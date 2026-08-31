package com.marchsnow.midibridge.service

import android.content.Context
import android.content.SharedPreferences
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.marchsnow.midibridge.util.Logger

/**
 * Keep-alive via MediaSession simulating video playback.
 * The system treats media-playing apps with higher process priority,
 * making the service much less likely to be killed.
 *
 * State model (AND-V1'):
 *  - [enabled]      — RUNTIME state: is a MediaSession active right now
 *  - persisted "desired" flag — the USER's choice, survives restarts and is
 *    re-applied by [restoreIfDesired] when the service starts.
 *    start()/stop() manage the session only; the desired flag is written
 *    by the ViewModel toggle (single source of truth for user intent).
 */
object VideoKeepAlive {

    private const val PREFS_NAME  = "midibridge_keepalive"
    private const val KEY_DESIRED = "desired_enabled"

    private var mediaSession: MediaSessionCompat? = null
    private var prefs: SharedPreferences? = null

    /** RUNTIME state — a MediaSession is currently active. */
    var enabled: Boolean = false
        private set

    fun start(context: Context): Boolean {
        if (mediaSession != null) {
            enabled = true
            return true
        }

        val ok = runCatching {
            val session = MediaSessionCompat(context, "MIDIBridgeKeepAlive").apply {
                isActive = true
                setPlaybackState(
                    PlaybackStateCompat.Builder()
                        .setState(PlaybackStateCompat.STATE_PLAYING, 0, 1.0f)
                        .setActions(PlaybackStateCompat.ACTION_PLAY)
                        .build()
                )
            }
            mediaSession = session
            true
        }.getOrDefault(false)

        enabled = ok
        if (ok) {
            Logger.i("VideoKeepAlive", "MediaSession keep-alive started")
        } else {
            // Surfaces to the UI via the return value — caller shows a Toast
            // and keeps the switch off instead of silently believing it's on
            Logger.e("VideoKeepAlive", "MediaSession keep-alive failed to start")
        }
        return ok
    }

    fun stop() {
        runCatching { mediaSession?.isActive = false }
        runCatching { mediaSession?.release() }
        mediaSession = null
        enabled = false
        Logger.i("VideoKeepAlive", "MediaSession keep-alive stopped")
    }

    // ─── Persisted user preference (survives restarts) ───

    /** Persist the user's desired keep-alive state. */
    fun persistDesired(context: Context, desired: Boolean) {
        prefs(context).edit().putBoolean(KEY_DESIRED, desired).apply()
    }

    /** The user's last chosen keep-alive state. */
    fun isDesired(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DESIRED, false)

    /**
     * Re-apply the persisted user preference at service startup.
     * Returns the resulting runtime state.
     */
    fun restoreIfDesired(context: Context): Boolean {
        if (!isDesired(context)) return false
        return start(context)
    }

    private fun prefs(context: Context): SharedPreferences =
        prefs ?: context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .also { prefs = it }
}
