package com.putnamworkbook.offline.feature.settings

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.putnamworkbook.offline.data.user.RoomSettingsRepository
import com.putnamworkbook.offline.data.user.UserDatabase
import com.putnamworkbook.offline.domain.model.AppSettings
import com.putnamworkbook.offline.domain.model.AppTheme
import com.putnamworkbook.offline.domain.model.Handedness
import com.putnamworkbook.offline.domain.model.PaperStyle
import com.putnamworkbook.offline.updateKeepScreenAwake
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun controlsAreAccessibleAndAttributionNamesEverySource() {
        var settings by mutableStateOf(AppSettings())
        var backupRequests = 0
        var restoreRequests = 0
        composeRule.setContent {
            MaterialTheme {
                SettingsScreen(
                    settings = settings,
                    onBack = {},
                    onSettingsChanged = { settings = it },
                    onBackup = { backupRequests += 1 },
                    onRestore = { restoreRequests += 1 },
                    appVersion = "0.1.0",
                    contentVersion = "2025 schema 1",
                )
            }
        }

        composeRule.onNodeWithTag("settings-palm-guard").assertDoesNotExist()
        composeRule.onNodeWithTag("settings-palm-width").assertDoesNotExist()
        composeRule.onNodeWithTag("settings-palm-height").assertDoesNotExist()
        composeRule.onNodeWithTag("settings-paper-blank").performClick().assertIsSelected()
        composeRule.onNodeWithTag("settings-theme-dark").performClick().assertIsSelected()
        composeRule.onNodeWithTag("settings-keep-awake").performClick()
        composeRule.onNodeWithTag("settings-list")
            .performScrollToNode(hasTestTag("settings-backup"))
        composeRule.onNodeWithTag("settings-backup").performClick()
        composeRule.onNodeWithTag("settings-restore").performClick()
        assertEquals(1, backupRequests)
        assertEquals(1, restoreRequests)
        composeRule.onNodeWithTag("settings-list")
            .performScrollToNode(hasTestTag("settings-attribution-maa"))
        composeRule.onNodeWithTag("settings-attribution-maa").assertTextEquals("Mathematical Association of America")
        composeRule.onNodeWithTag("settings-attribution-kedlaya").assertTextEquals("Kiran Kedlaya's Putnam archive")
        composeRule.onNodeWithTag("settings-attribution-mirror").assertTextEquals("rpxgit/The-Putnam-Archive mirror")
        composeRule.onNodeWithTag("settings-list")
            .performScrollToNode(hasTestTag("settings-content-version"))
        composeRule.onNodeWithTag("settings-app-version").assertTextEquals("App version 0.1.0")
        composeRule.onNodeWithTag("settings-content-version").assertTextEquals("Content version 2025 schema 1")

        assertEquals(PaperStyle.BLANK, settings.defaultPaperStyle)
        assertEquals(AppTheme.DARK, settings.theme)
        assertFalse(settings.keepAwakeInWorkspace)
    }

    @Test
    fun repositorySettingsSurviveViewModelRestart() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, UserDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repository = RoomSettingsRepository(database)
        var viewModel = SettingsViewModel(repository, CoroutineScope(Dispatchers.Default))

        viewModel.update { it.copy(handedness = Handedness.LEFT, theme = AppTheme.DARK) }
        viewModel.awaitIdle()
        viewModel.close()
        viewModel = SettingsViewModel(repository, CoroutineScope(Dispatchers.Default))
        viewModel.awaitLoaded()

        assertEquals(Handedness.LEFT, viewModel.uiState.value.handedness)
        assertEquals(AppTheme.DARK, viewModel.uiState.value.theme)
        viewModel.close()
        database.close()
    }

    @Test
    fun keepAwakeFlagIsSetOnlyForAnActiveWorkspace() {
        val keepAwake = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        composeRule.activityRule.scenario.onActivity { activity ->
            updateKeepScreenAwake(activity.window, workspaceActive = false, enabled = true)
            assertFalse(activity.window.attributes.flags and keepAwake != 0)
            updateKeepScreenAwake(activity.window, workspaceActive = true, enabled = true)
            assertTrue(activity.window.attributes.flags and keepAwake != 0)
            updateKeepScreenAwake(activity.window, workspaceActive = true, enabled = false)
            assertFalse(activity.window.attributes.flags and keepAwake != 0)
        }
    }
}
