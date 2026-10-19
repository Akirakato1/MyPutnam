package com.putnamworkbook.offline.data.content

import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.domain.model.ProblemSetKey
import kotlinx.coroutines.flow.Flow

class CompositeContentRepository(
    private val packaged: ContentRepository,
    private val legacy: ContentRepository,
) : ContentRepository {
    override fun observeInstalledRevision(): Flow<Long> = packaged.observeInstalledRevision()
    override suspend fun contentVersion() = if (usesPackaged()) packaged.contentVersion() else legacy.contentVersion()
    override suspend fun collections() = choose(ContentRepository::collections)
    override suspend fun sets(collectionId: String) = choose { it.sets(collectionId) }
    override suspend fun topics() = choose(ContentRepository::topics)
    override suspend fun problems(setKey: ProblemSetKey) = choose { it.problems(setKey) }
    override suspend fun problemsByTopic(topicId: String) = choose { it.problemsByTopic(topicId) }
    override suspend fun problem(key: ProblemKey): ProblemContent? = packaged.problem(key) ?: legacy.problem(key)

    private suspend fun usesPackaged() = packaged.collections().isNotEmpty()
    private suspend fun <T> choose(block: suspend (ContentRepository) -> List<T>): List<T> =
        block(if (usesPackaged()) packaged else legacy)
}
