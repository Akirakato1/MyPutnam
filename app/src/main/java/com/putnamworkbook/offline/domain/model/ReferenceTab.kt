package com.putnamworkbook.offline.domain.model

enum class ReferenceTab {
    PROBLEM,
    HELP,
    PREREQUISITES,
    SOLUTION,
    @Deprecated("Original papers are no longer part of the runtime UI")
    ORIGINAL_PAPER,

    ;

    @Suppress("DEPRECATION")
    fun normalizedForDisplay(): ReferenceTab =
        if (this == ORIGINAL_PAPER) PROBLEM else this

    companion object {
        val visibleEntries: List<ReferenceTab> = listOf(PROBLEM, HELP, PREREQUISITES, SOLUTION)
    }
}
