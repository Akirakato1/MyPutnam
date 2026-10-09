package com.putnamworkbook.offline.feature.archive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.putnamworkbook.offline.data.content.TopicSummary

@Composable
fun TopicFilterRow(
    topics: List<TopicSummary>,
    selectedTopicId: String?,
    onSelectTopic: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.testTag("topic-filter-row"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(
                selected = selectedTopicId == null,
                onClick = { onSelectTopic(null) },
                label = { Text("All") },
                modifier = Modifier.testTag("topic-all"),
            )
        }
        items(topics, key = { it.id }) { topic ->
            FilterChip(
                selected = selectedTopicId == topic.id,
                onClick = { onSelectTopic(topic.id) },
                label = { Text(topic.label) },
                modifier = Modifier.testTag("topic-${topic.id}"),
            )
        }
    }
}
