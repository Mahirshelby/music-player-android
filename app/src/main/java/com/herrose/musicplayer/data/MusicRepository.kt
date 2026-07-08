package com.herrose.musicplayer.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore

class MusicRepository(private val context: Context) {

    fun getAllSongs(): List<Song> {
        val songs = mutableListOf<Song>()

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"

        val query = context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            "${MediaStore.Audio.Media.TITLE} ASC"
        )

        query?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val rawTitle = cursor.getString(titleCol) ?: "Unknown"
                val title = cleanTitle(rawTitle)
                val rawArtist = cursor.getString(artistCol)
                val artist = if (rawArtist.isNullOrBlank() || rawArtist == "<unknown>") "Unknown Artist" else rawArtist
                val duration = cursor.getLong(durationCol)
                val albumId = cursor.getLong(albumIdCol)

                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
                )
                val albumArtUri = ContentUris.withAppendedId(
                    android.net.Uri.parse("content://media/external/albumart"), albumId
                )

                songs.add(
                    Song(
                        id = id,
                        title = title,
                        artist = artist,
                        duration = duration,
                        uri = contentUri.toString(),
                        albumArtUri = albumArtUri.toString()
                    )
                )
            }
        }

        return songs
    }

    private fun cleanTitle(rawTitle: String): String {
        var title = rawTitle

        title = title.substringBeforeLast(".")
        title = title.replace("-", " ").replace("_", " ")

        // Remove anything in parentheses/brackets (e.g. "(music.com", "[mp3]")
        title = title.replace(Regex("[\\(\\[].*"), "")

        // Remove trailing numeric IDs (e.g. "412230")
        title = title.replace(Regex("\\s+\\d{4,}$"), "")

        title = title.replace(Regex("\\s+"), " ").trim()
        title = title.split(" ").joinToString(" ") { word ->
            word.replaceFirstChar { it.uppercase() }
        }

        return title.ifBlank { rawTitle }
    }
}