package com.putnamworkbook.offline.data.library.packages

import com.putnamworkbook.offline.data.library.catalog.CatalogContentType
import com.putnamworkbook.offline.data.library.catalog.CatalogStore
import com.putnamworkbook.offline.data.library.catalog.PackageDescriptor
import com.putnamworkbook.offline.data.library.index.ContentMetadata
import com.putnamworkbook.offline.data.library.index.PackageAction
import com.putnamworkbook.offline.data.library.index.PackageOperation
import kotlinx.coroutines.flow.Flow

data class CatalogInstallEntry(
    val metadata: ContentMetadata,
    val descriptor: PackageDescriptor,
)

fun interface PackageCatalogSource {
    suspend fun entry(key: String): CatalogInstallEntry?
}

class CatalogStorePackageSource(private val store: CatalogStore) : PackageCatalogSource {
    override suspend fun entry(key: String): CatalogInstallEntry? {
        val catalog = store.state.value.catalog ?: return null
        catalog.problems.firstOrNull { it.key == key }?.let { problem ->
            return CatalogInstallEntry(
                ContentMetadata(
                    problem.key, CatalogContentType.PROBLEM, problem.label,
                    problem.collectionId, problem.setId, problem.problemId, problem.topics,
                    null, null, emptyList(), emptyList(), problem.ordinal, true,
                ),
                problem.packageDescriptor,
            )
        }
        catalog.references.firstOrNull { it.key == key }?.let { reference ->
            return CatalogInstallEntry(
                ContentMetadata(
                    reference.key, CatalogContentType.REFERENCE, reference.title,
                    null, null, null, emptyList(), reference.categoryId, reference.articleId,
                    reference.aliases, reference.keywords, reference.displayOrder, true,
                ),
                reference.packageDescriptor,
            )
        }
        return null
    }
}

interface PackageInstaller {
    fun observeOperations(): Flow<List<PackageOperation>>
    suspend fun enqueue(keys: Set<String>, action: PackageAction)
    suspend fun retry(operationId: String)
    suspend fun cancel(operationId: String)
}
