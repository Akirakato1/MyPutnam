package com.putnamworkbook.offline

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.putnamworkbook.offline.di.AppContainer
import com.putnamworkbook.offline.navigation.PutnamNavHost

@Composable
fun PutnamApp(container: AppContainer) {
    MaterialTheme {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .testTag("app-root"),
            color = MaterialTheme.colorScheme.background,
        ) {
            PutnamNavHost(
                contentRepository = container.contentRepository,
                progressRepository = container.progressRepository,
            )
        }
    }
}
