package com.putnamworkbook.offline

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.putnamworkbook.offline.data.content.ContentInitializer
import com.putnamworkbook.offline.data.content.ContentRepository
import com.putnamworkbook.offline.data.content.LibraryCollection
import com.putnamworkbook.offline.data.content.ProblemContent
import com.putnamworkbook.offline.data.content.ProblemSetSummary
import com.putnamworkbook.offline.data.content.ProblemSummary
import com.putnamworkbook.offline.data.content.TopicSummary
import com.putnamworkbook.offline.di.AppContainer
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.domain.model.ProblemSetKey
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PutnamAppStartupTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun blockedInitializerKeepsAVisibleLoadingSurface() {
        val pending = CompletableDeferred<ContentRepository>()
        showApp(ContentInitializer { pending.await() })

        composeRule.onNodeWithTag("content-startup-loading").assertIsDisplayed()
    }

    @Test
    fun successfulInitializerRunsOffMainAndShowsTheReadyApp() {
        val ranOnMain = AtomicBoolean(true)
        showApp(ContentInitializer {
            ranOnMain.set(Thread.currentThread() === Looper.getMainLooper().thread)
            EmptyContentRepository
        })

        composeRule.onNodeWithTag("home-screen").assertIsDisplayed()
        assertFalse("Content initialization must not run on the main thread", ranOnMain.get())
    }

    @Test
    fun malformedDatabaseFailureShowsTheRecoverySurface() {
        assertStartupFailure(IllegalStateException("content metadata is malformed"))
    }

    @Test
    fun missingDatabaseFailureShowsTheRecoverySurface() {
        assertStartupFailure(java.io.FileNotFoundException("databases/content.db"))
    }

    @Test
    fun unexpectedInitializerFailureShowsTheRecoverySurface() {
        assertStartupFailure(RuntimeException("unexpected initialization failure"))
    }

    private fun assertStartupFailure(failure: Exception) {
        showApp(ContentInitializer { throw failure })
        composeRule.onNodeWithTag("content-startup-error").assertIsDisplayed()
    }

    private fun showApp(initializer: ContentInitializer) {
        val container = AppContainer(composeRule.activity, initializer)
        composeRule.setContent { PutnamApp(container) }
    }
}

private object EmptyContentRepository : ContentRepository {
    override suspend fun contentVersion() = "test-v3"
    override suspend fun collections(): List<LibraryCollection> = emptyList()
    override suspend fun sets(collectionId: String): List<ProblemSetSummary> = emptyList()
    override suspend fun topics(): List<TopicSummary> = emptyList()
    override suspend fun problems(setKey: ProblemSetKey): List<ProblemSummary> = emptyList()
    override suspend fun problemsByTopic(topicId: String): List<ProblemSummary> = emptyList()
    override suspend fun problem(key: ProblemKey): ProblemContent? = null
}
