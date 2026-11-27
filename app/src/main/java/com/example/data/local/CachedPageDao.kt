package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CachedPageDao {
    @Query("SELECT * FROM cached_pages WHERE userId = :userId ORDER BY cachedAtMillis DESC")
    fun observeCachedPages(userId: String): Flow<List<CachedPageEntity>>

    @Query("SELECT * FROM cached_pages WHERE urlHash = :urlHash AND userId = :userId LIMIT 1")
    suspend fun getByUrlHash(userId: String, urlHash: String): CachedPageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(page: CachedPageEntity)

    @Query("UPDATE cached_pages SET archived = :archived WHERE urlHash = :urlHash AND userId = :userId")
    suspend fun updateArchived(userId: String, urlHash: String, archived: Boolean)

    @Query("DELETE FROM cached_pages WHERE urlHash = :urlHash AND userId = :userId")
    suspend fun deleteByUrlHash(userId: String, urlHash: String)

    @Query("DELETE FROM cached_pages WHERE userId = :userId")
    suspend fun deleteAllForUser(userId: String)
}

@Dao
interface LocalShortcutDao {
    @Query("SELECT * FROM local_shortcuts ORDER BY orderIndex ASC")
    fun observeShortcuts(): Flow<List<LocalShortcutEntity>>

    @Query("SELECT COUNT(*) FROM local_shortcuts")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<LocalShortcutEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: LocalShortcutEntity)

    @Query("DELETE FROM local_shortcuts WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface ScratchpadClipDao {
    @Query("SELECT * FROM scratchpad_clips ORDER BY updatedAtMillis DESC")
    fun observeClips(): Flow<List<ScratchpadClipEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(clip: ScratchpadClipEntity)

    @Query("DELETE FROM scratchpad_clips WHERE id = :id")
    suspend fun deleteById(id: String)
}
