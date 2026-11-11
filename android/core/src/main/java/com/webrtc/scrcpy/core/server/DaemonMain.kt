package com.webrtc.scrcpy.core.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.webrtc.scrcpy.core.capture.SurfaceControlCapturer
import com.webrtc.scrcpy.core.input.NativeInputInjector
import com.webrtc.scrcpy.core.webrtc.WebRtcEngine
import org.webrtc.IceCandidate
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Standalone daemon entrypoint for AOSP cloud devices / ADB shell CLI (app_process headless daemon)
 */
object DaemonMain {
    private const val TAG = "DaemonMain"

    private fun getSystemContext(): Context {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        var activityThread = activityThreadClass.getDeclaredMethod("currentActivityThread").apply { isAccessible = true }.invoke(null)
        if (activityThread == null) {
            activityThread = activityThreadClass.getDeclaredMethod("systemMain").apply { isAccessible = true }.invoke(null)
        }
        val rawContext = activityThreadClass.getDeclaredMethod("getSystemContext").apply { isAccessible = true }.invoke(activityThread) as Context
        return object : ContextWrapper(rawContext) {
            override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?): Intent? = null
            override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?, broadcastPermission: String?, scheduler: Handler?): Intent? = null
            override fun unregisterReceiver(receiver: BroadcastReceiver?) {}
            override fun getApplicationContext(): Context = this
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("[*] Starting webrtc-scrcpy in Shell/Root daemon mode...")
        Looper.prepareMainLooper()

        var port = 8080
        for (arg in args) {
            if (arg.startsWith("--port=")) {
                port = arg.substring(7).toIntOrNull() ?: 8080
            }
        }

        try {
            val context = getSystemContext()
            val injector = NativeInputInjector(context)
            println("[+] NativeInputInjector initialized (UID 2000 native input injection)")

            val engine = WebRtcEngine(context, injector)
            val capturer = SurfaceControlCapturer()
            // 720p 30fps
            engine.startScreenStream(capturer, 720, 1280, 30)
            println("[+] SurfaceControlCapturer mirror capture started")

            var server: EmbeddedServer? = null
            server = EmbeddedServer(port, context, object : EmbeddedServer.SignalingListener {
                override fun onOfferReceived(sdp: String) {
                    println("[+] Received WebRTC SDP Offer from browser, creating Answer...")
                    engine.handleOffer(sdp) { answer ->
                        println("[+] WebRTC Answer created, sending back via signaling")
                        server?.sendAnswer(answer.description)
                    }
                }

                override fun onIceCandidateReceived(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
                    engine.addRemoteIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
                }
            })

            engine.onLocalIceCandidate = { candidate ->
                server?.sendIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
            }

            // timeout=0: Disable NanoHTTPD default 5s read timeout so idle signaling WebSockets stay open
            server.start(0, false)
            val lanIp = resolveLanIpAddress() ?: "<DEVICE_IP>"
            println("[+] Embedded web & signaling server ready on port $port")
            println("[+] LAN access: http://$lanIp:$port")
            println("[+] ADB tunnel access: http://localhost:$port")
        } catch (e: Throwable) {
            System.err.println("[-] Daemon startup error: ${e.message}")
            e.printStackTrace()
            return
        }

        Looper.loop()
    }

    private fun resolveLanIpAddress(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.asSequence()
                ?.filter { it.isUp && !it.isLoopback }
                ?.flatMap { it.inetAddresses.toList().asSequence() }
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }
}
