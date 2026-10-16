package com.putnamworkbook.offline.di

import android.content.Context
import com.putnamworkbook.offline.backup.BackupCoordinator
import com.putnamworkbook.offline.data.content.ContentDatabase
import com.putnamworkbook.offline.data.content.ContentInitializer
import com.putnamworkbook.offline.data.content.ContentIntegrityValidator
import com.putnamworkbook.offline.data.content.ContentRepository
import com.putnamworkbook.offline.data.content.RoomContentRepository
import com.putnamworkbook.offline.data.user.ProgressRepository
import com.putnamworkbook.offline.data.user.RoomProgressRepository
import com.putnamworkbook.offline.data.user.RoomSettingsRepository
import com.putnamworkbook.offline.data.user.SettingsRepository
import com.putnamworkbook.offline.data.user.UserDatabase
import com.putnamworkbook.offline.export.ExportCoordinator
import com.putnamworkbook.offline.export.WebViewContentRasterizer
import com.putnamworkbook.offline.export.WorkbookPdfExporter
import com.putnamworkbook.offline.freeworkspace.FileFreeWorkspaceRepository
import com.putnamworkbook.offline.freeworkspace.FreeWorkspaceRepository
import com.putnamworkbook.offline.notebook.storage.FileNotebookRepository
import com.putnamworkbook.offline.notebook.storage.NotebookRepository
import com.putnamworkbook.offline.notebook.edit.NotebookClipboard
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppContainer(
    context: Context,
    initializer: ContentInitializer? = null,
) {
    val applicationContext: Context = context.applicationContext
    private val contentDatabase: ContentDatabase by lazy { ContentDatabase.create(applicationContext) }
    internal val contentInitializer: ContentInitializer = initializer ?: ContentInitializer {
        ContentIntegrityValidator(contentDatabase).validate()
        RoomContentRepository(contentDatabase)
    }
    val userDatabase: UserDatabase by lazy { UserDatabase.create(applicationContext) }
    val progressRepository: ProgressRepository by lazy { RoomProgressRepository(userDatabase) }
    val settingsRepository: SettingsRepository by lazy { RoomSettingsRepository(userDatabase) }
    val notebookRoot: Path by lazy { applicationContext.filesDir.toPath().resolve("notebooks") }
    val notebookRepository: NotebookRepository by lazy {
        FileNotebookRepository(notebookRoot)
    }
    val freeWorkspaceRepository: FreeWorkspaceRepository by lazy {
        FileFreeWorkspaceRepository(notebookRoot, notebookRepository)
    }
    val notebookClipboard: NotebookClipboard by lazy { NotebookClipboard() }
    val backupCoordinator: BackupCoordinator by lazy {
        BackupCoordinator(userDatabase, notebookRoot)
    }
    fun createExportCoordinator(
        renderingContext: Context,
        contentRepository: ContentRepository,
    ): ExportCoordinator =
        ExportCoordinator(
            WorkbookPdfExporter(
                contentRepository = contentRepository,
                progressRepository = progressRepository,
                notebookRepository = notebookRepository,
                contentRasterizer = WebViewContentRasterizer(renderingContext),
            ),
        )
}

suspend fun AppContainer.initializeContent(): ContentRepository = withContext(Dispatchers.IO) {
    contentInitializer.initialize()
}
