package com.herrose.musicplayer

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).build()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val metadata = player.mediaMetadata
                MusicWidgetProvider.updateAllWidgets(
                    applicationContext,
                    metadata.title?.toString() ?: "Not playing",
                    metadata.artist?.toString() ?: "",
                    isPlaying
                )
            }

            override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
                MusicWidgetProvider.updateAllWidgets(
                    applicationContext,
                    mediaMetadata.title?.toString() ?: "Not playing",
                    mediaMetadata.artist?.toString() ?: "",
                    player.isPlaying
                )
            }
        })

        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}