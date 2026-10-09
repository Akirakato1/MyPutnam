package com.putnamworkbook.offline.feature.workspace

import android.content.Context
import android.webkit.WebView
import com.putnamworkbook.offline.content.ui.LocalContentClient
import com.putnamworkbook.offline.content.ui.LocalContentClientFactory
import com.putnamworkbook.offline.data.content.ContentDocument
import com.putnamworkbook.offline.data.content.HintContent
import com.putnamworkbook.offline.data.content.ProblemContent
import com.putnamworkbook.offline.data.content.ProblemSummary
import com.putnamworkbook.offline.data.user.ProblemProgress
import com.putnamworkbook.offline.domain.model.HintStage
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.domain.model.ProblemStatus
import com.putnamworkbook.offline.domain.model.ReferenceTab

internal object ReferenceRendererFixture {
    const val STATEMENT = "content/putnam/2025/problems/A1/statement.html"
    const val SOLUTION = "content/putnam/2025/problems/A1/solution.html"
    val HINTS = (1..4).map { "content/putnam/2025/problems/A1/hint-$it.html" }
    val PREREQUISITES = (1..2).map { "content/putnam/2025/problems/A1/prerequisite-$it.html" }

    fun state(tab: ReferenceTab): WorkspaceUiState {
        val key = ProblemKey("putnam", "2025", "A1")
        return WorkspaceUiState(
            key = key,
            content = ProblemContent(
                summary = ProblemSummary(key, "A1", 1, "Putnam Competition", "2025", 2025),
                statementAssetPath = STATEMENT,
                statementSha256 = "0".repeat(64),
                hints = HINTS.mapIndexed { index, path ->
                    HintContent(
                        order = index + 1,
                        stage = HintStage.entries[index],
                        title = "Hint ${index + 1}",
                        assetPath = path,
                        sha256 = (index + 1).toString().repeat(64),
                    )
                },
                prerequisites = PREREQUISITES.mapIndexed { index, path ->
                    ContentDocument(index + 1, "Prerequisite ${index + 1}", path, "a".repeat(64))
                },
                solutionAssetPath = SOLUTION,
                solutionSha256 = "b".repeat(64),
                attribution = emptyList(),
            ),
            progress = ProblemProgress(
                key = key,
                status = ProblemStatus.IN_PROGRESS,
                revealedHintCount = 4,
                solutionRevealed = true,
                notebookPageCount = 1,
                activePageIndex = 0,
                activeReferenceTab = tab,
                lastOpenedAt = 1L,
            ),
            selectedReferenceTab = tab,
        )
    }

    fun requireRepresentativeKatex(context: Context) {
        val statement = context.assets.open(STATEMENT).bufferedReader().use { it.readText() }
        check(statement.length > 10_000) { "Reference fixture must exceed the pane height" }
        check(statement.contains("class=\"katex\"")) { "Fixture must contain inline KaTeX" }
        check(statement.contains("class=\"katex-display\"")) { "Fixture must contain display KaTeX" }
    }
}

internal class ControlledLocalContentClientFactory : LocalContentClientFactory {
    private val pendingLoads = ArrayDeque<() -> Unit>()
    private val clients = mutableListOf<LocalContentClient>()
    private var holdLoads = true

    override fun create(
        assetContext: Context,
        onIntegrityError: (String) -> Unit,
        onRendererGone: () -> Unit,
        onPageReady: (WebView) -> Unit,
    ): LocalContentClient = LocalContentClient(
        assetContext = assetContext,
        onIntegrityError = onIntegrityError,
        onRendererGone = onRendererGone,
        onPageReady = onPageReady,
        loadDispatcher = { load ->
            if (holdLoads) pendingLoads += load else load()
        },
    ).also(clients::add)

    fun holdNextLoads() {
        holdLoads = true
    }

    fun releaseLoads() {
        holdLoads = false
        while (pendingLoads.isNotEmpty()) pendingLoads.removeFirst().invoke()
    }

    fun simulateRendererLoss() {
        check(clients.isNotEmpty())
        clients.last().onRenderProcessGone(null, null)
    }
}
