package com.putnamworkbook.offline.freeworkspace

import java.util.UUID

data class FreeWorkspaceRecord(
    val id: UUID,
    val name: String,
    val createdAtEpochMillis: Long,
    val modifiedAtEpochMillis: Long,
)

enum class FreeWorkspaceStorageState {
    READY,
    MISSING,
}

data class FreeWorkspaceEntry(
    val record: FreeWorkspaceRecord,
    val storageState: FreeWorkspaceStorageState,
)
