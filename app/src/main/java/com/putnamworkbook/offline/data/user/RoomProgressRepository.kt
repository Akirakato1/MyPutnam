package com.putnamworkbook.offline.data.user

import androidx.room.withTransaction
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.domain.model.ProblemStatus
import com.putnamworkbook.offline.domain.model.ReferenceTab
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomProgressRepository(
    private val database: UserDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) : ProgressRepository {
    private val dao = database.userDao()

    override fun observe(key: ProblemKey): Flow<ProblemProgress> =
        dao.observeProgress(key.canonical).map { it?.toDomain() ?: ProblemProgress.untouched(key) }

    override fun observeAll(): Flow<Map<ProblemKey, ProblemProgress>> =
        dao.observeAllProgress().map { rows -> rows.associate { it.toDomain().let { progress -> progress.key to progress } } }

    override fun observeRecent(): Flow<ProblemProgress?> =
        dao.observeRecentProgress().map { it?.toDomain() }

    override suspend fun markOpened(key: ProblemKey) = mutate(key) { current -> current }

    override suspend fun revealNextHint(key: ProblemKey): Int = database.withTransaction {
        val current = dao.progress(key.canonical) ?: newProgress(key)
        val revealedCount = (current.revealedHintCount + 1).coerceAtMost(MAX_HINTS)
        dao.upsertProgress(current.copy(revealedHintCount = revealedCount, lastOpenedAt = clock()))
        revealedCount
    }

    override suspend fun revealSolution(key: ProblemKey) = mutate(key) { current ->
        current.copy(solutionRevealed = true)
    }

    override suspend fun markCompleted(key: ProblemKey, completed: Boolean) = mutate(key) { current ->
        current.copy(status = if (completed) ProblemStatus.COMPLETED.name else ProblemStatus.IN_PROGRESS.name)
    }

    override suspend fun setNotebookState(
        key: ProblemKey,
        pageCount: Int,
        pageIndex: Int,
        referenceTab: ReferenceTab,
    ) {
        require(pageCount >= 1) { "A notebook must contain at least one page" }
        require(pageIndex in 0 until pageCount) { "Active page index must identify an existing page" }
        mutate(key) { current ->
            current.copy(
                notebookPageCount = pageCount,
                activePageIndex = pageIndex,
                activeReferenceTab = referenceTab.name,
            )
        }
    }

    private suspend fun mutate(
        key: ProblemKey,
        transform: (ProblemProgressEntity) -> ProblemProgressEntity,
    ) = database.withTransaction {
        val current = dao.progress(key.canonical) ?: newProgress(key)
        dao.upsertProgress(transform(current).copy(lastOpenedAt = clock()))
    }

    private fun newProgress(key: ProblemKey) = ProblemProgressEntity(
        problemKey = key.canonical,
        status = ProblemStatus.IN_PROGRESS.name,
        revealedHintCount = 0,
        solutionRevealed = false,
        notebookPageCount = 1,
        activePageIndex = 0,
        activeReferenceTab = ReferenceTab.PROBLEM.name,
        lastOpenedAt = clock(),
    )

    private companion object {
        const val MAX_HINTS = 4
    }
}

private fun ProblemProgressEntity.toDomain() = ProblemProgress(
    key = ProblemKey.parse(problemKey),
    status = ProblemStatus.valueOf(status),
    revealedHintCount = revealedHintCount,
    solutionRevealed = solutionRevealed,
    notebookPageCount = notebookPageCount,
    activePageIndex = activePageIndex,
    activeReferenceTab = ReferenceTab.valueOf(activeReferenceTab),
    lastOpenedAt = lastOpenedAt,
)
