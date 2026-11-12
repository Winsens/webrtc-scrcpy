package com.webrtc.scrcpy.core.webrtc

import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.PeerConnection

/**
 * A STUN/TURN server used by both ends of the connection. The device uses it directly, and the
 * web page fetches the same list from the device (`/config`), so one setting configures both.
 */
data class IceServerConfig(
    val url: String,
    val username: String? = null,
    val credential: String? = null
) {
    fun toIceServer(): PeerConnection.IceServer {
        val builder = PeerConnection.IceServer.builder(url)
        if (username != null) builder.setUsername(username)
        if (credential != null) builder.setPassword(credential)
        return builder.createIceServer()
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("urls", url)
        if (username != null) put("username", username)
        if (credential != null) put("credential", credential)
    }

    companion object {
        /** Parses `URL` or `URL|username|credential`, e.g. `turn:host:3478?transport=tcp|user|pass`. */
        fun parse(spec: String): IceServerConfig? {
            val parts = spec.split('|')
            val url = parts[0].trim()
            if (!(url.startsWith("stun:") || url.startsWith("turn:") || url.startsWith("turns:"))) return null
            return IceServerConfig(url, parts.getOrNull(1)?.takeIf { it.isNotEmpty() }, parts.getOrNull(2)?.takeIf { it.isNotEmpty() })
        }

        fun toJson(servers: List<IceServerConfig>): String =
            JSONObject().put("iceServers", JSONArray(servers.map { it.toJson() })).toString()
    }
}
