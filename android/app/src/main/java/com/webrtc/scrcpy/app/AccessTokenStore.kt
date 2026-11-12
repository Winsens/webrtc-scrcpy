package com.webrtc.scrcpy.app

import android.content.Context
import com.webrtc.scrcpy.core.server.EmbeddedServer

/**
 * Persists the token the browser must present to open the signaling WebSocket, so the URL the
 * user bookmarks keeps working across service restarts. Reset it to revoke old links.
 */
object AccessTokenStore {
    private const val PREFS = "webrtc_scrcpy"
    private const val KEY_TOKEN = "access_token"

    @Synchronized
    fun get(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_TOKEN, null)?.let { return it }
        val token = EmbeddedServer.generateAccessToken()
        prefs.edit().putString(KEY_TOKEN, token).apply()
        return token
    }
}
