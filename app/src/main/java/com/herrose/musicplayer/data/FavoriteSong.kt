package com.herrose.musicplayer.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorites")
data class FavoriteSong(
    @PrimaryKey val songId: Long,
    val title: String,
    val artist: String,
    val uri: String,
    val albumArtUri: String?
)