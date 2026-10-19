package com.putnamworkbook.offline.feature.library

import com.putnamworkbook.offline.data.library.catalog.CatalogContentType

internal fun filterLibraryEntries(
    entries: List<LibraryEntryRow>,
    segment: LibraryContentSegment,
    filters: ProblemLibraryFilters,
): List<LibraryEntryRow> {
    val query = filters.query.trim().lowercase()
    return entries.asSequence()
        .filter {
            when (segment) {
                LibraryContentSegment.PROBLEMS -> it.contentType == CatalogContentType.PROBLEM
                LibraryContentSegment.MATH_REFERENCE -> it.contentType == CatalogContentType.REFERENCE
            }
        }
        .filter { filters.collectionId == null || it.collectionId == filters.collectionId }
        .filter { filters.setId == null || it.setId == filters.setId }
        .filter { filters.topicId == null || filters.topicId in it.topics }
        .filter { filters.referenceCategoryId == null || it.categoryId == filters.referenceCategoryId }
        .filter {
            when (filters.installState) {
                LibraryInstallFilter.ALL -> true
                LibraryInstallFilter.INSTALLED -> it.status == LibraryEntryStatus.INSTALLED
                LibraryInstallFilter.AVAILABLE -> it.status == LibraryEntryStatus.AVAILABLE
                LibraryInstallFilter.UPDATE_AVAILABLE -> it.status == LibraryEntryStatus.UPDATE_AVAILABLE
                LibraryInstallFilter.APP_UPDATE_REQUIRED -> it.status == LibraryEntryStatus.APP_UPDATE_REQUIRED
            }
        }
        .filter {
            query.isEmpty() || listOfNotNull(
                it.title, it.identity, it.collectionId, it.setId, it.categoryId,
                *it.topics.toTypedArray(), *it.aliases.toTypedArray(), *it.keywords.toTypedArray(),
            ).any { value -> query in value.lowercase() }
        }
        .sortedWith(compareBy<LibraryEntryRow>({ it.collectionId.orEmpty() }, { it.setId.orEmpty() }, { it.identity }, { it.title }))
        .toList()
}
