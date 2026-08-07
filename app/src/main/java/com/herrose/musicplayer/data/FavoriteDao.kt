package com.herrose.musicplayer.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY title ASC")
    fun getAllFavorites(): Flow<List<FavoriteSong>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavorite(song: FavoriteSong)

    @Delete
    suspend fun removeFavorite(song: FavoriteSong)

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE songId = :songId)")
    suspend fun isFavorite(songId: Long): Boolean
}