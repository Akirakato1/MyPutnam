package com.putnamworkbook.offline.feature.library

import com.putnamworkbook.offline.data.library.catalog.CatalogContentType
import com.putnamworkbook.offline.data.library.index.PackageAction
import com.putnamworkbook.offline.data.library.index.PackageOperation

enum class LibraryContentSegment { PROBLEMS, MATH_REFERENCE }
enum class LibraryInstallFilter { ALL, INSTALLED, AVAILABLE, UPDATE_AVAILABLE, APP_UPDATE_REQUIRED }
enum class LibraryEntryStatus(val label: String) {
    INSTALLED("Installed"),
    AVAILABLE("Available"),
    UPDATE_AVAILABLE("Update available"),
    APP_UPDATE_REQUIRED("App update required"),
    QUARANTINED("Needs reinstall"),
}

data class ProblemLibraryFilters(
    val query: String = "",
    val collectionId: String? = null,
    val setId: String? = null,
    val topicId: String? = null,
    val referenceCategoryId: String? = null,
    val installState: LibraryInstallFilter = LibraryInstallFilter.ALL,
)

data class LibraryEntryRow(
    val key: String,
    val contentType: CatalogContentType,
    val title: String,
    val identity: String,
    val collectionId: String?,
    val setId: String?,
    val topics: List<String>,
    val categoryId: String?,
    val aliases: List<String>,
    val keywords: List<String>,
    val compressedBytes: Long,
    val status: LibraryEntryStatus,
    val primaryAction: PackageAction?,
    val actionEnabled: Boolean,
    val bundled: Boolean,
    val hasProgress: Boolean,
    val catalogListed: Boolean,
    val operation: PackageOperation?,
)

data class LibraryActionConfirmation(
    val keys: Set<String>,
    val action: PackageAction,
    val problemCount: Int,
)

data class LibraryBatchProgress(
    val completed: Int,
    val remaining: Int,
    val failed: Int,
    val cancelled: Int,
)

data class ProblemLibraryUiState(
    val contentSegment: LibraryContentSegment = LibraryContentSegment.PROBLEMS,
    val filters: ProblemLibraryFilters = ProblemLibraryFilters(),
    val allEntries: List<LibraryEntryRow> = emptyList(),
    val visibleEntries: List<LibraryEntryRow> = emptyList(),
    val selectedKeys: Set<String> = emptySet(),
    val focusedKey: String? = null,
    val isOnline: Boolean = true,
    val isRefreshing: Boolean = false,
    val lastSuccessfulRefreshAt: Long? = null,
    val refreshError: String? = null,
    val confirmation: LibraryActionConfirmation? = null,
    val batchProgress: LibraryBatchProgress? = null,
)
