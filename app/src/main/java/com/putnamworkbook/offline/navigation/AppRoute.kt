package com.putnamworkbook.offline.navigation

import android.net.Uri
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.domain.model.ProblemSetKey
import java.util.UUID

sealed interface AppRoute {
    val path: String

    data object Home : AppRoute {
        override val path = HOME
    }

    data object Library : AppRoute {
        override val path = LIBRARY
    }

    data class ProblemLibrary(val focusKey: ProblemKey? = null) : AppRoute {
        override val path = focusKey?.let { key ->
            "$PROBLEM_LIBRARY?focus=${Uri.encode(key.canonical)}"
        } ?: PROBLEM_LIBRARY
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

    data object FreeWorkspaces : AppRoute {
        override val path = FREE_WORKSPACES
    }

    data class FreeWorkspace(val id: UUID) : AppRoute {
        override val path = "$FREE_WORKSPACES/${Uri.encode(id.toString())}"
    }

    companion object {
        const val HOME = "home"
        const val LIBRARY = "library"
        const val PROBLEM_LIBRARY = "problem-library"
        const val PROBLEM_LIBRARY_PATTERN = "$PROBLEM_LIBRARY?focus={focus}"
        const val SET_PROBLEMS_PREFIX = "library/set"
        const val SET_PROBLEMS_PATTERN = "$SET_PROBLEMS_PREFIX/{collectionId}/{setId}"
        const val WORKSPACE_PREFIX = "workspace"
        const val WORKSPACE_PATTERN = "$WORKSPACE_PREFIX/{collectionId}/{setId}/{problemId}"
        const val SETTINGS = "settings"
        const val FREE_WORKSPACES = "free-notebooks"
        const val FREE_WORKSPACE_PATTERN = "$FREE_WORKSPACES/{workspaceId}"
    }
}
