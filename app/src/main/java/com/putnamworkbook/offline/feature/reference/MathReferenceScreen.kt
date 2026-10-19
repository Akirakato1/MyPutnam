package com.putnamworkbook.offline.feature.reference

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.putnamworkbook.offline.domain.model.ProblemKey

@Composable
fun MathReferenceScreen(
    state: MathReferenceUiState,
    viewModel: MathReferenceViewModel,
    onBack: () -> Unit,
    onManageContent: (String) -> Unit,
    onOpenProblem: (ProblemKey) -> Unit,
    onIntegrityError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val handleBack = { if (!viewModel.back()) onBack() }
    BackHandler(onBack = handleBack)
    LaunchedEffect(state.focusedLibraryRequest) {
        state.focusedLibraryRequest?.let {
            onManageContent(it)
            viewModel.consumeFocusedLibraryRequest()
        }
    }
    LaunchedEffect(state.relatedProblemRequest) {
        state.relatedProblemRequest?.let {
            onOpenProblem(it)
            viewModel.consumeRelatedProblemRequest()
        }
    }
    MathReferenceBrowser(
        state = state,
        onQueryChanged = viewModel::setQuery,
        onOpenCategory = viewModel::openCategory,
        onOpenArticle = viewModel::openArticle,
        onOpenRelatedReference = viewModel::openRelatedReference,
        onOpenRelatedProblem = viewModel::openRelatedProblem,
        onUnavailableContent = onManageContent,
        onBack = handleBack,
        onRetry = viewModel::retry,
        onIntegrityError = onIntegrityError,
        modifier = modifier,
    )
}
