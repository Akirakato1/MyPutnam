package com.putnamworkbook.offline.data.content

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import java.security.MessageDigest

@Database(
    entities = [
        ProblemEntity::class,
        HintEntity::class,
        PrerequisiteEntity::class,
        ContentMetaEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class ContentDatabase : RoomDatabase() {
    abstract fun contentDao(): ContentDao

    companion object {
        internal const val DATABASE_NAME = "content.db"
        internal const val CONTENT_STATE_PREFERENCES = "immutable-content-state"
        internal const val CONTENT_ASSET_HASH_KEY = "asset-sha256"
        private const val CONTENT_ASSET_PATH = "databases/content.db"

        fun create(context: Context): ContentDatabase = create(
            context = context,
            databaseName = DATABASE_NAME,
            statePreferencesName = CONTENT_STATE_PREFERENCES,
        )

        @Synchronized
        internal fun create(
            context: Context,
            databaseName: String,
            statePreferencesName: String,
        ): ContentDatabase {
            val applicationContext = context.applicationContext
            val packagedHash = applicationContext.assets.open(CONTENT_ASSET_PATH).use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
                digest.digest().joinToString("") { byte -> "%02x".format(byte) }
            }
            val state = applicationContext.getSharedPreferences(
                statePreferencesName,
                Context.MODE_PRIVATE,
            )
            if (state.getString(CONTENT_ASSET_HASH_KEY, null) != packagedHash) {
                applicationContext.deleteDatabase(databaseName)
            }

            val database = Room.databaseBuilder(
                applicationContext,
                ContentDatabase::class.java,
                databaseName,
            )
                .createFromAsset(CONTENT_ASSET_PATH)
                .build()
            database.openHelper.writableDatabase
            state.edit().putString(CONTENT_ASSET_HASH_KEY, packagedHash).apply()
            return database
        }
    }
}
