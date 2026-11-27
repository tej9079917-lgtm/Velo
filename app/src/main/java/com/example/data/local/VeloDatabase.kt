package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        CachedPageEntity::class,
        LocalShortcutEntity::class,
        ScratchpadClipEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class VeloDatabase : RoomDatabase() {
    abstract fun cachedPageDao(): CachedPageDao
    abstract fun localShortcutDao(): LocalShortcutDao
    abstract fun scratchpadClipDao(): ScratchpadClipDao

    companion object {
        @Volatile
        private var instance: VeloDatabase? = null

        fun getInstance(context: Context): VeloDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    VeloDatabase::class.java,
                    "velo_offline_cache.db"
                ).fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
