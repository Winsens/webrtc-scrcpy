package com.webrtc.scrcpy.core.capture

import android.graphics.Rect
import android.os.Handler
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import java.lang.reflect.Method

/**
 * Silent screen capturer using SurfaceControl hidden APIs (for AOSP cloud devices / Root / Shell app_process)
 * Requires zero permission dialogs and mirrors the system main display layer directly
 */
class SurfaceControlCapturer(
    private val deviceWidth: Int = 1080,
    private val deviceHeight: Int = 1920
) : VideoCapturer, RefreshableCapturer {
    private val TAG = "SurfaceControlCapturer"

    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var capturerObserver: CapturerObserver? = null
    private var displayToken: IBinder? = null

    @Volatile
    private var isCapturing = false

    @Volatile
    private var lastFrameTimeMs = 0L

    private var captureHandler: Handler? = null
    private val frameForwarder = MonotonicFrameForwarder { capturerObserver }

    // Re-emit the last frame while the screen is static so the encoder keeps producing keyframes
    private val idleKeepAliveRunnable = object : Runnable {
        override fun run() {
            if (!isCapturing) return
            val elapsed = SystemClock.uptimeMillis() - lastFrameTimeMs
            if (lastFrameTimeMs > 0L && elapsed >= 450L) {
                surfaceTextureHelper?.forceFrame()
            }
            captureHandler?.postDelayed(this, 500L)
        }
    }

    private var createDisplayMethod: Method? = null
    private var openTransactionMethod: Method? = null
    private var closeTransactionMethod: Method? = null
    private var setDisplaySurfaceMethod: Method? = null
    private var setDisplayProjectionMethod: Method? = null
    private var setDisplayLayerStackMethod: Method? = null
    private var setDisplaySizeMethod: Method? = null
    private var destroyDisplayMethod: Method? = null

    init {
        try {
            val scClass = Class.forName("android.view.SurfaceControl")
            createDisplayMethod = scClass.getDeclaredMethod("createDisplay", String::class.java, Boolean::class.javaPrimitiveType)
            openTransactionMethod = scClass.getDeclaredMethod("openTransaction")
            closeTransactionMethod = scClass.getDeclaredMethod("closeTransaction")
            setDisplaySurfaceMethod = scClass.getDeclaredMethod("setDisplaySurface", IBinder::class.java, Surface::class.java)
            setDisplayLayerStackMethod = scClass.getDeclaredMethod("setDisplayLayerStack", IBinder::class.java, Int::class.javaPrimitiveType)
            setDisplaySizeMethod = scClass.getDeclaredMethod("setDisplaySize", IBinder::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            setDisplayProjectionMethod = scClass.getDeclaredMethod(
                "setDisplayProjection",
                IBinder::class.java,
                Int::class.javaPrimitiveType,
                Rect::class.java,
                Rect::class.java
            )
            destroyDisplayMethod = scClass.getDeclaredMethod("destroyDisplay", IBinder::class.java)
            Log.i(TAG, "SurfaceControl reflection methods initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SurfaceControl reflection: ${e.message}")
        }
    }

    override fun initialize(surfaceTextureHelper: SurfaceTextureHelper?, context: android.content.Context?, capturerObserver: CapturerObserver?) {
        this.surfaceTextureHelper = surfaceTextureHelper
        this.capturerObserver = capturerObserver
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        val helper = surfaceTextureHelper ?: run {
            Log.e(TAG, "SurfaceTextureHelper is null; cannot start capture")
            return
        }
        helper.setTextureSize(width, height)
        val surface = Surface(helper.surfaceTexture)

        try {
            // 1. Create virtual display
            val token = createDisplayMethod?.invoke(null, "webrtc-scrcpy-mirror", false) as? IBinder
            displayToken = token
            Log.i(TAG, "Created virtual display: token=$token")

            val layerStackRect = Rect(0, 0, deviceWidth, deviceHeight)
            val displayRect = Rect(0, 0, width, height)

            // 2. Configure transaction: bind Surface + LayerStack 0 + viewport projection
            openTransactionMethod?.invoke(null)
            try {
                setDisplaySurfaceMethod?.invoke(null, token, surface)
                setDisplayProjectionMethod?.invoke(null, token, 0, layerStackRect, displayRect)
                setDisplayLayerStackMethod?.invoke(null, token, 0)
                setDisplaySizeMethod?.invoke(null, token, width, height)
            } finally {
                closeTransactionMethod?.invoke(null)
            }

            val handler = Handler(helper.handler.looper)
            captureHandler = handler
            isCapturing = true
            lastFrameTimeMs = SystemClock.uptimeMillis()
            helper.startListening { videoFrame ->
                lastFrameTimeMs = SystemClock.uptimeMillis()
                frameForwarder.forward(videoFrame)
            }
            handler.removeCallbacks(idleKeepAliveRunnable)
            handler.postDelayed(idleKeepAliveRunnable, 500L)
            Log.i(TAG, "SurfaceControl mirror capture started: ${deviceWidth}x${deviceHeight} -> ${width}x${height} @ ${framerate}fps, LayerStack: 0")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SurfaceControl capture: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Re-emit the latest frame when a browser connects or refreshes, so a static screen is not black
     */
    override fun requestRefreshFrame() {
        val helper = surfaceTextureHelper ?: return
        val h = captureHandler ?: return
        if (!isCapturing) return
        h.post { helper.forceFrame() }
        h.postDelayed({ if (isCapturing) helper.forceFrame() }, 120L)
        h.postDelayed({ if (isCapturing) helper.forceFrame() }, 350L)
    }

    override fun stopCapture() {
        isCapturing = false
        captureHandler?.removeCallbacksAndMessages(null)
        captureHandler = null
        surfaceTextureHelper?.stopListening()
        displayToken?.let { token ->
            try {
                destroyDisplayMethod?.invoke(null, token)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to destroy DisplayToken: ${e.message}")
            }
            displayToken = null
        }
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        stopCapture()
        startCapture(width, height, framerate)
    }

    override fun dispose() {
        stopCapture()
    }

    override fun isScreencast(): Boolean = true
}
