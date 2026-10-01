package com.putnamworkbook.offline.domain.model

enum class HintStage(val storageValue: String) {
    NUDGE("nudge"),
    STRATEGY("strategy"),
    KNOWLEDGE("knowledge"),
    OUTLINE("outline"),
    ;

    companion object {
        fun fromStorageValue(value: String): HintStage =
            entries.firstOrNull { it.storageValue == value }
                ?: error("Unknown hint stage: $value")
    }
}
