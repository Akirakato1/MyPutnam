package com.putnamworkbook.offline.navigation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import android.net.Uri
import com.putnamworkbook.offline.backup.BackupCoordinator
import com.putnamworkbook.offline.data.content.ContentRepository
import com.putnamworkbook.offline.data.user.ProgressRepository
import com.putnamworkbook.offline.data.user.SettingsRepository
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.domain.model.ProblemSetKey
import com.putnamworkbook.offline.feature.archive.ArchiveScreen
import com.putnamworkbook.offline.feature.archive.ArchiveViewModel
import com.putnamworkbook.offline.feature.archive.ProblemListScreen
import com.putnamworkbook.offline.feature.home.HomeScreen
import com.putnamworkbook.offline.feature.home.HomeViewModel
import com.putnamworkbook.offline.feature.freenotebook.FreeWorkspaceLibraryScreen
import com.putnamworkbook.offline.feature.freenotebook.FreeWorkspaceListViewModel
import com.putnamworkbook.offline.feature.freenotebook.DurableSaveNotifier
import com.putnamworkbook.offline.feature.freenotebook.FreeNotebookEditorCoordinator
import com.putnamworkbook.offline.feature.freenotebook.FreeNotebookEditorScreen
import com.putnamworkbook.offline.feature.settings.SettingsScreen
import com.putnamworkbook.offline.feature.settings.SettingsViewModel
import com.putnamworkbook.offline.feature.workspace.ReferencePane
import com.putnamworkbook.offline.feature.workspace.WorkspaceScreen
import com.putnamworkbook.offline.feature.workspace.WorkspaceViewModel
import com.putnamworkbook.offline.export.ExportCoordinator
import com.putnamworkbook.offline.export.ExportResult
import com.putnamworkbook.offline.export.WorkbookExportOptions
import com.putnamworkbook.offline.notebook.storage.NotebookRepository
import com.putnamworkbook.offline.notebook.edit.NotebookClipboard
import com.putnamworkbook.offline.notebook.edit.SelectionFlipAxis
import com.putnamworkbook.offline.notebook.ui.NotebookPane
import com.putnamworkbook.offline.notebook.ui.NotebookViewModel
import com.putnamworkbook.offline.freeworkspace.FreeWorkspaceRepository
import com.putnamworkbook.offline.freeworkspace.FreeWorkspaceResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.UUID
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

data class WorkspaceDependencies(
    val settingsRepository: SettingsRepository,
    val notebookRepository: NotebookRepository,
    val notebookClipboard: NotebookClipboard,
    val backupCoordinator: BackupCoordinator,
    val exportCoordinator: ExportCoordinator,
    val appVersion: String,
    val contentVersion: String,
    val freeWorkspaceRepository: FreeWorkspaceRepository,
)

private class NotebookViewModelFactory(
    private val dependencies: WorkspaceDependencies,
    private val onDurableSave: (Long) -> Unit = {},
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(NotebookViewModel::class.java))
        @Suppress("UNCHECKED_CAST")
        return NotebookViewModel(
            dependencies.notebookRepository,
            dependencies.settingsRepository,
            clipboard = dependencies.notebookClipboard,
            onDurableSave = onDurableSave,
        ) as T
    }
}

@Composable
fun PutnamNavHost(
    contentRepository: ContentRepository,
    progressRepository: ProgressRepository,
    workspaceDependencies: WorkspaceDependencies? = null,
    freeWorkspaceRepository: FreeWorkspaceRepository? = workspaceDependencies?.freeWorkspaceRepository,
    startDestination: String = AppRoute.Home.path,
    onWorkspaceActiveChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val homeViewModel = remember(progressRepository, contentRepository) {
        HomeViewModel(progressRepository, contentRepository)
    }
    val archiveViewModel = remember(contentRepository, progressRepository) {
        ArchiveViewModel(contentRepository, progressRepository)
    }
    val homeState by homeViewModel.uiState.collectAsState()
    val archiveState by archiveViewModel.uiState.collectAsState()

    DisposableEffect(homeViewModel, archiveViewModel) {
        onDispose {
            homeViewModel.close()
            archiveViewModel.close()
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        composable(AppRoute.HOME) {
            HomeScreen(
                state = homeState,
                onContinue = { key -> navController.navigate(AppRoute.Workspace(key).path) },
                onSelectProblem = { navController.navigate(AppRoute.Library.path) },
                onFreeNotebook = { navController.navigate(AppRoute.FreeWorkspaces.path) },
                onSettings = { navController.navigate(AppRoute.Settings.path) },
            )
        }
        composable(AppRoute.FREE_WORKSPACES) { entry ->
            if (freeWorkspaceRepository == null) {
                Placeholder(text = "Free Notebook", tag = "free-workspace-library-placeholder")
            } else {
                val viewModel = remember(entry, freeWorkspaceRepository) {
                    FreeWorkspaceListViewModel(
                        repository = freeWorkspaceRepository,
                        onWorkspaceCreated = { id -> navController.navigate(AppRoute.FreeWorkspace(id).path) },
                    )
                }
                val state by viewModel.uiState.collectAsState()
                DisposableEffect(viewModel) { onDispose { viewModel.close() } }
                FreeWorkspaceLibraryScreen(
                    state = state,
                    onBack = { navController.popBackStack() },
                    onNew = viewModel::requestCreate,
                    onOpen = { id -> navController.navigate(AppRoute.FreeWorkspace(id).path) },
                    onRename = viewModel::requestRename,
                    onDelete = viewModel::requestDelete,
                    onNameChanged = viewModel::updateName,
                    onNameConfirmed = viewModel::submitName,
                    onNameDismissed = viewModel::dismissNameDialog,
                    onDeleteConfirmed = viewModel::confirmDelete,
                    onDeleteDismissed = viewModel::dismissDelete,
                    onRetry = viewModel::retry,
                )
            }
        }
        composable(
            route = AppRoute.FREE_WORKSPACE_PATTERN,
            arguments = listOf(navArgument("workspaceId") { type = NavType.StringType }),
        ) { entry ->
            val rawId = Uri.decode(entry.arguments?.getString("workspaceId").orEmpty())
            val workspaceId = parseStrictUuid(rawId)
            if (workspaceId == null) {
                Placeholder(text = "This free notebook link is invalid", tag = "free-workspace-invalid-route")
            } else if (workspaceDependencies == null || freeWorkspaceRepository == null) {
                Placeholder(
                    text = "Free notebook $workspaceId",
                    tag = "free-workspace-placeholder-$workspaceId",
                )
            } else {
                FreeNotebookDestination(
                    workspaceId = workspaceId,
                    repository = freeWorkspaceRepository,
                    dependencies = workspaceDependencies,
                    viewModelStoreOwner = entry,
                    onWorkspaceActiveChanged = onWorkspaceActiveChanged,
                    onBack = {
                        if (!navController.popBackStack()) {
                            navController.navigate(AppRoute.FreeWorkspaces.path) { launchSingleTop = true }
                        }
                    },
                    onWorkspaceReplaced = { replacementId ->
                        navController.navigate(AppRoute.FreeWorkspace(replacementId).path) {
                            popUpTo(AppRoute.FreeWorkspace(workspaceId).path) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }
        }
        composable(AppRoute.LIBRARY) {
            ArchiveScreen(
                state = archiveState,
                onBack = { navController.popBackStack() },
                onSelectTopic = archiveViewModel::selectTopic,
                onSelectSet = { key -> navController.navigate(AppRoute.SetProblems(key).path) },
                onSelectProblem = { key -> navController.navigate(AppRoute.Workspace(key).path) },
            )
        }
        composable(
            route = AppRoute.SET_PROBLEMS_PATTERN,
            arguments = listOf(
                navArgument("collectionId") { type = NavType.StringType },
                navArgument("setId") { type = NavType.StringType },
            ),
        ) { entry ->
            val setKey = ProblemSetKey(
                Uri.decode(requireNotNull(entry.arguments?.getString("collectionId"))),
                Uri.decode(requireNotNull(entry.arguments?.getString("setId"))),
            )
            LaunchedEffect(setKey) { archiveViewModel.selectSet(setKey) }
            ProblemListScreen(
                setKey = setKey,
                state = archiveState,
                onBack = { navController.popBackStack() },
                onSelectProblem = { key -> navController.navigate(AppRoute.Workspace(key).path) },
            )
        }
        composable(
            route = AppRoute.WORKSPACE_PATTERN,
            arguments = listOf(
                navArgument("collectionId") { type = NavType.StringType },
                navArgument("setId") { type = NavType.StringType },
                navArgument("problemId") { type = NavType.StringType },
            ),
        ) { entry ->
            val key = ProblemKey(
                Uri.decode(requireNotNull(entry.arguments?.getString("collectionId"))),
                Uri.decode(requireNotNull(entry.arguments?.getString("setId"))),
                Uri.decode(requireNotNull(entry.arguments?.getString("problemId"))),
            )
            if (workspaceDependencies == null) {
                Placeholder(
                    text = "Workspace ${key.canonical}",
                    tag = "workspace-placeholder-${key.canonical}",
                )
            } else {
                WorkspaceDestination(
                    key = key,
                    contentRepository = contentRepository,
                    progressRepository = progressRepository,
                    dependencies = workspaceDependencies,
                    viewModelStoreOwner = entry,
                    onWorkspaceActiveChanged = onWorkspaceActiveChanged,
                    onHome = {
                        if (!navController.popBackStack(AppRoute.HOME, inclusive = false)) {
                            navController.navigate(AppRoute.HOME) { launchSingleTop = true }
                        }
                    },
                )
            }
        }
        composable(AppRoute.SETTINGS) {
            if (workspaceDependencies == null) {
                Placeholder(text = "Settings", tag = "settings-placeholder")
            } else {
                SettingsDestination(
                    dependencies = workspaceDependencies,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

private fun parseStrictUuid(value: String): UUID? = runCatching { UUID.fromString(value) }
    .getOrNull()
    ?.takeIf { it.toString().equals(value, ignoreCase = true) }

@Composable
private fun FreeNotebookDestination(
    workspaceId: UUID,
    repository: FreeWorkspaceRepository,
    dependencies: WorkspaceDependencies,
    viewModelStoreOwner: ViewModelStoreOwner,
    onWorkspaceActiveChanged: (Boolean) -> Unit,
    onBack: () -> Unit,
    onWorkspaceReplaced: (UUID) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val coordinatorReference = remember(workspaceId) {
        AtomicReference<FreeNotebookEditorCoordinator?>(null)
    }
    val notifier = remember(workspaceId, repository, scope) {
        DurableSaveNotifier(
            scope = scope,
            notify = {
                when (val result = repository.markModified(workspaceId)) {
                    is FreeWorkspaceResult.Success -> Unit
                    is FreeWorkspaceResult.Failure -> throw IOException(result.message)
                    is FreeWorkspaceResult.NameRejected -> throw IOException("Workspace metadata could not be updated")
                }
            },
            onFailure = { error -> coordinatorReference.get()?.reportSaveFailure(error) },
        )
    }
    val notebookFactory = remember(dependencies, notifier) {
        NotebookViewModelFactory(dependencies, notifier::enqueue)
    }
    val notebookViewModel = remember(viewModelStoreOwner, workspaceId, notebookFactory) {
        ViewModelProvider(viewModelStoreOwner, notebookFactory).get(
            "free-notebook-$workspaceId",
            NotebookViewModel::class.java,
        )
    }
    SideEffect {
        notebookViewModel.updateDurableSaveListener(notifier::enqueue)
    }
    val coordinator = remember(workspaceId, repository, notifier, notebookViewModel) {
        FreeNotebookEditorCoordinator(
            workspaceId = workspaceId,
            repository = repository,
            notifier = notifier,
            scope = scope,
            openNotebook = { key -> notebookViewModel.open(key) },
            awaitNotebookIdle = notebookViewModel::awaitIdle,
            notebookRevision = notebookViewModel::durableSaveRevision,
            onBackReady = onBack,
            onWorkspaceReplaced = onWorkspaceReplaced,
        )
    }
    coordinatorReference.set(coordinator)
    val editorState by coordinator.uiState.collectAsState()
    val notebookState by notebookViewModel.uiState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(coordinator) {
        onWorkspaceActiveChanged(true)
        onDispose {
            onWorkspaceActiveChanged(false)
            coordinatorReference.compareAndSet(coordinator, null)
            coordinator.close()
        }
    }
    DisposableEffect(lifecycleOwner, notebookViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) notebookViewModel.requestFlush()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler(onBack = coordinator::back)
    FreeNotebookEditorScreen(
        state = editorState,
        onBack = coordinator::back,
        onSave = coordinator::save,
        onSaveAs = coordinator::requestSaveAs,
        onSaveAsNameChanged = coordinator::updateSaveAsName,
        onSaveAsConfirmed = coordinator::confirmSaveAs,
        onSaveAsDismissed = coordinator::dismissSaveAs,
        notebookContent = {
            NotebookPane(
                state = notebookState,
                brush = notebookState.brush,
                onStrokeFinished = notebookViewModel::commitStroke,
                onUndo = notebookViewModel::undo,
                onRedo = notebookViewModel::redo,
                onAddPage = notebookViewModel::addPage,
                onRequestDeletePage = notebookViewModel::requestDeletePage,
                onRequestClearPage = notebookViewModel::requestClearPage,
                onConfirmDeletePage = notebookViewModel::confirmDeletePage,
                onConfirmClearPage = notebookViewModel::confirmClearPage,
                onDismissConfirmation = notebookViewModel::dismissConfirmation,
                onSelectPage = notebookViewModel::selectPage,
                onSetToolMode = notebookViewModel::setToolMode,
                onBrushChanged = notebookViewModel::setBrush,
                onSelect = notebookViewModel::select,
                onViewportChanged = notebookViewModel::updateViewport,
                onPreviewMove = notebookViewModel::previewMove,
                onCommitMove = notebookViewModel::commitMove,
                onPreviewScale = notebookViewModel::previewScale,
                onCommitScale = notebookViewModel::commitScale,
                onPreviewRotation = notebookViewModel::previewRotation,
                onCommitRotation = notebookViewModel::commitRotationPreview,
                onCancelPreview = notebookViewModel::cancelTransformPreview,
                onCutSelection = notebookViewModel::cutSelection,
                onCopySelection = notebookViewModel::copySelection,
                onPaste = notebookViewModel::paste,
                onDeleteSelection = notebookViewModel::deleteSelection,
                onFlipHorizontal = { notebookViewModel.flipSelection(SelectionFlipAxis.HORIZONTAL) },
                onFlipVertical = { notebookViewModel.flipSelection(SelectionFlipAxis.VERTICAL) },
                onSetRotationSnapEnabled = notebookViewModel::setRotationSnapEnabled,
                onClearSelection = notebookViewModel::cancelSelection,
            )
        },
    )
}

@Composable
private fun WorkspaceDestination(
    key: ProblemKey,
    contentRepository: ContentRepository,
    progressRepository: ProgressRepository,
    dependencies: WorkspaceDependencies,
    viewModelStoreOwner: ViewModelStoreOwner,
    onWorkspaceActiveChanged: (Boolean) -> Unit,
    onHome: () -> Unit,
) {
    val workspaceViewModel = remember(key, contentRepository, progressRepository) {
        WorkspaceViewModel(contentRepository, progressRepository)
    }
    val notebookFactory = remember(dependencies) { NotebookViewModelFactory(dependencies) }
    val notebookViewModel = remember(viewModelStoreOwner, key, notebookFactory) {
        ViewModelProvider(viewModelStoreOwner, notebookFactory).get(
            "notebook-${key.canonical}",
            NotebookViewModel::class.java,
        )
    }
    val scope = rememberCoroutineScope()
    val workspaceState by workspaceViewModel.uiState.collectAsState()
    val notebookState by notebookViewModel.uiState.collectAsState()
    var notebookOpened by remember(key) { mutableStateOf(false) }
    var exportMessage by remember(key) { mutableStateOf<String?>(null) }
    var exportInProgress by remember(key) { mutableStateOf(false) }
    var hasPendingExport by rememberSaveable(key.canonical) { mutableStateOf(false) }
    var pendingIncludeHints by rememberSaveable(key.canonical) { mutableStateOf(true) }
    var pendingIncludeSolution by rememberSaveable(key.canonical) { mutableStateOf(true) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val exportPdf = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri ->
        val options = if (hasPendingExport) {
            WorkbookExportOptions(
                includeRevealedHints = pendingIncludeHints,
                includeRevealedSolution = pendingIncludeSolution,
            )
        } else {
            null
        }
        hasPendingExport = false
        if (uri != null && options != null) {
            scope.launch {
                exportMessage = "Exporting PDF…"
                exportInProgress = true
                try {
                    notebookViewModel.awaitIdle()
                    val output = runCatching { context.contentResolver.openOutputStream(uri) }.getOrNull()
                    if (output == null) {
                        exportMessage = null
                        workspaceViewModel.reportIntegrityError("The selected PDF file could not be opened")
                        return@launch
                    }
                    when (
                        val result = dependencies.exportCoordinator.export(
                            key,
                            options,
                            output,
                        )
                    ) {
                        is ExportResult.Success -> exportMessage = "PDF saved (${result.pageCount} pages)"
                        is ExportResult.Failure -> {
                            exportMessage = null
                            workspaceViewModel.reportIntegrityError(result.message)
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    exportMessage = null
                    workspaceViewModel.reportIntegrityError(error.message ?: "PDF export failed")
                } finally {
                    exportInProgress = false
                }
            }
        }
    }

    DisposableEffect(workspaceViewModel, notebookViewModel) {
        onWorkspaceActiveChanged(true)
        onDispose {
            onWorkspaceActiveChanged(false)
            workspaceViewModel.close()
        }
    }
    DisposableEffect(lifecycleOwner, notebookViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                notebookViewModel.requestFlush()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(key) {
        workspaceViewModel.open(key)
    }
    LaunchedEffect(workspaceState.progress, notebookOpened) {
        val progress = workspaceState.progress
        if (progress != null && !notebookOpened) {
            notebookOpened = true
            notebookViewModel.open(key, progress.activePageIndex)
        }
    }
    LaunchedEffect(
        notebookState.document?.pages?.size,
        notebookState.activePageIndex,
        workspaceState.selectedReferenceTab,
    ) {
        val document = notebookState.document
        if (document != null) {
            workspaceViewModel.updateNotebookState(document.pages.size, notebookState.activePageIndex)
        }
    }
    val returnHome: () -> Unit = {
        scope.launch {
            try {
                notebookViewModel.awaitIdle()
                notebookViewModel.uiState.value.let { latestNotebook ->
                    latestNotebook.document?.let { document ->
                        workspaceViewModel.updateNotebookState(document.pages.size, latestNotebook.activePageIndex)
                    }
                    workspaceViewModel.awaitIdle()
                }
                onHome()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                workspaceViewModel.reportIntegrityError(error.message ?: "Notebook save failed")
            }
        }
    }
    BackHandler(onBack = returnHome)
    WorkspaceScreen(
        state = workspaceState,
        onHome = returnHome,
        onSetCompleted = workspaceViewModel::setCompleted,
        onExportPdf = { options ->
            pendingIncludeHints = options.includeRevealedHints
            pendingIncludeSolution = options.includeRevealedSolution
            hasPendingExport = true
            exportPdf.launch("${key.canonical.replace("/", "%2F")}-workbook.pdf")
        },
        exportMessage = exportMessage,
        exportInProgress = exportInProgress,
        referenceContent = {
            ReferencePane(
                state = workspaceState,
                onSelectTab = workspaceViewModel::selectReferenceTab,
                onRevealNextStage = workspaceViewModel::revealNextStage,
                onSelectHint = workspaceViewModel::selectHint,
                onSelectPrerequisite = workspaceViewModel::selectPrerequisite,
                onIntegrityError = workspaceViewModel::reportIntegrityError,
            )
        },
        notebookContent = {
            NotebookPane(
                state = notebookState,
                brush = notebookState.brush,
                onStrokeFinished = notebookViewModel::commitStroke,
                onUndo = notebookViewModel::undo,
                onRedo = notebookViewModel::redo,
                onAddPage = notebookViewModel::addPage,
                onRequestDeletePage = notebookViewModel::requestDeletePage,
                onRequestClearPage = notebookViewModel::requestClearPage,
                onConfirmDeletePage = notebookViewModel::confirmDeletePage,
                onConfirmClearPage = notebookViewModel::confirmClearPage,
                onDismissConfirmation = notebookViewModel::dismissConfirmation,
                onSelectPage = notebookViewModel::selectPage,
                onSetToolMode = notebookViewModel::setToolMode,
                onBrushChanged = notebookViewModel::setBrush,
                onSelect = notebookViewModel::select,
                onViewportChanged = notebookViewModel::updateViewport,
                onPreviewMove = notebookViewModel::previewMove,
                onCommitMove = notebookViewModel::commitMove,
                onPreviewScale = notebookViewModel::previewScale,
                onCommitScale = notebookViewModel::commitScale,
                onPreviewRotation = notebookViewModel::previewRotation,
                onCommitRotation = notebookViewModel::commitRotationPreview,
                onCancelPreview = notebookViewModel::cancelTransformPreview,
                onCutSelection = notebookViewModel::cutSelection,
                onCopySelection = notebookViewModel::copySelection,
                onPaste = notebookViewModel::paste,
                onDeleteSelection = notebookViewModel::deleteSelection,
                onFlipHorizontal = { notebookViewModel.flipSelection(SelectionFlipAxis.HORIZONTAL) },
                onFlipVertical = { notebookViewModel.flipSelection(SelectionFlipAxis.VERTICAL) },
                onSetRotationSnapEnabled = notebookViewModel::setRotationSnapEnabled,
                onClearSelection = notebookViewModel::cancelSelection,
            )
        },
    )
}

@Composable
private fun SettingsDestination(
    dependencies: WorkspaceDependencies,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel = remember(dependencies.settingsRepository, dependencies.backupCoordinator) {
        SettingsViewModel(
            repository = dependencies.settingsRepository,
            backupCoordinator = dependencies.backupCoordinator,
        )
    }
    val settings by viewModel.uiState.collectAsState()
    val backupState by viewModel.backupState.collectAsState()
    val createBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) {
            val output = runCatching { context.contentResolver.openOutputStream(uri) }.getOrNull()
            if (output == null) viewModel.reportStorageError("The selected backup file could not be opened")
            else viewModel.createBackup(output)
        }
    }
    val restoreBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val input = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
            if (input == null) viewModel.reportStorageError("The selected backup file could not be opened")
            else viewModel.restoreBackup(input)
        }
    }
    DisposableEffect(viewModel) {
        onDispose { viewModel.close() }
    }
    SettingsScreen(
        settings = settings,
        onBack = onBack,
        onSettingsChanged = { next -> viewModel.update { next } },
        onBackup = { createBackup.launch("putnam-workbook-backup.pwb.zip") },
        onRestore = { restoreBackup.launch(arrayOf("application/zip", "application/octet-stream")) },
        appVersion = dependencies.appVersion,
        contentVersion = dependencies.contentVersion,
        backupState = backupState,
    )
}

@Composable
private fun Placeholder(text: String, tag: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(text)
    }
}
