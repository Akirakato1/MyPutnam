package com.putnamworkbook.offline.data.user

import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.domain.model.ProblemStatus
import com.putnamworkbook.offline.domain.model.ReferenceTab
import kotlinx.coroutines.flow.Flow

data class ProblemProgress(
    val key: ProblemKey,
    val status: ProblemStatus,
    val revealedHintCount: Int,
    val solutionRevealed: Boolean,
    val notebookPageCount: Int,
    val activePageIndex: Int,
    val activeReferenceTab: ReferenceTab,
    val lastOpenedAt: Long?,
) {
    companion object {
        fun untouched(key: ProblemKey) = ProblemProgress(
            key = key,
            status = ProblemStatus.UNTOUCHED,
            revealedHintCount = 0,
            solutionRevealed = false,
            notebookPageCount = 1,
            activePageIndex = 0,
            activeReferenceTab = ReferenceTab.PROBLEM,
            lastOpenedAt = null,
        )
    }
}

interface ProgressRepository {
    fun observe(key: ProblemKey): Flow<ProblemProgress>

    fun observeAll(): Flow<Map<ProblemKey, ProblemProgress>>

    fun observeRecent(): Flow<ProblemProgress?>

    suspend fun markOpened(key: ProblemKey)

    suspend fun revealNextHint(key: ProblemKey): Int

    suspend fun revealSolution(key: ProblemKey)

    suspend fun markCompleted(key: ProblemKey, completed: Boolean)

    suspend fun setNotebookState(
        key: ProblemKey,
        pageCount: Int,
        pageIndex: Int,
        referenceTab: ReferenceTab,
    )
}
