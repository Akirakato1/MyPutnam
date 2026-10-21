package com.putnamworkbook.offline.data.content

import android.content.Context
import java.nio.file.Files
import java.nio.file.Path

class LegacyContentCleanup(
    private val legacyDatabase: Path,
    private val clearLegacyState: () -> Unit,
) {
    fun cleanupAfterPackageInitialization(packageInitializationSucceeded: Boolean) {
        if (!packageInitializationSucceeded) return
        val databaseName = legacyDatabase.fileName.toString()
        listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
            Files.deleteIfExists(legacyDatabase.resolveSibling("$databaseName$suffix"))
        }
        clearLegacyState()
    }

    companion object {
        private const val DATABASE_NAME = "content.db"
        private const val CONTENT_STATE_PREFERENCES = "immutable-content-state"

        fun forContext(context: Context): LegacyContentCleanup {
            val applicationContext = context.applicationContext
            return LegacyContentCleanup(applicationContext.getDatabasePath(DATABASE_NAME).toPath()) {
                applicationContext.getSharedPreferences(CONTENT_STATE_PREFERENCES, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .commit()
            }
        }
    }
}
