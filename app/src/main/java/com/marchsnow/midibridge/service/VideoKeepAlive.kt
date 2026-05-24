package com.marchsnow.midibridge.service

import android.content.Context
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.marchsnow.midibridge.util.Logger

/**
 * Keep-alive via MediaSession simulating video playback.
 * The system treats media-playing apps with higher process priority,
 * making the service much less likely to be killed.
 */
object VideoKeepAlive {

    private var mediaSession: MediaSessionCompat? = null
    var enabled: Boolean = false
        private set

    fun start(context: Context): Boolean {
        if (mediaSession != null) return true

        return runCatching {
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
            enabled = true
            Logger.i("VideoKeepAlive", "MediaSession keep-alive started")
            true
        }.getOrDefault(false)
    }

    fun stop() {
        runCatching { mediaSession?.isActive = false }
        runCatching { mediaSession?.release() }
        mediaSession = null
        enabled = false
        Logger.i("VideoKeepAlive", "MediaSession keep-alive stopped")
    }
}
