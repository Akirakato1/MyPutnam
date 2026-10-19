package com.putnamworkbook.offline.data.library.catalog

import kotlinx.coroutines.flow.StateFlow

data class CatalogValidators(val etag: String?, val lastModified: String?)

enum class CatalogSource { EMBEDDED, REMOTE_CACHE }

data class CatalogState(
    val catalog: PublicationCatalog? = null,
    val source: CatalogSource? = null,
    val validators: CatalogValidators = CatalogValidators(null, null),
    val lastSuccessfulRefreshAt: Long? = null,
    val lastAttemptedRefreshAt: Long? = null,
    val lastError: String? = null,
)

interface CatalogStore {
    val state: StateFlow<CatalogState>
    suspend fun initialize()
    suspend fun commitRemote(bytes: ByteArray, validators: CatalogValidators, refreshedAt: Long)
    suspend fun recordNotModified(validators: CatalogValidators, refreshedAt: Long)
    suspend fun recordFailure(message: String, attemptedAt: Long)
}
