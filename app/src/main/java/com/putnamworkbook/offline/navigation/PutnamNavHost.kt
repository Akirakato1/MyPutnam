package com.putnamworkbook.offline.navigation

import androidx.activity.compose.rememberLauncherForActivityResult
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
import com.putnamworkbook.offline.backup.BackupCoordinator
import com.putnamworkbook.offline.data.content.ContentRepository
import com.putnamworkbook.offline.data.user.ProgressRepository
import com.putnamworkbook.offline.data.user.SettingsRepository
import com.putnamworkbook.offline.content.ui.OriginalPdfRepository
import com.putnamworkbook.offline.content.ui.OriginalPdfViewModel
import com.putnamworkbook.offline.domain.model.AppSettings
import com.putnamworkbook.offline.domain.model.ProblemKey
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
import com.putnamworkbook.offline.notebook.ui.NotebookPane
import com.putnamworkbook.offline.notebook.ui.NotebookViewModel
import com.putnamworkbook.offline.notebook.ui.toPalmGuardState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

data class WorkspaceDependencies(
    val settingsRepository: SettingsRepository,
    val notebookRepository: NotebookRepository,
    val originalPdfRepository: OriginalPdfRepository,
    val backupCoordinator: BackupCoordinator,
    val exportCoordinator: ExportCoordinator,
    val appVersion: String,
    val contentVersion: String,
)

@Composable
fun PutnamNavHost(
    contentRepository: ContentRepository,
    progressRepository: ProgressRepository,
    workspaceDependencies: WorkspaceDependencies? = null,
    onWorkspaceActiveChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val homeViewModel = remember(progressRepository) { HomeViewModel(progressRepository) }
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
                onContinue = { key -> navController.navigate(AppRoute.Workspace(key.year, key.code).path) },
                onSelectProblem = { navController.navigate(AppRoute.ArchiveYears.path) },
                onSettings = { navController.navigate(AppRoute.Settings.path) },
            )
        }
        composable(AppRoute.ARCHIVE_YEARS) {
            ArchiveScreen(
                state = archiveState,
                onBack = { navController.popBackStack() },
                onSelectYear = { year -> navController.navigate(AppRoute.YearProblems(year).path) },
            )
        }
        composable(
            route = AppRoute.YEAR_PROBLEMS_PATTERN,
            arguments = listOf(navArgument("year") { type = NavType.IntType }),
        ) { entry ->
            val year = requireNotNull(entry.arguments?.getInt("year"))
            LaunchedEffect(year) { archiveViewModel.selectYear(year) }
            ProblemListScreen(
                year = year,
                state = archiveState,
                onBack = { navController.popBackStack() },
                onSelectProblem = { key -> navController.navigate(AppRoute.Workspace(key.year, key.code).path) },
            )
        }
        composable(
            route = AppRoute.WORKSPACE_PATTERN,
            arguments = listOf(
                navArgument("year") { type = NavType.IntType },
                navArgument("code") { type = NavType.StringType },
            ),
        ) { entry ->
            val year = requireNotNull(entry.arguments?.getInt("year"))
            val code = requireNotNull(entry.arguments?.getString("code"))
            if (workspaceDependencies == null) {
                Placeholder(
                    text = "Workspace $year $code",
                    tag = "workspace-placeholder-$year-$code",
                )
            } else {
                WorkspaceDestination(
                    key = ProblemKey(year, code),
                    contentRepository = contentRepository,
                    progressRepository = progressRepository,
                    dependencies = workspaceDependencies,
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
    onWorkspaceActiveChanged: (Boolean) -> Unit,
    onHome: () -> Unit,
) {
    val workspaceViewModel = remember(key, contentRepository, progressRepository) {
        WorkspaceViewModel(contentRepository, progressRepository)
    }
    val notebookViewModel = remember(key, dependencies.notebookRepository, dependencies.settingsRepository) {
        NotebookViewModel(dependencies.notebookRepository, dependencies.settingsRepository)
    }
    val pdfViewModel = remember(key, dependencies.originalPdfRepository) {
        OriginalPdfViewModel(dependencies.originalPdfRepository)
    }
    val scope = rememberCoroutineScope()
    val workspaceState by workspaceViewModel.uiState.collectAsState()
    val notebookState by notebookViewModel.uiState.collectAsState()
    val pdfState by pdfViewModel.uiState.collectAsState()
    val settings by dependencies.settingsRepository.observe().collectAsState(initial = AppSettings())
    var notebookOpened by remember(key) { mutableStateOf(false) }
    var exportMessage by remember(key) { mutableStateOf<String?>(null) }
    var exportInProgress by remember(key) { mutableStateOf(false) }
    var hasPendingExport by rememberSaveable(key.year, key.code) { mutableStateOf(false) }
    var pendingIncludeHints by rememberSaveable(key.year, key.code) { mutableStateOf(true) }
    var pendingIncludeSolution by rememberSaveable(key.year, key.code) { mutableStateOf(true) }
    val context = LocalContext.current
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

    DisposableEffect(workspaceViewModel, notebookViewModel, pdfViewModel) {
        onWorkspaceActiveChanged(true)
        onDispose {
            onWorkspaceActiveChanged(false)
            workspaceViewModel.close()
            notebookViewModel.close()
            pdfViewModel.close()
        }
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
    LaunchedEffect(workspaceState.selectedReferenceTab, workspaceState.content?.originalPdf) {
        if (workspaceState.selectedReferenceTab == com.putnamworkbook.offline.domain.model.ReferenceTab.ORIGINAL_PAPER) {
            workspaceState.content?.originalPdf?.let { original ->
                pdfViewModel.open(original.assetPath, original.sha256)
                pdfViewModel.awaitIdle()
                if (original.pageIndex > 0) {
                    pdfViewModel.selectPage(original.pageIndex)
                }
            }
        }
    }

    val palmGuard = settings.toPalmGuardState()

    WorkspaceScreen(
        state = workspaceState,
        onHome = {
            scope.launch {
                notebookViewModel.awaitIdle()
                notebookViewModel.uiState.value.let { latestNotebook ->
                    latestNotebook.document?.let { document ->
                        workspaceViewModel.updateNotebookState(document.pages.size, latestNotebook.activePageIndex)
                    }
                    workspaceViewModel.awaitIdle()
                }
                onHome()
            }
        },
        onSetCompleted = workspaceViewModel::setCompleted,
        onExportPdf = { options ->
            pendingIncludeHints = options.includeRevealedHints
            pendingIncludeSolution = options.includeRevealedSolution
            hasPendingExport = true
            exportPdf.launch("putnam-${key.year}-${key.code}-workbook.pdf")
        },
        exportMessage = exportMessage,
        exportInProgress = exportInProgress,
        referenceContent = {
            ReferencePane(
                state = workspaceState,
                pdfState = pdfState,
                onSelectTab = workspaceViewModel::selectReferenceTab,
                onRevealNextStage = workspaceViewModel::revealNextStage,
                onSelectHint = workspaceViewModel::selectHint,
                onPreviousPdfPage = { pdfViewModel.selectPage(pdfState.currentPageIndex - 1) },
                onNextPdfPage = { pdfViewModel.selectPage(pdfState.currentPageIndex + 1) },
                onIntegrityError = workspaceViewModel::reportIntegrityError,
            )
        },
        notebookContent = {
            NotebookPane(
                state = notebookState,
                brush = settings.brush,
                palmGuard = palmGuard,
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
                onReorderPage = notebookViewModel::reorderPage,
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
