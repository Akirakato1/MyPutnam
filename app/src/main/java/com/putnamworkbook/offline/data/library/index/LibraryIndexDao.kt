package com.putnamworkbook.offline.data.library.index

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryIndexDao {
    @Query("SELECT * FROM library_content ORDER BY content_key")
    fun observeContents(): Flow<List<LibraryContentEntity>>

    @Query("SELECT * FROM library_package ORDER BY content_key, source")
    fun observePackages(): Flow<List<LibraryPackageEntity>>

    @Query("SELECT revision FROM library_metadata WHERE id = 1")
    fun observeRevision(): Flow<Long?>

    @Query("SELECT * FROM package_operation ORDER BY updated_at DESC, operation_id")
    fun observeOperations(): Flow<List<PackageOperationEntity>>

    @Query("SELECT * FROM library_content ORDER BY content_key")
    suspend fun contents(): List<LibraryContentEntity>

    @Query("SELECT * FROM library_content WHERE content_key = :key")
    suspend fun content(key: String): LibraryContentEntity?

    @Query("SELECT * FROM library_package ORDER BY content_key, source")
    suspend fun packages(): List<LibraryPackageEntity>

    @Query("SELECT * FROM library_package WHERE content_key = :key ORDER BY source")
    suspend fun packages(key: String): List<LibraryPackageEntity>

    @Query("SELECT * FROM package_operation WHERE operation_id = :id")
    suspend fun operation(id: String): PackageOperationEntity?

    @Query("SELECT * FROM package_operation ORDER BY updated_at DESC, operation_id")
    suspend fun operations(): List<PackageOperationEntity>

    @Query("SELECT revision FROM library_metadata WHERE id = 1")
    suspend fun revision(): Long?

    @Upsert
    suspend fun upsertContent(content: LibraryContentEntity)

    @Upsert
    suspend fun upsertPackage(packageEntity: LibraryPackageEntity)

    @Upsert
    suspend fun upsertOperation(operation: PackageOperationEntity)

    @Upsert
    suspend fun upsertMetadata(metadata: LibraryMetadataEntity)

    @Query("DELETE FROM library_package WHERE content_key = :key AND source = :source")
    suspend fun deletePackage(key: String, source: String)

    @Query("DELETE FROM library_package WHERE source = :source")
    suspend fun deletePackagesBySource(source: String)

    @Query("UPDATE library_content SET catalog_listed = 0")
    suspend fun markAllCatalogUnlisted()
}
