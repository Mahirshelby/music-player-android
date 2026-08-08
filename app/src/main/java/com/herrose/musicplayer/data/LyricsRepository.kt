package com.herrose.musicplayer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class LyricLine(val timeMs: Long, val text: String)
data class LyricsResult(val plain: String?, val synced: List<LyricLine>?)

object LyricsRepository {
    suspend fun fetchLyrics(title: String, artist: String): LyricsResult = withContext(Dispatchers.IO) {
        try {
            val encodedTitle = URLEncoder.encode(title, "UTF-8")
            val encodedArtist = URLEncoder.encode(artist, "UTF-8")
            val url = URL("https://lrclib.net/api/get?track_name=$encodedTitle&artist_name=$encodedArtist")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 8000
            connection.readTimeout = 8000

            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                val plain = json.optString("plainLyrics", "").ifBlank { null }
                val syncedRaw = json.optString("syncedLyrics", "").ifBlank { null }
                val synced = syncedRaw?.let { parseLrc(it) }
                LyricsResult(plain, synced)
            } else {
                LyricsResult(null, null)
            }
        } catch (e: Exception) {
            LyricsResult(null, null)
        }
    }

    private fun parseLrc(lrc: String): List<LyricLine> {
        val regex = Regex("""\[(\d+):(\d+\.\d+)\](.*)""")
        return lrc.lines().mapNotNull { line ->
            val match = regex.find(line) ?: return@mapNotNull null
            val min = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val sec = match.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
            val timeMs = min * 60000L + (sec * 1000).toLong()
            val text = match.groupValues[3].trim()
            if (text.isNotBlank()) LyricLine(timeMs, text) else null
        }
    }
}