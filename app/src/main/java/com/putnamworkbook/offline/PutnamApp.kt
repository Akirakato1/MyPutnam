package com.putnamworkbook.offline

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.putnamworkbook.offline.di.AppContainer
import com.putnamworkbook.offline.domain.model.AppSettings
import com.putnamworkbook.offline.domain.model.AppTheme
import com.putnamworkbook.offline.navigation.PutnamNavHost
import com.putnamworkbook.offline.navigation.WorkspaceDependencies

@Composable
fun PutnamApp(
    container: AppContainer,
    onKeepScreenAwakeChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val appVersion = remember(context) {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    }
    var contentVersion by remember { mutableStateOf("loading") }
    val exportCoordinator = remember(container, context) { container.createExportCoordinator(context) }
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
    LaunchedEffect(container.contentRepository) {
        contentVersion = container.contentRepository.contentVersion()
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
            PutnamNavHost(
                contentRepository = container.contentRepository,
                progressRepository = container.progressRepository,
                workspaceDependencies = WorkspaceDependencies(
                    settingsRepository = container.settingsRepository,
                    notebookRepository = container.notebookRepository,
                    notebookClipboard = container.notebookClipboard,
                    originalPdfRepository = container.originalPdfRepository,
                    backupCoordinator = container.backupCoordinator,
                    exportCoordinator = exportCoordinator,
                    appVersion = appVersion,
                    contentVersion = contentVersion,
                ),
                onWorkspaceActiveChanged = { workspaceActive = it },
            )
        }
    }
}
