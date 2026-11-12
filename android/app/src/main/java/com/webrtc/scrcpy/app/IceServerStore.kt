package com.webrtc.scrcpy.app

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log
import com.webrtc.scrcpy.core.webrtc.IceServerConfig

/**
 * STUN/TURN servers for the app's sessions, needed when the browser cannot reach the phone's IP
 * directly (NAT, cloud phones reached through adb). Set from adb on debuggable builds:
 *
 *   adb shell am start -n com.webrtc.scrcpy/.app.MainActivity --es ice_server 'turn:host:3478?transport=tcp|user|pass'
 *
 * An empty value clears it. Takes effect the next time the service starts.
 */
object IceServerStore {
    const val EXTRA_ICE_SERVER = "ice_server"
    private const val PREFS = "webrtc_scrcpy"
    private const val KEY_ICE_SERVER = "ice_server"
    private const val TAG = "IceServerStore"

    fun get(context: Context): List<IceServerConfig> {
        val spec = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ICE_SERVER, null)
        return listOfNotNull(spec?.let { IceServerConfig.parse(it) })
    }

    /** Applies an `ice_server` extra. Ignored on release builds, since MainActivity is exported. */
    fun applyFromIntent(context: Context, intent: Intent?) {
        val spec = intent?.getStringExtra(EXTRA_ICE_SERVER) ?: return
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!debuggable) {
            Log.w(TAG, "Ignoring $EXTRA_ICE_SERVER extra on a release build")
            return
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        when {
            spec.isBlank() -> prefs.remove(KEY_ICE_SERVER)
            IceServerConfig.parse(spec) != null -> prefs.putString(KEY_ICE_SERVER, spec)
            else -> {
                Log.w(TAG, "Invalid $EXTRA_ICE_SERVER: $spec")
                return
            }
        }
        prefs.apply()
        Log.i(TAG, "ICE server set to '${spec.substringBefore('|')}'")
    }
}
