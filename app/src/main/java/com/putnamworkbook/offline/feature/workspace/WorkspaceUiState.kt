package com.putnamworkbook.offline.feature.workspace

import com.putnamworkbook.offline.data.content.ProblemContent
import com.putnamworkbook.offline.data.user.ProblemProgress
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.domain.model.ReferenceTab

enum class RevealStage {
    HINT,
    SOLUTION,
    NONE,
}

enum class WorkspaceAvailability {
    IDLE,
    LOADING,
    AVAILABLE,
    UNAVAILABLE,
}

data class WorkspaceUiState(
    val key: ProblemKey? = null,
    val content: ProblemContent? = null,
    val progress: ProblemProgress? = null,
    val selectedReferenceTab: ReferenceTab = ReferenceTab.PROBLEM,
    val activeHintIndex: Int = -1,
    val activePrerequisiteIndex: Int = 0,
    val isLoading: Boolean = false,
    val availability: WorkspaceAvailability = WorkspaceAvailability.IDLE,
    val errorMessage: String? = null,
) {
    val nextReveal: RevealStage
        get() {
            val loadedContent = content ?: return RevealStage.NONE
            val loadedProgress = progress ?: return RevealStage.NONE
            val hintLimit = minOf(MAX_PROGRESSIVE_HINTS, loadedContent.hints.size)
            return when {
                loadedProgress.revealedHintCount < hintLimit -> RevealStage.HINT
                !loadedProgress.solutionRevealed -> RevealStage.SOLUTION
                else -> RevealStage.NONE
            }
        }

    companion object {
        const val MAX_PROGRESSIVE_HINTS = 4
    }
}
