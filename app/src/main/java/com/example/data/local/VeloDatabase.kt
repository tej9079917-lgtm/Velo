package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        CachedPageEntity::class,
        LocalShortcutEntity::class,
        ScratchpadClipEntity::class,
        DownloadedMediaEntity::class,
        SecretMediaEntity::class,
        SpeedTestHistoryEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class VeloDatabase : RoomDatabase() {
    abstract fun cachedPageDao(): CachedPageDao
    abstract fun localShortcutDao(): LocalShortcutDao
    abstract fun scratchpadClipDao(): ScratchpadClipDao
    abstract fun downloadedMediaDao(): DownloadedMediaDao
    abstract fun secretMediaDao(): SecretMediaDao
    abstract fun speedTestHistoryDao(): SpeedTestHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: VeloDatabase? = null

        fun getInstance(context: Context): VeloDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    VeloDatabase::class.java,
                    "velo_browser_local.db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
