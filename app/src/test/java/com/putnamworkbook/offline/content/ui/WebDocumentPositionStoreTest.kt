package com.putnamworkbook.offline.content.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class WebDocumentPositionStoreTest {
    @Test
    fun documentsAndRevisionsKeepIndependentNonnegativePositions() {
        val store = WebDocumentPositionStore(maxEntries = 3)
        store.update("article|revision-a", 120)
        store.update("article|revision-b", 480)
        store.update("other|revision-a", -20)

        assertEquals(120, store.position("article|revision-a"))
        assertEquals(480, store.position("article|revision-b"))
        assertEquals(0, store.position("other|revision-a"))
        store.clear("article|revision-a")
        assertEquals(0, store.position("article|revision-a"))
    }

    @Test
    fun leastRecentlyUsedEntriesAreBounded() {
        val store = WebDocumentPositionStore(maxEntries = 2)
        store.update("a", 1)
        store.update("b", 2)
        assertEquals(1, store.position("a"))
        store.update("c", 3)

        assertEquals(1, store.position("a"))
        assertEquals(0, store.position("b"))
        assertEquals(3, store.position("c"))
    }
}
