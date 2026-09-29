package com.putnamworkbook.offline.data.content

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ProblemEntity::class,
        HintEntity::class,
        PrerequisiteEntity::class,
        ContentMetaEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class ContentDatabase : RoomDatabase() {
    abstract fun contentDao(): ContentDao

    companion object {
        fun create(context: Context): ContentDatabase =
            Room.databaseBuilder(context, ContentDatabase::class.java, "content.db")
                .createFromAsset("databases/content.db")
                .build()
    }
}
