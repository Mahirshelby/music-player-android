package com.herrose.musicplayer.data

import androidx.room.Entity

@Entity(tableName = "playlist_songs", primaryKeys = ["playlistId", "songId"])
data class PlaylistSong(
    val playlistId: Long,
    val songId: Long,
    val title: String,
    val artist: String,
    val uri: String,
    val albumArtUri: String?
)