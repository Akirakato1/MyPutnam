package com.putnamworkbook.offline.notebook.model

import com.putnamworkbook.offline.domain.model.ProblemKey

data class NotebookDocument(
    val schemaVersion: Int = SCHEMA_VERSION,
    val key: ProblemKey,
    val pages: List<NotebookPage>,
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) { "Unsupported notebook schema version: $schemaVersion" }
        require(pages.isNotEmpty()) { "A notebook must contain at least one page" }
        require(pages.map { it.id }.distinct().size == pages.size) { "Page ids must be unique" }
    }

    companion object {
        const val SCHEMA_VERSION = 3
    }
}
