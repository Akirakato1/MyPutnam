package com.putnamworkbook.offline.data.library.index

import com.putnamworkbook.offline.data.library.catalog.PublicationCatalog
import kotlinx.coroutines.flow.Flow

interface InstalledContentIndex {
    fun observe(): Flow<InstalledContentSnapshot>
    suspend fun reconcileCatalog(catalog: PublicationCatalog)
    suspend fun reconcileBundled(catalog: PublicationCatalog)
    suspend fun activateDownloaded(entry: InstalledPackage)
    suspend fun logicallyRemove(key: String)
    suspend fun restoreBundled(key: String)
    suspend fun quarantine(key: String, digest: String, error: InstallError)
    suspend fun recordFailure(key: String, error: InstallError)
    suspend fun exportBackupState(): LibraryBackupState
    suspend fun applyBackupState(state: LibraryBackupState): ReinstallPlan
}

interface PackageOperationStore {
    fun observeOperations(): Flow<List<PackageOperation>>
    suspend fun operation(id: String): PackageOperation?
    suspend fun upsertOperation(operation: PackageOperation)
    suspend fun recoverInterruptedOperations(now: Long)
}
