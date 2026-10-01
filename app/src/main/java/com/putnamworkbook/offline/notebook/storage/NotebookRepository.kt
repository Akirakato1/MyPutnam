package com.putnamworkbook.offline.notebook.storage

import com.putnamworkbook.offline.domain.model.PaperStyle
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.notebook.model.NotebookDocument
import com.putnamworkbook.offline.notebook.model.NotebookEdit
import com.putnamworkbook.offline.notebook.model.StrokeRecord

data class NotebookPageSnapshot(
    val id: String,
    val paperStyle: PaperStyle,
    val strokes: List<StrokeRecord>,
)

data class NotebookExportSnapshot(
    val key: ProblemKey,
    val pages: List<NotebookPageSnapshot>,
)

interface NotebookRepository {
    suspend fun loadExisting(key: ProblemKey): NotebookDocument?

    suspend fun loadExportSnapshot(key: ProblemKey): NotebookExportSnapshot?

    suspend fun loadOrCreate(key: ProblemKey, defaultPaper: PaperStyle): NotebookDocument

    suspend fun loadStrokes(key: ProblemKey, pageId: String): List<StrokeRecord>

    suspend fun applyEdits(key: ProblemKey, pageId: String, edits: List<NotebookEdit>): NotebookDocument

    suspend fun undo(key: ProblemKey, pageId: String): NotebookDocument

    suspend fun redo(key: ProblemKey, pageId: String): NotebookDocument

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

}
