package com.putnamworkbook.offline.notebook.ui

import com.putnamworkbook.offline.domain.model.PaperStyle
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.notebook.model.NotebookDocument
import com.putnamworkbook.offline.notebook.model.NotebookPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotebookPageAnimationTest {
    @Test
    fun removedOutgoingPageIndexIsIgnoredDuringPageTurnAnimation() {
        val documentAfterDelete = NotebookDocument(
            key = ProblemKey("putnam", "2025", "A1"),
            pages = listOf(NotebookPage("remaining", PaperStyle.BLANK)),
        )

        assertEquals("remaining", pageForAnimatedIndex(documentAfterDelete, 0)?.id)
        assertNull(pageForAnimatedIndex(documentAfterDelete, 1))
    }

    @Test
    fun pageDeletionRequiresMoreThanOnePage() {
        assertFalse(canDeleteNotebookPage(1))
        assertTrue(canDeleteNotebookPage(2))
    }
}
