package com.putnamworkbook.offline.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.putnamworkbook.offline.data.content.ContentRepository
import com.putnamworkbook.offline.data.user.ProgressRepository
import com.putnamworkbook.offline.feature.archive.ArchiveScreen
import com.putnamworkbook.offline.feature.archive.ArchiveViewModel
import com.putnamworkbook.offline.feature.archive.ProblemListScreen
import com.putnamworkbook.offline.feature.home.HomeScreen
import com.putnamworkbook.offline.feature.home.HomeViewModel

@Composable
fun PutnamNavHost(
    contentRepository: ContentRepository,
    progressRepository: ProgressRepository,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val homeViewModel = remember(progressRepository) { HomeViewModel(progressRepository) }
    val archiveViewModel = remember(contentRepository, progressRepository) {
        ArchiveViewModel(contentRepository, progressRepository)
    }
    val homeState by homeViewModel.uiState.collectAsState()
    val archiveState by archiveViewModel.uiState.collectAsState()

    DisposableEffect(homeViewModel, archiveViewModel) {
        onDispose {
            homeViewModel.close()
            archiveViewModel.close()
        }
    }

    NavHost(
        navController = navController,
        startDestination = AppRoute.Home.path,
        modifier = modifier,
    ) {
        composable(AppRoute.HOME) {
            HomeScreen(
                state = homeState,
                onContinue = { key -> navController.navigate(AppRoute.Workspace(key.year, key.code).path) },
                onSelectProblem = { navController.navigate(AppRoute.ArchiveYears.path) },
                onSettings = { navController.navigate(AppRoute.Settings.path) },
            )
        }
        composable(AppRoute.ARCHIVE_YEARS) {
            ArchiveScreen(
                state = archiveState,
                onBack = { navController.popBackStack() },
                onSelectYear = { year -> navController.navigate(AppRoute.YearProblems(year).path) },
            )
        }
        composable(
            route = AppRoute.YEAR_PROBLEMS_PATTERN,
            arguments = listOf(navArgument("year") { type = NavType.IntType }),
        ) { entry ->
            val year = requireNotNull(entry.arguments?.getInt("year"))
            LaunchedEffect(year) { archiveViewModel.selectYear(year) }
            ProblemListScreen(
                year = year,
                state = archiveState,
                onBack = { navController.popBackStack() },
                onSelectProblem = { key -> navController.navigate(AppRoute.Workspace(key.year, key.code).path) },
            )
        }
        composable(
            route = AppRoute.WORKSPACE_PATTERN,
            arguments = listOf(
                navArgument("year") { type = NavType.IntType },
                navArgument("code") { type = NavType.StringType },
            ),
        ) { entry ->
            val year = requireNotNull(entry.arguments?.getInt("year"))
            val code = requireNotNull(entry.arguments?.getString("code"))
            Placeholder(
                text = "Workspace $year $code",
                tag = "workspace-placeholder-$year-$code",
            )
        }
        composable(AppRoute.SETTINGS) {
            Placeholder(text = "Settings", tag = "settings-placeholder")
        }
    }
}

@Composable
private fun Placeholder(text: String, tag: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(text)
    }
}
