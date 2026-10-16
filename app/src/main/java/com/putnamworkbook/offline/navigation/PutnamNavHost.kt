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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner

data class WorkspaceDependencies(
    val settingsRepository: SettingsRepository,
    val notebookRepository: NotebookRepository,
    val notebookClipboard: NotebookClipboard,
    val backupCoordinator: BackupCoordinator,
    val exportCoordinator: ExportCoordinator,
    val appVersion: String,
    val contentVersion: String,
)

private class NotebookViewModelFactory(
    private val dependencies: WorkspaceDependencies,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(NotebookViewModel::class.java))
        @Suppress("UNCHECKED_CAST")
        return NotebookViewModel(
            dependencies.notebookRepository,
            dependencies.settingsRepository,
            clipboard = dependencies.notebookClipboard,
        ) as T
    }
}

@Composable
fun PutnamNavHost(
    contentRepository: ContentRepository,
    progressRepository: ProgressRepository,
    workspaceDependencies: WorkspaceDependencies? = null,
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
        startDestination = AppRoute.Home.path,
        modifier = modifier,
    ) {
        composable(AppRoute.HOME) {
            HomeScreen(
                state = homeState,
                onContinue = { key -> navController.navigate(AppRoute.Workspace(key).path) },
                onSelectProblem = { navController.navigate(AppRoute.Library.path) },
                onSettings = { navController.navigate(AppRoute.Settings.path) },
            )
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
