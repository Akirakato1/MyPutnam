package com.putnamworkbook.offline.navigation

import android.net.Uri

sealed interface AppRoute {
    val path: String

    data object Home : AppRoute {
        override val path = HOME
    }

    data object ArchiveYears : AppRoute {
        override val path = ARCHIVE_YEARS
    }

    data class YearProblems(val year: Int) : AppRoute {
        override val path = "$YEAR_PROBLEMS_PREFIX/$year"
    }

    data class Workspace(val year: Int, val code: String) : AppRoute {
        init {
            require(code.isNotBlank()) { "Problem code cannot be blank" }
        }

        override val path = "$WORKSPACE_PREFIX/$year/${Uri.encode(code)}"
    }

    data object Settings : AppRoute {
        override val path = SETTINGS
    }

    companion object {
        const val HOME = "home"
        const val ARCHIVE_YEARS = "archive"
        const val YEAR_PROBLEMS_PREFIX = "archive/year"
        const val YEAR_PROBLEMS_PATTERN = "$YEAR_PROBLEMS_PREFIX/{year}"
        const val WORKSPACE_PREFIX = "workspace"
        const val WORKSPACE_PATTERN = "$WORKSPACE_PREFIX/{year}/{code}"
        const val SETTINGS = "settings"
    }
}
