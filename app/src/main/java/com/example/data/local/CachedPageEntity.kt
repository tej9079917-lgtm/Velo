package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cached_pages")
data class CachedPageEntity(
    @PrimaryKey val urlHash: String,
    val userId: String,
    val title: String,
    val url: String,
    val excerpt: String,
    val encryptedHtml: String,
    val iv: String,
    val isEncrypted: Boolean,
    val archived: Boolean = false,
    val byteSize: Long,
    val cachedAtMillis: Long = System.currentTimeMillis()
)

@Entity(tableName = "local_shortcuts")
data class LocalShortcutEntity(
    @PrimaryKey val id: String,
    val title: String,
    val url: String,
    val accentHex: String,
    val orderIndex: Int
)

@Entity(tableName = "scratchpad_clips")
data class ScratchpadClipEntity(
    @PrimaryKey val id: String,
    val title: String,
    val sourceUrl: String,
    val content: String,
    val tag: String,
    val updatedAtMillis: Long = System.currentTimeMillis()
)
