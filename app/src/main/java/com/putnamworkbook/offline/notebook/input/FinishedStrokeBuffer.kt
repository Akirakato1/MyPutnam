package com.putnamworkbook.offline.notebook.input

import com.putnamworkbook.offline.notebook.model.StrokeRecord

class FinishedStrokeBuffer<K> {
    private val records = LinkedHashMap<K, StrokeRecord>()
    private val awaitingRender = LinkedHashMap<K, StrokeRecord>()

    fun put(id: K, stroke: StrokeRecord) {
        require(id !in records && id !in awaitingRender) {
            "A pending ink id cannot be reused before completion"
        }
        records[id] = stroke
    }

    fun finish(finishedIds: Collection<K>): Map<K, StrokeRecord> = buildMap {
        finishedIds.forEach { id ->
            records.remove(id)?.let { record ->
                awaitingRender[id] = record
                put(id, record)
            }
        }
    }

    fun acknowledgeRendered(renderedStrokeIds: Set<String>): List<K> = buildList {
        val acknowledged = awaitingRender.entries
            .filter { (_, record) -> record.id in renderedStrokeIds }
            .map { it.key }
        acknowledged.forEach { id ->
            awaitingRender.remove(id)
            add(id)
        }
    }

    fun remove(id: K): StrokeRecord? = records.remove(id) ?: awaitingRender.remove(id)
}
