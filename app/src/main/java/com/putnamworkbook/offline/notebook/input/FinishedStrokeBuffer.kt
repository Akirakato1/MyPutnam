package com.putnamworkbook.offline.notebook.input

import com.putnamworkbook.offline.notebook.model.StrokeRecord

class FinishedStrokeBuffer<K> {
    private val records = LinkedHashMap<K, StrokeRecord>()

    fun put(id: K, stroke: StrokeRecord) {
        require(id !in records) { "A pending ink id cannot be reused before completion" }
        records[id] = stroke
    }

    fun drain(finishedIds: Collection<K>): List<StrokeRecord> = buildList {
        finishedIds.forEach { id -> records.remove(id)?.let(::add) }
    }

    fun remove(id: K): StrokeRecord? = records.remove(id)
}
