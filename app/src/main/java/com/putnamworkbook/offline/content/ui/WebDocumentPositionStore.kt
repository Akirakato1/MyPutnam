package com.putnamworkbook.offline.content.ui

import com.putnamworkbook.offline.data.content.ContentResourceRef

class WebDocumentPositionStore(private val maxEntries: Int = 128) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    private val positions = LinkedHashMap<String, Int>(maxEntries, 0.75f, true)

    @Synchronized
    fun position(documentId: String): Int = positions[documentId] ?: 0

    @Synchronized
    fun update(documentId: String, scrollY: Int) {
        positions[documentId] = scrollY.coerceAtLeast(0)
        while (positions.size > maxEntries) {
            positions.remove(positions.entries.first().key)
        }
    }

    @Synchronized
    fun clear(documentId: String) {
        positions.remove(documentId)
    }
}

fun ContentResourceRef.webDocumentId(): String = "$contentKey|$memberPath|$packageSha256"
