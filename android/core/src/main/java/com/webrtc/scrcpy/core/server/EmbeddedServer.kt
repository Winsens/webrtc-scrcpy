package com.webrtc.scrcpy.core.server

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import com.webrtc.scrcpy.core.webrtc.IceServerConfig
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * On-device lightweight HTTP static asset host and WebSocket signaling server.
 *
 * The signaling WebSocket gives full screen and input control, so it requires [accessToken]
 * (passed by the page as `?token=`) and rejects cross-origin browser pages. The static page
 * itself is public; it contains no secrets.
 */
class EmbeddedServer(
    port: Int,
    private val context: Context?,
    private val accessToken: String,
    private val signalingListener: SignalingListener,
    private val iceServers: List<IceServerConfig> = emptyList()
) : NanoWSD(port) {
    companion object {
        // In the companion so it exists before the instance init block preloads assets
        private val daemonArchives: List<java.io.File> by lazy {
            val classPath = System.getProperty("java.class.path").orEmpty()
                .split(java.io.File.pathSeparatorChar)
                .filter { it.isNotBlank() }
            (classPath + "/data/local/tmp/app-debug.apk")
                .distinct()
                .map { java.io.File(it) }
                .filter { it.isFile }
        }

        /** Random URL-safe token, about 128 bits. */
        fun generateAccessToken(): String {
            val alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
            val random = SecureRandom()
            return (1..22).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        }
    }

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

    override fun serve(session: IHTTPSession): Response {
        if (isWebsocketRequested(session)) {
            val rejection = signalingRejection(session)
            if (rejection != null) {
                Log.w(TAG, "Rejected signaling WebSocket from ${session.remoteIpAddress}: $rejection")
                return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Forbidden")
            }
        }
        return super.serve(session)
    }

    private fun hasValidToken(session: IHTTPSession): Boolean {
        val supplied = session.parameters["token"]?.firstOrNull() ?: return false
        return MessageDigest.isEqual(supplied.toByteArray(), accessToken.toByteArray())
    }

    /** Reason to refuse a signaling WebSocket handshake, or null if it is allowed. */
    private fun signalingRejection(session: IHTTPSession): String? {
        if (session.uri != "/ws") return "unknown path ${session.uri}"
        if (!hasValidToken(session)) return "missing or bad token"
        // Browsers always send Origin; it must be this server, so other sites cannot connect
        val origin = session.headers["origin"]
        if (origin != null) {
            val originHost = try { URI(origin).authority } catch (_: Exception) { null }
            if (originHost == null || !originHost.equals(session.headers["host"], ignoreCase = true)) {
                return "cross-origin request from $origin"
            }
        }
        return null
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
            // ICE servers for the page; behind the token because TURN credentials are secret
            uri == "/config" -> {
                if (hasValidToken(session)) {
                    newFixedLengthResponse(Response.Status.OK, "application/json", IceServerConfig.toJson(iceServers)).apply {
                        addHeader("Cache-Control", "no-store")
                    }
                } else {
                    newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Forbidden")
                }
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

        // 2. app_process daemon mode: the system context has no assets of ours, so read them
        //    from the APK/jar on the CLASSPATH (scripts/run_daemon.sh pushes the APK there)
        for (archive in daemonArchives) {
            try {
                java.util.zip.ZipFile(archive).use { zip ->
                    val entry = zip.getEntry("assets/$assetPath") ?: zip.getEntry(assetPath)
                    if (entry != null) {
                        zip.getInputStream(entry).use { return it.readBytes() }
                    }
                }
            } catch (_: Exception) {}
        }

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

