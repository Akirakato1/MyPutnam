package com.putnamworkbook.offline.di

import android.content.Context
import com.putnamworkbook.offline.backup.BackupCoordinator
import com.putnamworkbook.offline.backup.InstalledContentLibraryStateBackupPort
import com.putnamworkbook.offline.data.content.ContentRepository
import com.putnamworkbook.offline.data.content.CatalogProblemRepository
import com.putnamworkbook.offline.data.content.CompositeReferenceRepository
import com.putnamworkbook.offline.data.content.FileInstalledPackageReader
import com.putnamworkbook.offline.data.content.LegacyContentCleanup
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
import com.putnamworkbook.offline.data.library.index.InstalledContentSnapshot
import com.putnamworkbook.offline.data.library.packages.CatalogStorePackageSource
import com.putnamworkbook.offline.data.library.packages.FilePackageStore
import com.putnamworkbook.offline.data.library.packages.HttpsPackageDownloader
import com.putnamworkbook.offline.data.library.packages.PackageArchiveValidator
import com.putnamworkbook.offline.data.library.packages.PackageDownloader
import com.putnamworkbook.offline.data.library.packages.PackageInstaller
import com.putnamworkbook.offline.data.library.packages.PackageStore
import com.putnamworkbook.offline.data.library.packages.QueuedPackageInstaller
import com.putnamworkbook.offline.data.library.network.AndroidNetworkStatusMonitor
import com.putnamworkbook.offline.data.library.network.NetworkStatusMonitor
import com.putnamworkbook.offline.data.user.ProgressRepository
import com.putnamworkbook.offline.data.user.RoomProgressRepository
import com.putnamworkbook.offline.data.user.RoomSettingsRepository
import com.putnamworkbook.offline.data.user.SettingsRepository
import com.putnamworkbook.offline.data.user.UserDatabase
import com.putnamworkbook.offline.export.ExportCoordinator
import com.putnamworkbook.offline.export.WebViewContentRasterizer
import com.putnamworkbook.offline.export.WorkbookPdfExporter
import com.putnamworkbook.offline.content.ui.FileContentResourceResolver
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

class AppContainer(
    context: Context,
    initializer: (suspend () -> ContentRepository)? = null,
    catalogStoreOverride: CatalogStore? = null,
    catalogClientOverride: CatalogClient? = null,
) {
    val applicationContext: Context = context.applicationContext
    internal val contentInitializer = initializer
    internal val legacyContentCleanup = LegacyContentCleanup.forContext(applicationContext)
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val catalogRefreshStarted = AtomicBoolean(false)
    val currentVersionCode: Int by lazy {
        applicationContext.packageManager
            .getPackageInfo(applicationContext.packageName, 0)
            .longVersionCode
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }
    val catalogStore: CatalogStore = catalogStoreOverride ?: FileCatalogStore(
        root = applicationContext.filesDir.toPath().resolve("content-library"),
        embeddedCatalog = ::readEmbeddedCatalog,
        codec = CatalogCodec(),
    )
    private val catalogClient: CatalogClient = catalogClientOverride ?: HttpsCatalogClient(CatalogOriginPolicy())
    private val catalogRefreshCoordinator = CatalogRefreshCoordinator(catalogStore, catalogClient)
    private val libraryIndexDatabase: LibraryIndexDatabase by lazy { LibraryIndexDatabase.create(applicationContext) }
    private val roomInstalledContentIndex: RoomInstalledContentIndex by lazy {
        RoomInstalledContentIndex(libraryIndexDatabase, currentVersionCode)
    }
    val installedContentIndex: InstalledContentIndex by lazy { roomInstalledContentIndex }
    val packageOperationStore: PackageOperationStore by lazy { roomInstalledContentIndex }
    private val installedContentSnapshot: StateFlow<InstalledContentSnapshot> by lazy {
        installedContentIndex.observe().stateIn(applicationScope, SharingStarted.Eagerly, InstalledContentSnapshot(0, emptyList()))
    }
    internal val packageDownloader: PackageDownloader by lazy {
        HttpsPackageDownloader(
            root = applicationContext.cacheDir.toPath().resolve("content-library-downloads"),
            originPolicy = CatalogOriginPolicy(),
        )
    }
    val packageStore: PackageStore by lazy {
        FilePackageStore(
            root = applicationContext.filesDir.toPath().resolve("content-library/packages"),
            verifier = PackageArchiveValidator(currentVersionCode),
            bundledPackageOpener = { path ->
                applicationContext.assets.open("content-library/packages/${path.substringAfterLast('/')}")
            },
        )
    }
    val packageInstaller: PackageInstaller by lazy {
        QueuedPackageInstaller(
            catalogSource = CatalogStorePackageSource(catalogStore),
            downloader = packageDownloader,
            packageStore = packageStore,
            contentIndex = installedContentIndex,
            operationStore = packageOperationStore,
            scope = applicationScope,
        )
    }
    val networkStatusMonitor: NetworkStatusMonitor by lazy { AndroidNetworkStatusMonitor(applicationContext) }
    private val packageRoot: Path by lazy { applicationContext.filesDir.toPath().resolve("content-library/packages") }
    val contentResourceResolver by lazy {
        FileContentResourceResolver(
            context = applicationContext,
            packagesRoot = packageRoot,
            snapshot = { installedContentSnapshot.value },
            onQuarantine = { key, digest, error ->
                applicationScope.launch { installedContentIndex.quarantine(key, digest, error) }
            },
        )
    }
    internal val packagedContentRepository: ContentRepository by lazy {
        CatalogProblemRepository(
            catalog = { catalogStore.state.value.catalog },
            snapshots = installedContentIndex.observe(),
            packageReader = FileInstalledPackageReader(packageRoot, packageStore, installedContentIndex),
        )
    }
    val referenceRepository by lazy {
        CompositeReferenceRepository(
            catalog = { catalogStore.state.value.catalog },
            snapshots = installedContentIndex.observe(),
            packageReader = FileInstalledPackageReader(packageRoot, packageStore, installedContentIndex),
        )
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
        BackupCoordinator(
            userDatabase,
            notebookRoot,
            libraryStatePort = InstalledContentLibraryStateBackupPort(installedContentIndex),
        )
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
                contentRasterizer = WebViewContentRasterizer(
                    renderingContext,
                    contentResourceResolver = contentResourceResolver,
                ),
            ),
        )

    fun startCatalogRefresh() {
        if (!catalogRefreshStarted.compareAndSet(false, true)) return
        applicationScope.launch {
            refreshCatalog()
        }
    }

    suspend fun refreshCatalog() {
        catalogRefreshCoordinator.refresh()
        catalogStore.state.value.catalog?.let { installedContentIndex.reconcileCatalog(it) }
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
    catalogState.catalog?.let { installedContentIndex.reconcileCatalog(it) }
    packageDownloader.recoverInterruptedState()
    packageStore.recoverInterruptedState()
    packageOperationStore.recoverInterruptedOperations(System.currentTimeMillis())
    contentInitializer?.let { return@withContext it() }
    val repository = packagedContentRepository
    legacyContentCleanup.cleanupAfterPackageInitialization(packageInitializationSucceeded = true)
    repository
}
