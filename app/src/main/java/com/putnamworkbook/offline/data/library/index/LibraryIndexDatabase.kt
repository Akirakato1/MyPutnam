package com.putnamworkbook.offline.data.library.index

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        LibraryContentEntity::class,
        LibraryPackageEntity::class,
        PackageOperationEntity::class,
        LibraryMetadataEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class LibraryIndexDatabase : RoomDatabase() {
    abstract fun libraryIndexDao(): LibraryIndexDao

    companion object {
        fun create(context: Context): LibraryIndexDatabase =
            Room.databaseBuilder(context, LibraryIndexDatabase::class.java, "library-index.db").build()
    }
}
