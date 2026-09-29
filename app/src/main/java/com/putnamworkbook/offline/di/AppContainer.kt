package com.putnamworkbook.offline.di

import android.content.Context
import com.putnamworkbook.offline.backup.BackupCoordinator
import com.putnamworkbook.offline.content.ui.OriginalPdfRepository
import com.putnamworkbook.offline.data.content.ContentDatabase
import com.putnamworkbook.offline.data.content.ContentRepository
import com.putnamworkbook.offline.data.content.RoomContentRepository
import com.putnamworkbook.offline.data.user.ProgressRepository
import com.putnamworkbook.offline.data.user.RoomProgressRepository
import com.putnamworkbook.offline.data.user.RoomSettingsRepository
import com.putnamworkbook.offline.data.user.SettingsRepository
import com.putnamworkbook.offline.data.user.UserDatabase
import com.putnamworkbook.offline.notebook.storage.FileNotebookRepository
import com.putnamworkbook.offline.notebook.storage.NotebookRepository
import java.nio.file.Path

class AppContainer(context: Context) {
    val applicationContext: Context = context.applicationContext
    val contentDatabase: ContentDatabase by lazy { ContentDatabase.create(applicationContext) }
    val contentRepository: ContentRepository by lazy { RoomContentRepository(contentDatabase) }
    val userDatabase: UserDatabase by lazy { UserDatabase.create(applicationContext) }
    val progressRepository: ProgressRepository by lazy { RoomProgressRepository(userDatabase) }
    val settingsRepository: SettingsRepository by lazy { RoomSettingsRepository(userDatabase) }
    val notebookRoot: Path by lazy { applicationContext.filesDir.toPath().resolve("notebooks") }
    val notebookRepository: NotebookRepository by lazy {
        FileNotebookRepository(notebookRoot)
    }
    val backupCoordinator: BackupCoordinator by lazy {
        BackupCoordinator(userDatabase, notebookRoot)
    }
    val originalPdfRepository: OriginalPdfRepository by lazy {
        OriginalPdfRepository(
            assets = applicationContext.assets,
            cacheDirectory = applicationContext.cacheDir.resolve("original-pdf"),
        )
    }
}
