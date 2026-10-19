package com.putnamworkbook.offline.di

import android.content.Context
import com.putnamworkbook.offline.backup.BackupCoordinator
import com.putnamworkbook.offline.data.content.ContentDatabase
import com.putnamworkbook.offline.data.content.ContentInitializer
import com.putnamworkbook.offline.data.content.ContentIntegrityValidator
import com.putnamworkbook.offline.data.content.ContentRepository
import com.putnamworkbook.offline.data.content.RoomContentRepository
import com.putnamworkbook.offline.data.library.catalog.CatalogClient
import com.putnamworkbook.offline.data.library.catalog.CatalogCodec
import com.putnamworkbook.offline.data.library.catalog.CatalogOriginPolicy
import com.putnamworkbook.offline.data.library.catalog.CatalogRefreshCoordinator
import com.putnamworkbook.offline.data.library.catalog.CatalogSource
import com.putnamworkbook.offline.data.library.catalog.CatalogStore
import com.putnamworkbook.offline.data.library.catalog.FileCatalogStore
import com.putnamworkbook.offline.data.library.catalog.HttpsCatalogClient
import com.putnamworkbook.offline.data.library.index.InstalledContentIndex
import com.putnamworkbook.offline.data.library.index.LibraryIndexDatabase
import com.putnamworkbook.offline.data.library.index.PackageOperationStore
import com.putnamworkbook.offline.data.library.index.RoomInstalledContentIndex
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
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppContainer(
    context: Context,
    initializer: ContentInitializer? = null,
    catalogStoreOverride: CatalogStore? = null,
    catalogClientOverride: CatalogClient? = null,
) {
    val applicationContext: Context = context.applicationContext
    private val contentDatabase: ContentDatabase by lazy { ContentDatabase.create(applicationContext) }
    internal val contentInitializer: ContentInitializer = initializer ?: ContentInitializer {
        ContentIntegrityValidator(contentDatabase).validate()
        RoomContentRepository(contentDatabase)
    }
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val catalogRefreshStarted = AtomicBoolean(false)
    val catalogStore: CatalogStore = catalogStoreOverride ?: FileCatalogStore(
        root = applicationContext.filesDir.toPath().resolve("content-library"),
        embeddedCatalog = ::readEmbeddedCatalog,
        codec = CatalogCodec(),
    )
    private val catalogClient: CatalogClient = catalogClientOverride ?: HttpsCatalogClient(CatalogOriginPolicy())
    private val catalogRefreshCoordinator = CatalogRefreshCoordinator(catalogStore, catalogClient)
    private val libraryIndexDatabase: LibraryIndexDatabase by lazy { LibraryIndexDatabase.create(applicationContext) }
    private val roomInstalledContentIndex: RoomInstalledContentIndex by lazy {
        val versionCode = applicationContext.packageManager
            .getPackageInfo(applicationContext.packageName, 0)
            .longVersionCode
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        RoomInstalledContentIndex(libraryIndexDatabase, versionCode)
    }
    val installedContentIndex: InstalledContentIndex by lazy { roomInstalledContentIndex }
    val packageOperationStore: PackageOperationStore by lazy { roomInstalledContentIndex }
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

    fun startCatalogRefresh() {
        if (!catalogRefreshStarted.compareAndSet(false, true)) return
        applicationScope.launch { catalogRefreshCoordinator.refresh() }
    }

    private fun readEmbeddedCatalog(): ByteArray = try {
        applicationContext.assets.open(EMBEDDED_CATALOG_ASSET).use { it.readBytes() }
    } catch (_: IOException) {
        EMPTY_COMPATIBILITY_CATALOG.encodeToByteArray()
    }

    private companion object {
        const val EMBEDDED_CATALOG_ASSET = "content-library/catalog.json"
        const val EMPTY_COMPATIBILITY_CATALOG =
            "{\"schemaVersion\":1,\"catalogId\":\"myputnam-compatibility\",\"catalogRevision\":\"0000000000000000000000000000000000000000000000000000000000000000\",\"generatedAt\":\"2026-10-18T00:00:00.000Z\",\"minAppVersionCode\":1,\"collections\":[],\"topics\":[],\"referenceCategories\":[],\"problems\":[],\"references\":[]}"
    }
}

suspend fun AppContainer.initializeContent(): ContentRepository = withContext(Dispatchers.IO) {
    catalogStore.initialize()
    val catalogState = catalogStore.state.value
    val bundledCatalog = catalogState.embeddedCatalog
        ?: catalogState.catalog.takeIf { catalogState.source == CatalogSource.EMBEDDED }
    bundledCatalog?.let { installedContentIndex.reconcileBundled(it) }
    packageOperationStore.recoverInterruptedOperations(System.currentTimeMillis())
    contentInitializer.initialize()
}
