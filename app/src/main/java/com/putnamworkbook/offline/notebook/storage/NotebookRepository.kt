package com.putnamworkbook.offline.notebook.storage

import com.putnamworkbook.offline.domain.model.PaperStyle
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.notebook.model.NotebookDocument
import com.putnamworkbook.offline.notebook.model.StrokeRecord

interface NotebookRepository {
    suspend fun loadOrCreate(key: ProblemKey, defaultPaper: PaperStyle): NotebookDocument

    suspend fun loadStrokes(key: ProblemKey, pageId: String): List<StrokeRecord>

    suspend fun commitStroke(key: ProblemKey, pageId: String, stroke: StrokeRecord): NotebookDocument

    suspend fun setStrokeActive(
        key: ProblemKey,
        pageId: String,
        strokeId: String,
        active: Boolean,
    ): NotebookDocument

    suspend fun addPage(key: ProblemKey, afterPageId: String, paperStyle: PaperStyle): NotebookDocument

    suspend fun deletePage(key: ProblemKey, pageId: String): NotebookDocument

    suspend fun clearPage(key: ProblemKey, pageId: String): NotebookDocument

    suspend fun reorderPage(key: ProblemKey, pageId: String, newIndex: Int): NotebookDocument
}
