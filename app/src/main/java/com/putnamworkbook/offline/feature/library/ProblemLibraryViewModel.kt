package com.putnamworkbook.offline.feature.library

import com.putnamworkbook.offline.data.library.catalog.*
import com.putnamworkbook.offline.data.library.index.*
import com.putnamworkbook.offline.data.library.network.NetworkStatusMonitor
import com.putnamworkbook.offline.data.library.packages.PackageInstaller
import com.putnamworkbook.offline.data.user.ProblemProgress
import com.putnamworkbook.offline.data.user.ProgressRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ProblemLibraryViewModel(
    private val catalogStore: CatalogStore,
    private val installedContentIndex: InstalledContentIndex,
    private val packageInstaller: PackageInstaller,
    progressRepository: ProgressRepository,
    networkStatusMonitor: NetworkStatusMonitor,
    private val currentAppVersionCode: Int,
    private val refreshCatalog: suspend () -> Unit,
    focusKey: String? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val mutableUiState = MutableStateFlow(
        ProblemLibraryUiState(
            focusedKey = focusKey,
            filters = ProblemLibraryFilters(query = focusKey.orEmpty()),
        ),
    )
    val uiState: StateFlow<ProblemLibraryUiState> = mutableUiState.asStateFlow()

    private var catalogState = catalogStore.state.value
    private var installed = InstalledContentSnapshot(0, emptyList())
    private var operations: List<PackageOperation> = emptyList()
    private var progress: Map<com.putnamworkbook.offline.domain.model.ProblemKey, ProblemProgress> = emptyMap()
    private var online = true

    init {
        scope.launch { catalogStore.state.collect { catalogState = it; publish() } }
        scope.launch { installedContentIndex.observe().collect { installed = it; publish() } }
        scope.launch { packageInstaller.observeOperations().collect { operations = it; publish() } }
        scope.launch { progressRepository.observeAll().collect { progress = it; publish() } }
        scope.launch { networkStatusMonitor.isOnline.collect { online = it; publish() } }
    }

    fun setContentSegment(segment: LibraryContentSegment) = update {
        it.copy(contentSegment = segment, selectedKeys = emptySet(), filters = it.filters.copy(
            topicId = null,
            collectionId = null,
            setId = null,
            referenceCategoryId = null,
        ))
    }
    fun setQuery(query: String) = setFilters { copy(query = query) }
    fun setCollection(id: String?) = setFilters { copy(collectionId = id, setId = null) }
    fun setSet(id: String?) = setFilters { copy(setId = id) }
    fun setTopic(id: String?) = setFilters { copy(topicId = id) }
    fun setReferenceCategory(id: String?) = setFilters { copy(referenceCategoryId = id) }
    fun setInstallFilter(filter: LibraryInstallFilter) = setFilters { copy(installState = filter) }

    fun toggleSelection(key: String) = update { state ->
        state.copy(selectedKeys = state.selectedKeys.toMutableSet().apply {
            if (!add(key)) remove(key)
        })
    }

    fun selectAllVisible() = update { it.copy(selectedKeys = it.visibleEntries.mapTo(linkedSetOf()) { row -> row.key }) }
    fun clearSelection() = update { it.copy(selectedKeys = emptySet()) }

    fun requestItemAction(key: String) {
        val row = mutableUiState.value.allEntries.firstOrNull { it.key == key } ?: return
        row.operation?.takeIf { it.state == OperationState.FAILED }?.let {
            retryOperation(it.id)
            return
        }
        val action = row.primaryAction ?: return
        if (!row.actionEnabled) return
        requestAction(setOf(key), action)
    }

    fun requestSelectionAction(action: PackageAction) {
        val state = mutableUiState.value
        val keys = state.allEntries
            .filter { row -> row.key in state.selectedKeys && row.primaryAction == action && row.actionEnabled }
            .mapTo(linkedSetOf(), LibraryEntryRow::key)
        if (keys.isNotEmpty()) requestAction(keys, action)
    }

    private fun requestAction(keys: Set<String>, action: PackageAction) {
        if (action == PackageAction.DELETE) {
            val problems = mutableUiState.value.allEntries.count { it.key in keys && it.contentType == CatalogContentType.PROBLEM }
            update { it.copy(confirmation = LibraryActionConfirmation(keys, action, problems)) }
        } else enqueue(keys, action)
    }

    fun confirmAction() {
        val confirmation = mutableUiState.value.confirmation ?: return
        update { it.copy(confirmation = null) }
        enqueue(confirmation.keys, confirmation.action)
    }

    fun dismissAction() = update { it.copy(confirmation = null) }
    fun retryOperation(id: String) { scope.launch { packageInstaller.retry(id) } }
    fun cancelOperation(id: String) { scope.launch { packageInstaller.cancel(id) } }

    fun refresh() {
        if (mutableUiState.value.isRefreshing) return
        update { it.copy(isRefreshing = true) }
        scope.launch {
            try { refreshCatalog() } finally { update { it.copy(isRefreshing = false) } }
        }
    }

    fun close() = scope.cancel()

    private fun enqueue(keys: Set<String>, action: PackageAction) {
        if (action in setOf(PackageAction.INSTALL, PackageAction.UPDATE) && !online) return
        scope.launch { packageInstaller.enqueue(keys, action) }
    }

    private fun setFilters(transform: ProblemLibraryFilters.() -> ProblemLibraryFilters) = update {
        it.copy(filters = it.filters.transform(), selectedKeys = emptySet())
    }

    private fun update(transform: (ProblemLibraryUiState) -> ProblemLibraryUiState) {
        mutableUiState.value = transform(mutableUiState.value).withVisible()
    }

    private fun publish() {
        val rows = buildRows(catalogState.catalog, installed, operations, progress, online, currentAppVersionCode)
        val active = operations.filter { it.state !in setOf(OperationState.COMPLETE, OperationState.CANCELLED) }
        val batch = operations.takeIf { it.isNotEmpty() }?.let { values ->
            LibraryBatchProgress(
                completed = values.count { it.state == OperationState.COMPLETE },
                remaining = active.count { it.state != OperationState.FAILED },
                failed = values.count { it.state == OperationState.FAILED },
                cancelled = values.count { it.state == OperationState.CANCELLED },
            )
        }
        mutableUiState.value = mutableUiState.value.copy(
            allEntries = rows,
            selectedKeys = mutableUiState.value.selectedKeys.intersect(rows.mapTo(hashSetOf()) { it.key }),
            isOnline = online,
            lastSuccessfulRefreshAt = catalogState.lastSuccessfulRefreshAt,
            refreshError = catalogState.lastError,
            batchProgress = batch,
        ).withVisible()
    }

    private fun ProblemLibraryUiState.withVisible() = copy(
        visibleEntries = filterLibraryEntries(allEntries, contentSegment, filters),
    )
}

private fun buildRows(
    catalog: PublicationCatalog?,
    snapshot: InstalledContentSnapshot,
    operations: List<PackageOperation>,
    progress: Map<com.putnamworkbook.offline.domain.model.ProblemKey, ProblemProgress>,
    online: Boolean,
    appVersion: Int,
): List<LibraryEntryRow> {
    if (catalog == null) return emptyList()
    val installedByKey = snapshot.items.associateBy { it.metadata.key }
    val operationsByKey = operations.groupBy { it.contentKey }.mapValues { (_, rows) -> rows.maxByOrNull { it.updatedAt } }
    val rows = mutableListOf<LibraryEntryRow>()
    catalog.problems.forEach { item ->
        rows += row(
            key = item.key, type = CatalogContentType.PROBLEM, title = item.label,
            identity = "${item.collectionId} ${item.setId} ${item.problemId}", collectionId = item.collectionId,
            setId = item.setId, topics = item.topics, categoryId = null, aliases = emptyList(), keywords = emptyList(),
            descriptor = item.packageDescriptor, installed = installedByKey[item.key], operation = operationsByKey[item.key],
            hasProgress = progress.keys.any { it.canonical == item.key }, online = online, appVersion = appVersion, catalogListed = true,
        )
    }
    catalog.references.forEach { item ->
        rows += row(
            key = item.key, type = CatalogContentType.REFERENCE, title = item.title,
            identity = item.categoryId, collectionId = null, setId = null, topics = emptyList(),
            categoryId = item.categoryId, aliases = item.aliases, keywords = item.keywords,
            descriptor = item.packageDescriptor, installed = installedByKey[item.key], operation = operationsByKey[item.key],
            hasProgress = false, online = online, appVersion = appVersion, catalogListed = true,
        )
    }
    val catalogKeys = rows.mapTo(hashSetOf()) { it.key }
    snapshot.items.filter { it.metadata.key !in catalogKeys }.forEach { item ->
        val descriptor = item.downloaded?.descriptor ?: item.bundled?.descriptor ?: return@forEach
        rows += row(
            item.metadata.key, item.metadata.contentType, item.metadata.label, item.metadata.key,
            item.metadata.collectionId, item.metadata.setId, item.metadata.topics, item.metadata.categoryId,
            item.metadata.aliases, item.metadata.keywords, descriptor, item, operationsByKey[item.metadata.key],
            progress.keys.any { it.canonical == item.metadata.key }, online, appVersion, false,
        )
    }
    return rows
}

private fun row(
    key: String, type: CatalogContentType, title: String, identity: String, collectionId: String?, setId: String?,
    topics: List<String>, categoryId: String?, aliases: List<String>, keywords: List<String>, descriptor: PackageDescriptor,
    installed: ResolvedInstalledContent?, operation: PackageOperation?, hasProgress: Boolean, online: Boolean,
    appVersion: Int, catalogListed: Boolean,
): LibraryEntryRow {
    val status = when {
        descriptor.minAppVersionCode > appVersion -> LibraryEntryStatus.APP_UPDATE_REQUIRED
        installed?.availability == ContentAvailability.QUARANTINED -> LibraryEntryStatus.QUARANTINED
        installed?.availability == ContentAvailability.INSTALLED && installed.activeDigest != descriptor.sha256 -> LibraryEntryStatus.UPDATE_AVAILABLE
        installed?.availability == ContentAvailability.INSTALLED -> LibraryEntryStatus.INSTALLED
        else -> LibraryEntryStatus.AVAILABLE
    }
    val action = when {
        status == LibraryEntryStatus.APP_UPDATE_REQUIRED -> null
        installed?.availability == ContentAvailability.LOGICALLY_REMOVED && installed.bundled != null -> PackageAction.RESTORE
        status == LibraryEntryStatus.INSTALLED -> PackageAction.DELETE
        status == LibraryEntryStatus.UPDATE_AVAILABLE -> PackageAction.UPDATE
        else -> PackageAction.INSTALL
    }
    val enabled = action != null && (action !in setOf(PackageAction.INSTALL, PackageAction.UPDATE) || online) &&
        operation?.state !in setOf(OperationState.QUEUED, OperationState.DOWNLOADING, OperationState.VERIFYING, OperationState.ACTIVATING)
    return LibraryEntryRow(
        key, type, title, identity, collectionId, setId, topics, categoryId, aliases, keywords,
        descriptor.compressedBytes, status, action, enabled, installed?.bundled != null, hasProgress,
        catalogListed, operation,
    )
}
