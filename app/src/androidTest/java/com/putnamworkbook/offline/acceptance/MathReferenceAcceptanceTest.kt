package com.putnamworkbook.offline.acceptance

import android.net.Uri
import android.webkit.WebResourceResponse
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.putnamworkbook.offline.MainActivity
import com.putnamworkbook.offline.PutnamApplication
import com.putnamworkbook.offline.content.ui.ContentResourceResolver
import com.putnamworkbook.offline.content.ui.ContentResourceResolverProvider
import com.putnamworkbook.offline.data.content.*
import com.putnamworkbook.offline.data.user.ProblemProgress
import com.putnamworkbook.offline.data.user.ProgressRepository
import com.putnamworkbook.offline.domain.model.HintStage
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.domain.model.ProblemSetKey
import com.putnamworkbook.offline.domain.model.ProblemStatus
import com.putnamworkbook.offline.domain.model.ReferenceKey
import com.putnamworkbook.offline.domain.model.ReferenceTab
import com.putnamworkbook.offline.freeworkspace.FileFreeWorkspaceRepository
import com.putnamworkbook.offline.navigation.PutnamNavHost
import com.putnamworkbook.offline.navigation.WorkspaceDependencies
import com.putnamworkbook.offline.notebook.edit.NotebookClipboard
import com.putnamworkbook.offline.notebook.storage.FileNotebookRepository
import com.putnamworkbook.offline.notebook.ui.COPY_SELECTION_TEST_TAG
import com.putnamworkbook.offline.notebook.ui.NOTEBOOK_CANVAS_TEST_TAG
import com.putnamworkbook.offline.notebook.ui.PREVIOUS_PAGE_TEST_TAG
import com.putnamworkbook.offline.notebook.ui.RenderedStrokeCountKey
import com.putnamworkbook.offline.notebook.ui.SELECTION_OVERLAY_TEST_TAG
import com.putnamworkbook.offline.notebook.ui.TOOL_MODE_TOGGLE_TEST_TAG
import java.io.ByteArrayInputStream
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MathReferenceAcceptanceTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun offlineReferenceBrowsingPreservesProblemTabNotebookPageInkSelectionAndToolbar() {
        val app = composeRule.activity.application as PutnamApplication
        val key = ProblemKey("acceptance", "${System.currentTimeMillis()}", "P1")
        val content = AcceptanceContentRepository(key)
        val progress = AcceptanceProgressRepository(key)
        val reference = AcceptanceReferenceRepository()
        val notebookRoot = Files.createTempDirectory(composeRule.activity.cacheDir.toPath(), "reference-acceptance-")
        val notebookRepository = FileNotebookRepository(notebookRoot)
        val freeWorkspaceRepository = FileFreeWorkspaceRepository(notebookRoot, notebookRepository)
        val dependencies = WorkspaceDependencies(
            settingsRepository = app.container.settingsRepository,
            notebookRepository = notebookRepository,
            notebookClipboard = NotebookClipboard(),
            backupCoordinator = app.container.backupCoordinator,
            exportCoordinator = app.container.createExportCoordinator(composeRule.activity, content),
            appVersion = "acceptance",
            contentVersion = "acceptance",
            freeWorkspaceRepository = freeWorkspaceRepository,
        )

        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(ContentResourceResolverProvider provides AcceptanceReferenceResolver) {
                    PutnamNavHost(
                        contentRepository = content,
                        progressRepository = progress,
                        referenceRepository = reference,
                        workspaceDependencies = dependencies,
                    )
                }
            }
        }

        browseReferenceFromHomeAndReturn()
        openProblem(key)
        drawSwitchPageAndSelectInk()

        val notebookBoundsBefore = composeRule.onNodeWithTag("workspace-notebook-pane")
            .fetchSemanticsNode().boundsInRoot
        val strokesBefore = waitForStoredStrokes(notebookRepository, key, 1)
        composeRule.onNodeWithTag("reference-tab-help").performClick()
        waitForTag("help-hint-1")
        composeRule.onNodeWithTag("reference-document-body").performTouchInput { swipeUp() }

        composeRule.onNodeWithTag("workspace-math-reference").performScrollTo().performClick()
        waitForTag("math-reference-browser")
        composeRule.onNodeWithTag("math-reference-search").performTextInput("totient")
        waitForTag("math-reference-articles")
        composeRule.onNodeWithTag("reference-article-math-reference/number-theory/euler-totient")
            .performClick()
        waitForTag("reference-article")

        assertEquals(
            notebookBoundsBefore,
            composeRule.onNodeWithTag("workspace-notebook-pane").fetchSemanticsNode().boundsInRoot,
        )
        assertNotebookEditingState()

        repeat(3) { pressBack(); composeRule.waitForIdle() }
        composeRule.onNodeWithTag("math-reference-browser").assertDoesNotExist()
        composeRule.onNodeWithTag("help-hint-1").assertIsDisplayed()
        assertNotebookEditingState()

        val persistedProgress = runBlocking { progress.observe(key).first() }
        assertEquals(ReferenceTab.HELP, persistedProgress.activeReferenceTab)
        assertEquals(1, persistedProgress.revealedHintCount)
        assertEquals(strokesBefore, waitForStoredStrokes(notebookRepository, key, 1))
        composeRule.onNodeWithTag("workspace-home").performClick()
        waitForTag("home-screen")
        assertTrue(notebookRoot.toFile().deleteRecursively())
    }

    private fun browseReferenceFromHomeAndReturn() {
        waitForTag("home-screen")
        composeRule.onNodeWithTag("home-math-reference").performClick()
        waitForTag("math-reference-browser")
        composeRule.onNodeWithTag("math-reference-search").performTextInput("totient")
        waitForTag("math-reference-articles")
        composeRule.onNodeWithTag("reference-article-math-reference/number-theory/euler-totient")
            .performClick()
        waitForTag("reference-article")
        repeat(3) { pressBack(); composeRule.waitForIdle() }
        waitForTag("home-screen")
    }

    private fun openProblem(key: ProblemKey) {
        composeRule.onNodeWithTag("home-select-problem").performClick()
        waitForTag("library-set-${key.setKey.canonical}")
        composeRule.onNodeWithTag("library-set-${key.setKey.canonical}").performClick()
        waitForTag("library-problem-${key.canonical}")
        composeRule.onNodeWithTag("library-problem-${key.canonical}").performClick()
        waitForTag("workspace-screen")
        waitForTag(NOTEBOOK_CANVAS_TEST_TAG)
    }

    private fun drawSwitchPageAndSelectInk() {
        val canvas = composeRule.onNodeWithTag(NOTEBOOK_CANVAS_TEST_TAG)
        val bounds = canvas.fetchSemanticsNode().boundsInRoot
        canvas.performTouchInput {
            swipe(
                Offset(bounds.width * 0.2f, bounds.height * 0.25f),
                Offset(bounds.width * 0.6f, bounds.height * 0.55f),
                durationMillis = 450,
            )
        }
        waitForRenderedStrokeCount(1)
        composeRule.onNodeWithContentDescription("Add page").performClick()
        waitForText("Page 2 of 2")
        composeRule.onNodeWithTag(PREVIOUS_PAGE_TEST_TAG).performClick()
        waitForText("Page 1 of 2")
        waitForRenderedStrokeCount(1)
        composeRule.onNodeWithTag(TOOL_MODE_TOGGLE_TEST_TAG).performClick()
        val overlay = composeRule.onNodeWithTag(SELECTION_OVERLAY_TEST_TAG)
        val overlayBounds = overlay.fetchSemanticsNode().boundsInRoot
        overlay.performTouchInput {
            swipe(
                Offset(overlayBounds.width * 0.05f, overlayBounds.height * 0.05f),
                Offset(overlayBounds.width * 0.85f, overlayBounds.height * 0.85f),
                durationMillis = 450,
            )
        }
        composeRule.onNodeWithTag(COPY_SELECTION_TEST_TAG).assertIsEnabled()
    }

    private fun assertNotebookEditingState() {
        waitForText("Page 1 of 2")
        waitForRenderedStrokeCount(1)
        composeRule.onNodeWithTag(TOOL_MODE_TOGGLE_TEST_TAG)
            .assertContentDescriptionEquals("Select mode")
        composeRule.onNodeWithTag(COPY_SELECTION_TEST_TAG).assertIsEnabled()
    }

    private fun waitForStoredStrokes(
        repository: FileNotebookRepository,
        key: ProblemKey,
        expected: Int,
    ): List<com.putnamworkbook.offline.notebook.model.StrokeRecord> {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            runBlocking {
                val notebook = repository.loadExisting(key) ?: return@runBlocking false
                repository.loadStrokes(key, notebook.pages.first().id).size == expected
            }
        }
        return runBlocking {
            val notebook = requireNotNull(repository.loadExisting(key))
            repository.loadStrokes(key, notebook.pages.first().id)
        }
    }

    private fun waitForRenderedStrokeCount(expected: Int) {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onNodeWithTag(NOTEBOOK_CANVAS_TEST_TAG)
                .fetchSemanticsNode().config[RenderedStrokeCountKey] == expected
        }
    }

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }
}

private class AcceptanceContentRepository(private val key: ProblemKey) : ContentRepository {
    private val summary = ProblemSummary(key, "P1", 1, "Acceptance Problems", "Reference stability", null)
    override suspend fun collections() = listOf(LibraryCollection(key.collectionId, "Acceptance Problems", "test", 1))
    override suspend fun sets(collectionId: String) = listOf(ProblemSetSummary(key.setKey, "Reference stability", 1, null))
    override suspend fun topics() = emptyList<TopicSummary>()
    override suspend fun problems(setKey: ProblemSetKey) = listOf(summary)
    override suspend fun problemsByTopic(topicId: String) = emptyList<ProblemSummary>()
    override suspend fun problem(key: ProblemKey) = ProblemContent(
        summary = summary,
        statementAssetPath = "content/putnam/2025/problems/A1/statement.html",
        statementSha256 = "0".repeat(64),
        hints = (1..4).map { index ->
            HintContent(index, HintStage.entries[index - 1], "Hint $index", "content/putnam/2025/problems/A1/hint-$index.html", "$index".repeat(64))
        },
        prerequisites = listOf(ContentDocument(1, "Prerequisite", "content/putnam/2025/problems/A1/prerequisite-1.html", "a".repeat(64))),
        solutionAssetPath = "content/putnam/2025/problems/A1/solution.html",
        solutionSha256 = "b".repeat(64),
        attribution = emptyList(),
    )
}

private class AcceptanceProgressRepository(key: ProblemKey) : ProgressRepository {
    private val rows = MutableStateFlow(
        mapOf(key to ProblemProgress.untouched(key).copy(
            status = ProblemStatus.IN_PROGRESS,
            revealedHintCount = 1,
            lastOpenedAt = 1L,
        )),
    )

    override fun observe(key: ProblemKey): Flow<ProblemProgress> = rows.map { it[key] ?: ProblemProgress.untouched(key) }
    override fun observeAll(): Flow<Map<ProblemKey, ProblemProgress>> = rows
    override fun observeRecent(): Flow<ProblemProgress?> = rows.map { values -> values.values.maxByOrNull { it.lastOpenedAt ?: 0L } }
    override suspend fun markOpened(key: ProblemKey) = mutate(key) { it.copy(status = ProblemStatus.IN_PROGRESS) }
    override suspend fun revealNextHint(key: ProblemKey): Int {
        var count = 0
        mutate(key) { current -> current.copy(revealedHintCount = (current.revealedHintCount + 1).coerceAtMost(4).also { count = it }) }
        return count
    }
    override suspend fun revealSolution(key: ProblemKey) = mutate(key) { it.copy(solutionRevealed = true) }
    override suspend fun markCompleted(key: ProblemKey, completed: Boolean) = mutate(key) {
        it.copy(status = if (completed) ProblemStatus.COMPLETED else ProblemStatus.IN_PROGRESS)
    }
    override suspend fun setNotebookState(key: ProblemKey, pageCount: Int, pageIndex: Int, referenceTab: ReferenceTab) = mutate(key) {
        it.copy(notebookPageCount = pageCount, activePageIndex = pageIndex, activeReferenceTab = referenceTab)
    }
    private fun mutate(key: ProblemKey, transform: (ProblemProgress) -> ProblemProgress) {
        val current = rows.value[key] ?: ProblemProgress.untouched(key)
        rows.value = rows.value + (key to transform(current).copy(lastOpenedAt = System.nanoTime()))
    }
}

private class AcceptanceReferenceRepository : ReferenceRepository {
    private val key = ReferenceKey("number-theory", "euler-totient")
    private val summary = ReferenceArticleSummary(key, "Euler's totient function", listOf("phi"), listOf("totient", "coprime"), "Number Theory", 1, 1)
    override fun observeCategories(): Flow<List<ReferenceCategorySummary>> = MutableStateFlow(listOf(ReferenceCategorySummary("number-theory", "Number Theory", 1)))
    override fun observeInstalledArticles(): Flow<List<ReferenceArticleSummary>> = MutableStateFlow(listOf(summary))
    override suspend fun article(key: ReferenceKey): ReferenceArticleContent? = if (key == this.key) ReferenceArticleContent(
        summary = summary,
        document = ContentResourceRef(key.canonical, "a".repeat(64), "documents/article.fragment.html", "b".repeat(64)),
        relatedReferences = emptyList(),
        relatedProblems = emptyList(),
        attribution = listOf(Attribution("reference", "Acceptance fixture", "https://example.invalid")),
    ) else null
}

private object AcceptanceReferenceResolver : ContentResourceResolver {
    private const val HTML = "<!doctype html><meta charset=\"utf-8\"><style>body{font:20px serif;padding:24px}</style><h1>Euler's totient function</h1><p>For a positive integer n, phi(n) counts the positive integers at most n that are coprime to n.</p>"
    override fun urlFor(resource: ContentResourceRef): Uri = Uri.parse("https://appassets.androidplatform.net/document/${resource.packageSha256}/${resource.contentKey}/${resource.memberPath}")
    override fun intercept(uri: Uri): WebResourceResponse? = if (uri.path?.startsWith("/document/") == true) {
        WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(HTML.encodeToByteArray()))
    } else null
    override fun readDocument(resource: ContentResourceRef): String = HTML
}
