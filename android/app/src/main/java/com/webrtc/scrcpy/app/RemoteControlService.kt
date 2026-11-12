package com.webrtc.scrcpy.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.util.Size
import android.view.Display
import androidx.core.app.NotificationCompat
import com.webrtc.scrcpy.core.capture.MediaProjectionVideoCapturer
import com.webrtc.scrcpy.core.capture.ScreenGeometry
import com.webrtc.scrcpy.core.input.AccessibilityInputInjector
import com.webrtc.scrcpy.core.input.InputInjector
import com.webrtc.scrcpy.core.input.NativeInputInjector
import com.webrtc.scrcpy.core.protocol.ControlEvent
import com.webrtc.scrcpy.core.server.EmbeddedServer
import com.webrtc.scrcpy.core.webrtc.WebRtcEngine
import org.webrtc.IceCandidate

class RemoteControlService : Service() {
    private val TAG = "RemoteControlService"
    private val NOTIFICATION_CHANNEL_ID = "webrtc_scrcpy_channel"
    private val NOTIFICATION_ID = 1001

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var embeddedServer: EmbeddedServer? = null
    private var webRtcEngine: WebRtcEngine? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        const val ACTION_START = "com.webrtc.scrcpy.START"
        const val ACTION_STOP = "com.webrtc.scrcpy.STOP"
        const val ACTION_STATE_CHANGED = "com.webrtc.scrcpy.STATE_CHANGED"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_IS_RUNNING = "extra_is_running"

        var isRunning = false
            private set
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "RemoteControlService onCreate")
        createNotificationChannel()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WebRtcScrcpy:WakeLock")
        // Held for the whole session; released in onDestroy
        @Suppress("WakelockTimeout")
        wakeLock?.acquire()

        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifiManager.createWifiLock(mode, "WebRtcScrcpy:WifiLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire low-latency WifiLock: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.i(TAG, "RemoteControlService onStartCommand: action=$action startId=$startId")

        if (action == ACTION_START) {
            val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
            if (resultData != null) {
                startForeground(NOTIFICATION_ID, buildNotification())
                startRemoteEngine(resultData)
                isRunning = true
                notifyStateChanged(true)
            } else {
                Log.w(TAG, "ACTION_START missing screen capture grant EXTRA_RESULT_DATA")
            }
        } else if (action == ACTION_STOP) {
            Log.i(TAG, "Received ACTION_STOP, calling stopSelf")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun createDynamicInputInjector(): InputInjector {
        val accessibilityInjector = AccessibilityInputInjector()
        val nativeInjector = NativeInputInjector(this)
        val hasInjectPermission = checkSelfPermission("android.permission.INJECT_EVENTS") == PackageManager.PERMISSION_GRANTED

        return object : InputInjector {
            private var lastWarnMs = 0L

            private fun activeInjector(): InputInjector? {
                if (AccessibilityInputInjector.serviceInstance != null) {
                    return accessibilityInjector
                }
                if (hasInjectPermission) {
                    return nativeInjector
                }
                val now = SystemClock.uptimeMillis()
                if (now - lastWarnMs >= 5000L) {
                    lastWarnMs = now
                    Log.w(TAG, "Control event received, but Accessibility Service is disabled and INJECT_EVENTS is missing; enable WebRTC-Scrcpy Accessibility Service in system settings")
                }
                return null
            }

            override fun setScreenDimensions(width: Int, height: Int) {
                accessibilityInjector.setScreenDimensions(width, height)
                nativeInjector.setScreenDimensions(width, height)
            }

            override fun injectTouch(touch: ControlEvent.Touch) {
                activeInjector()?.injectTouch(touch)
            }

            override fun injectKey(key: ControlEvent.Key) {
                activeInjector()?.injectKey(key)
            }

            override fun injectScroll(scroll: ControlEvent.Scroll) {
                activeInjector()?.injectScroll(scroll)
            }

            override fun injectText(text: String) {
                activeInjector()?.injectText(text)
            }

            override fun injectCommand(commandId: Int) {
                activeInjector()?.injectCommand(commandId)
            }
        }
    }

    private fun startRemoteEngine(mediaProjectionResultData: Intent) {
        if (AccessibilityInputInjector.serviceInstance != null) {
            Log.i(TAG, "Accessibility Service is ready; using AccessibilityInputInjector for touch injection")
        } else {
            Log.w(TAG, "Accessibility Service is not enabled yet; rootless touch injection will activate automatically once enabled")
        }
        val injector = createDynamicInputInjector()

        val iceServers = IceServerStore.get(this)
        val engine = WebRtcEngine(this, injector, iceServers)
        this.webRtcEngine = engine

        // Capture at the display's own aspect ratio (short side capped at 720), 30fps,
        // so the frame has no black bars and touch mapping stays exact
        val captureSize = ScreenGeometry.realDisplaySize(this)?.let { ScreenGeometry.captureSize(it) } ?: Size(720, 1280)
        // Stop the service when the user or system revokes screen capture, instead of
        // reporting "running" with a frozen stream
        val capturer = MediaProjectionVideoCapturer(mediaProjectionResultData, object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "Screen capture was stopped; stopping service")
                mainHandler.post { stopSelf() }
            }
        })
        engine.startScreenStream(capturer, captureSize.width, captureSize.height, 30)

        // Start local HTTP + WebSocket signaling server (port 8080)
        val server = EmbeddedServer(8080, this, AccessTokenStore.get(this), object : EmbeddedServer.SignalingListener {
            override fun onOfferReceived(sdp: String) {
                engine.handleOffer(sdp) { answer ->
                    this@RemoteControlService.embeddedServer?.sendAnswer(answer.description)
                }
            }

            override fun onIceCandidateReceived(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
                engine.addRemoteIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
            }
        }, iceServers)

        engine.onLocalIceCandidate = { candidate ->
            this.embeddedServer?.sendIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
        }

        try {
            // timeout=0: Disable NanoHTTPD default 5s read timeout so idle signaling WebSockets stay open
            server.start(0, false)
            this.embeddedServer = server
            Log.i(TAG, "WebRTC-Scrcpy embedded server started, listening on port 8080")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start EmbeddedServer: ${e.message}")
        }
    }

    private fun notifyStateChanged(running: Boolean) {
        val broadcast = Intent(ACTION_STATE_CHANGED).apply {
            putExtra(EXTRA_IS_RUNNING, running)
            setPackage(packageName)
        }
        sendBroadcast(broadcast)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "WebRTC Remote Control Service",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                setSound(null, null)
                enableVibration(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("WebRTC-Scrcpy Running")
            .setContentText("Tap to manage background screen casting and remote control")
            .setSmallIcon(android.R.drawable.ic_menu_slideshow)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        Log.i(TAG, "RemoteControlService onDestroy started")
        isRunning = false
        notifyStateChanged(false)

        try {
            embeddedServer?.stop()
            webRtcEngine?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying service: ${e.message}")
        }
        super.onDestroy()
        Log.i(TAG, "RemoteControlService destroyed")
    }
}
