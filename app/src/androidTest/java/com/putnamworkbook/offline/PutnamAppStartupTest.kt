package com.putnamworkbook.offline

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.putnamworkbook.offline.data.content.ContentRepository
import com.putnamworkbook.offline.data.content.LibraryCollection
import com.putnamworkbook.offline.data.content.ProblemContent
import com.putnamworkbook.offline.data.content.ProblemSetSummary
import com.putnamworkbook.offline.data.content.ProblemSummary
import com.putnamworkbook.offline.data.content.TopicSummary
import com.putnamworkbook.offline.data.library.catalog.CatalogClient
import com.putnamworkbook.offline.data.library.catalog.CatalogState
import com.putnamworkbook.offline.data.library.catalog.CatalogStore
import com.putnamworkbook.offline.data.library.catalog.CatalogValidators
import com.putnamworkbook.offline.di.AppContainer
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.domain.model.ProblemSetKey
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        showApp { pending.await() }

        composeRule.onNodeWithTag("content-startup-loading").assertIsDisplayed()
    }

    @Test
    fun successfulInitializerRunsOffMainAndShowsTheReadyApp() {
        val ranOnMain = AtomicBoolean(true)
        showApp {
            ranOnMain.set(Thread.currentThread() === Looper.getMainLooper().thread)
            EmptyContentRepository
        }

        composeRule.onNodeWithTag("home-screen").assertIsDisplayed()
        assertFalse("Content initialization must not run on the main thread", ranOnMain.get())
    }

    @Test
    fun blockedCatalogRefreshDoesNotDelayHome() {
        val refreshStarted = CompletableDeferred<Unit>()
        val neverCompletes = CompletableDeferred<com.putnamworkbook.offline.data.library.catalog.CatalogFetchResult>()
        val container = AppContainer(
            context = composeRule.activity,
            initializer = { EmptyContentRepository },
            catalogStoreOverride = ReadyCatalogStore(),
            catalogClientOverride = CatalogClient {
                refreshStarted.complete(Unit)
                neverCompletes.await()
            },
        )

        composeRule.setContent { PutnamApp(container) }

        composeRule.onNodeWithTag("home-screen").assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 5_000) { refreshStarted.isCompleted }
        assertTrue(refreshStarted.isCompleted)
    }

    @Test
    fun malformedDatabaseFailureShowsTheRecoverySurface() {
        assertStartupFailure(IllegalStateException("content metadata is malformed"))
    }

    @Test
    fun missingDatabaseFailureShowsTheRecoverySurface() {
        assertStartupFailure(java.io.FileNotFoundException("content-library/catalog.json"))
    }

    @Test
    fun unexpectedInitializerFailureShowsTheRecoverySurface() {
        assertStartupFailure(RuntimeException("unexpected initialization failure"))
    }

    private fun assertStartupFailure(failure: Exception) {
        showApp { throw failure }
        composeRule.onNodeWithTag("content-startup-error").assertIsDisplayed()
    }

    private fun showApp(initializer: suspend () -> ContentRepository) {
        val container = AppContainer(composeRule.activity, initializer)
        composeRule.setContent { PutnamApp(container) }
    }
}

private class ReadyCatalogStore : CatalogStore {
    override val state = MutableStateFlow(CatalogState())
    override suspend fun initialize() = Unit
    override suspend fun commitRemote(bytes: ByteArray, validators: CatalogValidators, refreshedAt: Long) = Unit
    override suspend fun recordNotModified(validators: CatalogValidators, refreshedAt: Long) = Unit
    override suspend fun recordFailure(message: String, attemptedAt: Long) = Unit
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
