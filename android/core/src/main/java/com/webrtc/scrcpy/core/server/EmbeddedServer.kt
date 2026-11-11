package com.webrtc.scrcpy.core.server

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * On-device lightweight HTTP static asset host and WebSocket signaling server
 */
class EmbeddedServer(
    port: Int,
    private val context: Context?,
    private val signalingListener: SignalingListener
) : NanoWSD(port) {
    private val TAG = "EmbeddedServer"

    private data class CachedAsset(
        val bytes: ByteArray,
        val mimeType: String,
        val etag: String
    )

    private val assetCache = ConcurrentHashMap<String, CachedAsset>()
    private val etagSeed = System.currentTimeMillis().toString(36)

    interface SignalingListener {
        fun onOfferReceived(sdp: String)
        fun onIceCandidateReceived(sdpMid: String, sdpMLineIndex: Int, candidate: String)
    }

    @Volatile
    private var activeSocket: SignalingWebSocket? = null

    init {
        preloadWebAssets()
    }

    private fun preloadWebAssets() {
        listOf(
            "web/index.html" to "text/html; charset=utf-8",
            "web/css/style.css" to "text/css; charset=utf-8",
            "web/js/protocol.js" to "application/javascript; charset=utf-8",
            "web/js/webrtc.js" to "application/javascript; charset=utf-8",
            "web/js/app.js" to "application/javascript; charset=utf-8"
        ).forEach { (path, mime) ->
            loadAssetCached(path, mime)
        }
    }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket {
        return SignalingWebSocket(handshake)
    }

    override fun serveHttp(session: IHTTPSession): Response {
        val uri = session.uri
        Log.d(TAG, "HTTP request: $uri")

        return when {
            uri == "/" || uri == "/index.html" -> {
                serveAsset(session, "web/index.html", "text/html; charset=utf-8")
            }
            uri == "/favicon.ico" -> {
                newFixedLengthResponse(Response.Status.NO_CONTENT, "image/x-icon", "")
            }
            uri.startsWith("/css/") -> {
                serveAsset(session, "web$uri", "text/css; charset=utf-8")
            }
            uri.startsWith("/js/") -> {
                serveAsset(session, "web$uri", "application/javascript; charset=utf-8")
            }
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
        }
    }

    private fun readAssetBytes(assetPath: String): ByteArray? {
        // 1. Try reading from Android Context Assets
        try {
            context?.assets?.open(assetPath)?.use { return it.readBytes() }
        } catch (_: Exception) {}

        // 2. Try reading directly from APK zip (for app_process daemon mode)
        try {
            val apkPath = "/data/local/tmp/app-debug.apk"
            val file = java.io.File(apkPath)
            if (file.exists()) {
                java.util.zip.ZipFile(file).use { zip ->
                    val entry = zip.getEntry("assets/$assetPath") ?: zip.getEntry(assetPath)
                    if (entry != null) {
                        zip.getInputStream(entry).use { return it.readBytes() }
                    }
                }
            }
        } catch (_: Exception) {}

        return null
    }

    private fun loadAssetCached(assetPath: String, mimeType: String): CachedAsset? {
        assetCache[assetPath]?.let { return it }
        val bytes = readAssetBytes(assetPath) ?: return null
        val etag = "\"$etagSeed-${bytes.size}-${bytes.contentHashCode()}\""
        val cached = CachedAsset(bytes, mimeType, etag)
        assetCache[assetPath] = cached
        return cached
    }

    private fun serveAsset(session: IHTTPSession, assetPath: String, mimeType: String): Response {
        val cached = loadAssetCached(assetPath, mimeType)
        if (cached != null) {
            val ifNoneMatch = session.headers["if-none-match"]
            if (ifNoneMatch != null && ifNoneMatch == cached.etag) {
                return newFixedLengthResponse(Response.Status.NOT_MODIFIED, cached.mimeType, "").apply {
                    addHeader("ETag", cached.etag)
                    addHeader("Cache-Control", "no-cache")
                }
            }
            return newFixedLengthResponse(
                Response.Status.OK,
                cached.mimeType,
                ByteArrayInputStream(cached.bytes),
                cached.bytes.size.toLong()
            ).apply {
                addHeader("ETag", cached.etag)
                addHeader("Cache-Control", "no-cache")
            }
        }

        // 3. Fallback asset
        if (assetPath.endsWith("index.html")) {
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", EmbeddedFallbackAssets.FALLBACK_HTML)
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
    }

    fun sendAnswer(sdp: String) {
        val json = JSONObject().apply {
            put("type", "answer")
            put("sdp", sdp)
        }
        sendJson(json.toString())
    }

    fun sendIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        val json = JSONObject().apply {
            put("type", "candidate")
            put("sdpMid", sdpMid)
            put("sdpMLineIndex", sdpMLineIndex)
            put("candidate", candidate)
        }
        sendJson(json.toString())
    }

    private fun sendJson(msg: String) {
        activeSocket?.let { ws ->
            if (ws.isOpen) {
                try {
                    ws.send(msg)
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to send signaling message: ${e.message}")
                }
            }
        }
    }

    inner class SignalingWebSocket(handshake: IHTTPSession) : WebSocket(handshake) {
        override fun onOpen() {
            val prev = activeSocket
            activeSocket = this
            if (prev != null && prev !== this && prev.isOpen) {
                try {
                    prev.close(WebSocketFrame.CloseCode.NormalClosure, "replaced", false)
                } catch (_: Exception) {}
            }
            Log.i(TAG, "WebRTC client connected to signaling WebSocket")
        }

        override fun onClose(code: WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
            if (activeSocket == this) activeSocket = null
            Log.i(TAG, "Signaling client disconnected: $reason")
        }

        override fun onMessage(message: WebSocketFrame) {
            val payload = message.textPayload ?: return
            try {
                val json = JSONObject(payload)
                val type = json.optString("type")
                if ("offer" == type) {
                    val sdp = json.getString("sdp")
                    signalingListener.onOfferReceived(sdp)
                } else if ("candidate" == type) {
                    val candidate = json.getString("candidate")
                    val sdpMid = json.optString("sdpMid", "")
                    val sdpMLineIndex = json.optInt("sdpMLineIndex", 0)
                    signalingListener.onIceCandidateReceived(sdpMid, sdpMLineIndex, candidate)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse signaling JSON: ${e.message}")
            }
        }

        override fun onPong(pong: WebSocketFrame?) {}
        override fun onException(exception: IOException?) {}
    }
}

