package com.putnamworkbook.offline.backup

import com.putnamworkbook.offline.data.library.index.InstalledContentIndex
import com.putnamworkbook.offline.data.library.index.ReinstallPlan

interface LibraryStateBackupPort {
    suspend fun exportState(): LibraryBackupState
    suspend fun applyState(state: LibraryBackupState): ReinstallPlan
}

class InstalledContentLibraryStateBackupPort(
    private val index: InstalledContentIndex,
) : LibraryStateBackupPort {
    override suspend fun exportState(): LibraryBackupState {
        val state = index.exportBackupState()
        return LibraryBackupState(
            desiredKeys = state.desiredKeys,
            logicallyRemovedBundledKeys = state.logicallyRemovedBundledKeys,
            activeDigests = state.downloadedDigests,
        )
    }

    override suspend fun applyState(state: LibraryBackupState): ReinstallPlan = index.applyBackupState(
        com.putnamworkbook.offline.data.library.index.LibraryBackupState(
            desiredKeys = state.desiredKeys,
            logicallyRemovedBundledKeys = state.logicallyRemovedBundledKeys,
            downloadedDigests = state.activeDigests,
        ),
    )
}
