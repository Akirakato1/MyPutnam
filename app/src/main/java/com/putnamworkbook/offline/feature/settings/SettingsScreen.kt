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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
import com.putnamworkbook.offline.domain.model.BrushSpec
import com.putnamworkbook.offline.domain.model.Handedness
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
                SettingsSection("Handedness and palm guard") {
                    ChoiceRow {
                        Choice("Right handed", settings.handedness == Handedness.RIGHT, "settings-handedness-right") {
                            onSettingsChanged(settings.copy(handedness = Handedness.RIGHT))
                        }
                        Choice("Left handed", settings.handedness == Handedness.LEFT, "settings-handedness-left") {
                            onSettingsChanged(settings.copy(handedness = Handedness.LEFT))
                        }
                    }
                    ToggleRow("Block palm touches in the notebook", settings.palmGuard.enabled, "settings-palm-guard") {
                        onSettingsChanged(settings.copy(palmGuard = settings.palmGuard.copy(enabled = it)))
                    }
                    Text("Guard width ${percent(settings.palmGuard.widthFraction)}")
                    Slider(
                        value = settings.palmGuard.widthFraction,
                        onValueChange = { value ->
                            onSettingsChanged(settings.copy(palmGuard = settings.palmGuard.copy(widthFraction = value)))
                        },
                        valueRange = 0.1f..0.5f,
                        modifier = Modifier.testTag("settings-palm-width"),
                    )
                    Text("Guard height ${percent(settings.palmGuard.heightFraction)}")
                    Slider(
                        value = settings.palmGuard.heightFraction,
                        onValueChange = { value ->
                            onSettingsChanged(settings.copy(palmGuard = settings.palmGuard.copy(heightFraction = value)))
                        },
                        valueRange = 0.1f..0.8f,
                        modifier = Modifier.testTag("settings-palm-height"),
                    )
                }
            }
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
                    Text("Pen width ${"%.1f".format(settings.brush.width)}")
                    Slider(
                        value = settings.brush.width,
                        onValueChange = { width -> onSettingsChanged(settings.copy(brush = settings.brush.copy(width = width))) },
                        valueRange = 1f..12f,
                        modifier = Modifier.testTag("settings-brush-width"),
                    )
                    ChoiceRow {
                        BRUSH_COLORS.forEach { (label, color) ->
                            Choice(label, settings.brush.colorArgb == color, "settings-brush-${label.lowercase()}") {
                                onSettingsChanged(settings.copy(brush = BrushSpec(color, settings.brush.width)))
                            }
                        }
                    }
                    Text("These defaults apply only to new pages and new strokes.")
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
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onBackup, modifier = Modifier.testTag("settings-backup")) { Text("Create backup") }
                        OutlinedButton(onClick = onRestore, modifier = Modifier.testTag("settings-restore")) { Text("Restore backup") }
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

private fun percent(value: Float) = "${(value * 100).toInt()}%"

private fun AppTheme.label() = when (this) {
    AppTheme.SYSTEM -> "System"
    AppTheme.LIGHT -> "Light"
    AppTheme.DARK -> "Dark"
}

private val BRUSH_COLORS = listOf(
    "Ink" to 0xFF1F2937L,
    "Blue" to 0xFF1D4ED8L,
    "Red" to 0xFFB91C1CL,
)
