package com.putnamworkbook.offline.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.putnamworkbook.offline.domain.model.AppSettings
import com.putnamworkbook.offline.domain.model.AppTheme
import com.putnamworkbook.offline.domain.model.PaperStyle

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onSettingsChanged: (AppSettings) -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    appVersion: String,
    contentVersion: String,
    backupState: BackupUiState = BackupUiState.Idle,
    onToggleReinstallCandidate: (String) -> Unit = {},
    onConfirmReinstall: () -> Unit = {},
    onDismissReinstall: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().testTag("settings-screen")) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TextButton(onClick = onBack, modifier = Modifier.testTag("settings-back")) { Text("Back") }
            Text("Settings", style = MaterialTheme.typography.headlineMedium)
        }
        HorizontalDivider()
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("settings-list"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                SettingsSection("New notebook pages") {
                    ChoiceRow {
                        Choice("Square grid", settings.defaultPaperStyle == PaperStyle.SQUARE_GRID, "settings-paper-grid") {
                            onSettingsChanged(settings.copy(defaultPaperStyle = PaperStyle.SQUARE_GRID))
                        }
                        Choice("Blank", settings.defaultPaperStyle == PaperStyle.BLANK, "settings-paper-blank") {
                            onSettingsChanged(settings.copy(defaultPaperStyle = PaperStyle.BLANK))
                        }
                    }
                    Text("This default applies only to new notebook pages.")
                }
            }
            item {
                SettingsSection("Appearance and device") {
                    ChoiceRow {
                        AppTheme.entries.forEach { theme ->
                            Choice(theme.label(), settings.theme == theme, "settings-theme-${theme.name.lowercase()}") {
                                onSettingsChanged(settings.copy(theme = theme))
                            }
                        }
                    }
                    ToggleRow("Keep screen awake while working", settings.keepAwakeInWorkspace, "settings-keep-awake") {
                        onSettingsChanged(settings.copy(keepAwakeInWorkspace = it))
                    }
                }
            }
            item {
                SettingsSection("Backup and restore") {
                    val working = backupState is BackupUiState.Working
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = onBackup,
                            enabled = !working,
                            modifier = Modifier.testTag("settings-backup"),
                        ) { Text("Create backup") }
                        OutlinedButton(
                            onClick = onRestore,
                            enabled = !working,
                            modifier = Modifier.testTag("settings-restore"),
                        ) { Text("Restore backup") }
                    }
                    BackupStatus(backupState)
                    if (backupState is BackupUiState.ReinstallReview) {
                        ReinstallReview(
                            backupState,
                            onToggleReinstallCandidate,
                            onConfirmReinstall,
                            onDismissReinstall,
                        )
                    }
                }
            }
            item {
                SettingsSection("Attribution") {
                    Text("Mathematical Association of America", Modifier.testTag("settings-attribution-maa"))
                    Text("Kiran Kedlaya's Putnam archive", Modifier.testTag("settings-attribution-kedlaya"))
                    Text("rpxgit/The-Putnam-Archive mirror", Modifier.testTag("settings-attribution-mirror"))
                    Text("Personal, noncommercial study build", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                SettingsSection("Versions") {
                    Text("App version $appVersion", Modifier.testTag("settings-app-version"))
                    Text("Content version $contentVersion", Modifier.testTag("settings-content-version"))
                }
            }
        }
    }
}

@Composable
private fun BackupStatus(state: BackupUiState) {
    val text = when (state) {
        BackupUiState.Idle -> "Backups include progress, settings, and every workbook page."
        is BackupUiState.Working -> if (state.action == BackupAction.CREATE) {
            "Creating and verifying backup…"
        } else {
            "Validating backup before restore…"
        }
        is BackupUiState.Complete -> state.message
        is BackupUiState.Error -> state.message
        is BackupUiState.ReinstallReview -> "Backup restored. Review optional library downloads."
    }
    Text(
        text = text,
        color = if (state is BackupUiState.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("settings-backup-status"),
    )
}

@Composable
private fun ReinstallReview(
    state: BackupUiState.ReinstallReview,
    onToggle: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().testTag("settings-reinstall-review"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (state.restoredBundledCount > 0) {
            Text("${state.restoredBundledCount} bundled item(s) restored offline.")
        }
        state.candidates.forEach { key ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = key in state.selected,
                    onCheckedChange = { onToggle(key) },
                    modifier = Modifier.testTag("settings-reinstall-$key"),
                )
                Text(key)
            }
        }
        if (!state.isOnline) {
            Text(
                "Connect to the internet to download the selected library items.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("settings-reinstall-offline"),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onConfirm,
                enabled = state.isOnline && state.selected.isNotEmpty(),
                modifier = Modifier.testTag("settings-reinstall-confirm"),
            ) { Text("Download selected") }
            OutlinedButton(onClick = onDismiss, modifier = Modifier.testTag("settings-reinstall-dismiss")) {
                Text("Not now")
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        content()
    }
}

@Composable
private fun ChoiceRow(content: @Composable RowScope.() -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        content()
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = Modifier.testTag(tag),
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, tag: String, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.testTag(tag))
    }
}

private fun AppTheme.label() = when (this) {
    AppTheme.SYSTEM -> "System"
    AppTheme.LIGHT -> "Light"
    AppTheme.DARK -> "Dark"
}
