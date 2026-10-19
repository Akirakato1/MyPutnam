package com.putnamworkbook.offline

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.putnamworkbook.offline.di.AppContainer
import com.putnamworkbook.offline.di.initializeContent
import com.putnamworkbook.offline.data.content.ContentRepository
import com.putnamworkbook.offline.domain.model.AppSettings
import com.putnamworkbook.offline.domain.model.AppTheme
import com.putnamworkbook.offline.navigation.PutnamNavHost
import com.putnamworkbook.offline.navigation.WorkspaceDependencies
import com.putnamworkbook.offline.navigation.LibraryDependencies
import com.putnamworkbook.offline.content.ui.ContentResourceResolverProvider

@Composable
fun PutnamApp(
    container: AppContainer,
    onKeepScreenAwakeChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val appVersion = remember(context) {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    }
    var contentState by remember(container) { mutableStateOf<ContentStartupState>(ContentStartupState.Loading) }
    val settings by container.settingsRepository.observe().collectAsState(initial = AppSettings())
    var workspaceActive by remember { mutableStateOf(false) }
    val useDarkTheme = when (settings.theme) {
        AppTheme.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
        AppTheme.LIGHT -> false
        AppTheme.DARK -> true
    }
    LaunchedEffect(workspaceActive, settings.keepAwakeInWorkspace) {
        onKeepScreenAwakeChanged(workspaceActive && settings.keepAwakeInWorkspace)
    }
    LaunchedEffect(container) {
        contentState = try {
            val repository = container.initializeContent()
            ContentStartupState.Ready(repository, repository.contentVersion())
        } catch (error: Exception) {
            ContentStartupState.Error(error)
        }
    }
    DisposableEffect(Unit) {
        onDispose { onKeepScreenAwakeChanged(false) }
    }

    MaterialTheme(colorScheme = if (useDarkTheme) darkColorScheme() else lightColorScheme()) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .testTag("app-root"),
            color = MaterialTheme.colorScheme.background,
        ) {
            when (val state = contentState) {
                ContentStartupState.Loading -> Box(
                    modifier = Modifier.fillMaxSize().testTag("content-startup-loading"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Preparing offline library…")
                }

                is ContentStartupState.Error -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                        .testTag("content-startup-error"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Offline library could not be opened. Reinstall this development build and try again.")
                }

                is ContentStartupState.Ready -> {
                    LaunchedEffect(container) {
                        container.startCatalogRefresh()
                    }
                    val exportCoordinator = remember(container, context, state.repository) {
                        container.createExportCoordinator(context, state.repository)
                    }
                    CompositionLocalProvider(ContentResourceResolverProvider provides container.contentResourceResolver) {
                        PutnamNavHost(
                            contentRepository = state.repository,
                            progressRepository = container.progressRepository,
                            workspaceDependencies = WorkspaceDependencies(
                                settingsRepository = container.settingsRepository,
                                notebookRepository = container.notebookRepository,
                                notebookClipboard = container.notebookClipboard,
                                backupCoordinator = container.backupCoordinator,
                                exportCoordinator = exportCoordinator,
                                appVersion = appVersion,
                                contentVersion = state.contentVersion,
                                freeWorkspaceRepository = container.freeWorkspaceRepository,
                            ),
                            libraryDependencies = LibraryDependencies(
                                catalogStore = container.catalogStore,
                                installedContentIndex = container.installedContentIndex,
                                packageInstaller = container.packageInstaller,
                                networkStatusMonitor = container.networkStatusMonitor,
                                currentAppVersionCode = container.currentVersionCode,
                                refreshCatalog = container::refreshCatalog,
                            ),
                            onWorkspaceActiveChanged = { workspaceActive = it },
                        )
                    }
                }
            }
        }
    }
}

private sealed interface ContentStartupState {
    data object Loading : ContentStartupState
    data class Ready(
        val repository: ContentRepository,
        val contentVersion: String,
    ) : ContentStartupState
    data class Error(val cause: Throwable) : ContentStartupState
}
