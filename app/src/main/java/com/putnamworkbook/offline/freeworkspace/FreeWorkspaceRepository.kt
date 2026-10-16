package com.putnamworkbook.offline.freeworkspace

import com.putnamworkbook.offline.domain.model.PaperStyle
import com.putnamworkbook.offline.domain.model.ProblemKey
import java.util.UUID

fun interface EpochMillisClock {
    fun now(): Long
}

sealed interface FreeWorkspaceResult<out T> {
    data class Success<T>(val value: T) : FreeWorkspaceResult<T>
    data class NameRejected(val error: FreeWorkspaceNameError) : FreeWorkspaceResult<Nothing>
    data class Failure(val message: String) : FreeWorkspaceResult<Nothing>
}

interface FreeWorkspaceRepository {
    suspend fun list(): FreeWorkspaceResult<List<FreeWorkspaceEntry>>
    suspend fun create(name: String, defaultPaper: PaperStyle): FreeWorkspaceResult<FreeWorkspaceRecord>
    suspend fun rename(id: UUID, name: String): FreeWorkspaceResult<FreeWorkspaceRecord>
    suspend fun duplicate(sourceId: UUID, name: String): FreeWorkspaceResult<FreeWorkspaceRecord>
    suspend fun delete(id: UUID): FreeWorkspaceResult<Unit>
    suspend fun markModified(id: UUID): FreeWorkspaceResult<FreeWorkspaceRecord>
    fun notebookKey(id: UUID): ProblemKey
}
