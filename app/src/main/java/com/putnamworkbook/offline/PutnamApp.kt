package com.putnamworkbook.offline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.putnamworkbook.offline.di.AppContainer

@Composable
fun PutnamApp(container: AppContainer) {
    MaterialTheme {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .testTag("app-root"),
            color = MaterialTheme.colorScheme.background,
        ) {
            Box(
                modifier = Modifier.padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Putnam Workbook",
                    style = MaterialTheme.typography.headlineLarge,
                )
            }
        }
    }

    // Force construction during startup while the container is intentionally minimal.
    container.applicationContext
}
