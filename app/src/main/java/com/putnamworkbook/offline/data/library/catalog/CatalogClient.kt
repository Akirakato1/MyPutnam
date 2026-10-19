package com.putnamworkbook.offline.data.library.catalog

fun interface CatalogClient {
    suspend fun fetch(validators: CatalogValidators?): CatalogFetchResult
}

sealed interface CatalogFetchResult {
    data class Modified(val bytes: ByteArray, val validators: CatalogValidators) : CatalogFetchResult
    data object NotModified : CatalogFetchResult
    data class Failed(val message: String) : CatalogFetchResult
}
