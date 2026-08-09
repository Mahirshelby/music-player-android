package com.herrose.musicplayer

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors

class MusicWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_PLAY_PAUSE = "com.herrose.musicplayer.widget.PLAY_PAUSE"
        const val ACTION_NEXT = "com.herrose.musicplayer.widget.NEXT"
        const val ACTION_PREV = "com.herrose.musicplayer.widget.PREV"

        var lastTitle: String = "Not playing"
        var lastArtist: String = ""
        var lastIsPlaying: Boolean = false

        fun updateAllWidgets(context: Context, title: String, artist: String, isPlaying: Boolean) {
            lastTitle = title
            lastArtist = artist
            lastIsPlaying = isPlaying
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, MusicWidgetProvider::class.java))
            for (id in ids) {
                updateWidget(context, manager, id)
            }
        }

        fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_music_player)
            views.setTextViewText(R.id.widget_title, lastTitle)
            views.setTextViewText(R.id.widget_artist, lastArtist)
            views.setImageViewResource(
                R.id.widget_play_pause,
                if (lastIsPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            )

            views.setOnClickPendingIntent(R.id.widget_play_pause, actionPendingIntent(context, ACTION_PLAY_PAUSE))
            views.setOnClickPendingIntent(R.id.widget_next, actionPendingIntent(context, ACTION_NEXT))
            views.setOnClickPendingIntent(R.id.widget_prev, actionPendingIntent(context, ACTION_PREV))

            manager.updateAppWidget(widgetId, views)
        }

        private fun actionPendingIntent(context: Context, action: String): PendingIntent {
            val intent = Intent(context, MusicWidgetProvider::class.java).apply { this.action = action }
            return PendingIntent.getBroadcast(
                context, action.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            updateWidget(context, appWidgetManager, id)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_PLAY_PAUSE, ACTION_NEXT, ACTION_PREV -> {
                val pendingResult = goAsync()
                val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
                val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
                controllerFuture.addListener({
                    try {
                        val controller = controllerFuture.get()
                        when (intent.action) {
                            ACTION_PLAY_PAUSE -> {
                                if (controller.isPlaying) controller.pause() else controller.play()
                            }
                            ACTION_NEXT -> controller.seekToNextMediaItem()
                            ACTION_PREV -> controller.seekToPreviousMediaItem()
                        }
                        controller.release()
                    } catch (e: Exception) {
                        // ignore
                    } finally {
                        pendingResult.finish()
                    }
                }, MoreExecutors.directExecutor())
            }
        }
    }
}