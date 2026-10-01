package com.putnamworkbook.offline.notebook.model

data class NotebookEditGroup(
    val id: String,
    val activatedStrokeIds: List<String>,
    val deactivatedStrokeIds: List<String>,
) {
    init {
        require(id.matches(SAFE_ID)) { "Edit group id contains unsupported characters" }
        require(activatedStrokeIds.isNotEmpty() || deactivatedStrokeIds.isNotEmpty()) {
            "An edit group must change at least one stroke"
        }
        require(activatedStrokeIds.distinct().size == activatedStrokeIds.size) {
            "Activated stroke ids must be unique"
        }
        require(deactivatedStrokeIds.distinct().size == deactivatedStrokeIds.size) {
            "Deactivated stroke ids must be unique"
        }
        require(activatedStrokeIds.none(deactivatedStrokeIds::contains)) {
            "An edit group cannot activate and deactivate the same stroke"
        }
        require((activatedStrokeIds + deactivatedStrokeIds).all { it.matches(SAFE_ID) }) {
            "Edit group stroke id contains unsupported characters"
        }
    }

    private companion object {
        val SAFE_ID = Regex("[A-Za-z0-9_-]+")
    }
}
