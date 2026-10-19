package com.putnamworkbook.offline.data.library.catalog

import kotlinx.coroutines.CancellationException

class CatalogRefreshCoordinator(
    private val store: CatalogStore,
    private val client: CatalogClient,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun refresh() {
        val attemptedAt = clock()
        when (val result = try {
            client.fetch(store.state.value.validators)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            CatalogFetchResult.Failed(error.message?.take(512) ?: "Catalog refresh failed")
        }) {
            is CatalogFetchResult.Modified -> try {
                store.commitRemote(result.bytes, result.validators, attemptedAt)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                store.recordFailure("Catalog validation failed: ${error.message?.take(384) ?: "invalid catalog"}", attemptedAt)
            }
            CatalogFetchResult.NotModified -> store.recordNotModified(store.state.value.validators, attemptedAt)
            is CatalogFetchResult.Failed -> store.recordFailure(result.message, attemptedAt)
        }
    }
}
