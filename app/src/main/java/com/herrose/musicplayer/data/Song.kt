package com.herrose.musicplayer.data

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val duration: Long,
    val uri: String,
    val albumArtUri: String?
)
