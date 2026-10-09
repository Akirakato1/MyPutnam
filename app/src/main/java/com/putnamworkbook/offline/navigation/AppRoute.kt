package com.putnamworkbook.offline.navigation

import android.net.Uri
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.domain.model.ProblemSetKey

sealed interface AppRoute {
    val path: String

    data object Home : AppRoute {
        override val path = HOME
    }

    data object Library : AppRoute {
        override val path = LIBRARY
    }

    data class SetProblems(val key: ProblemSetKey) : AppRoute {
        override val path = "$SET_PROBLEMS_PREFIX/${Uri.encode(key.collectionId)}/${Uri.encode(key.setId)}"
    }

    data class Workspace(val key: ProblemKey) : AppRoute {
        override val path = "$WORKSPACE_PREFIX/${Uri.encode(key.collectionId)}/${Uri.encode(key.setId)}/${Uri.encode(key.problemId)}"
    }

    data object Settings : AppRoute {
        override val path = SETTINGS
    }

    companion object {
        const val HOME = "home"
        const val LIBRARY = "library"
        const val SET_PROBLEMS_PREFIX = "library/set"
        const val SET_PROBLEMS_PATTERN = "$SET_PROBLEMS_PREFIX/{collectionId}/{setId}"
        const val WORKSPACE_PREFIX = "workspace"
        const val WORKSPACE_PATTERN = "$WORKSPACE_PREFIX/{collectionId}/{setId}/{problemId}"
        const val SETTINGS = "settings"
    }
}
