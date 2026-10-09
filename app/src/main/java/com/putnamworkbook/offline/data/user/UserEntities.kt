package com.putnamworkbook.offline.data.user

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(
    tableName = "problem_progress",
)
data class ProblemProgressEntity(
    @androidx.room.PrimaryKey
    @ColumnInfo(name = "problem_key") val problemKey: String,
    val status: String,
    @ColumnInfo(name = "revealed_hint_count") val revealedHintCount: Int,
    @ColumnInfo(name = "solution_revealed") val solutionRevealed: Boolean,
    @ColumnInfo(name = "notebook_page_count") val notebookPageCount: Int,
    @ColumnInfo(name = "active_page_index") val activePageIndex: Int,
    @ColumnInfo(name = "active_reference_tab") val activeReferenceTab: String,
    @ColumnInfo(name = "last_opened_at") val lastOpenedAt: Long,
)

@Entity(tableName = "app_setting")
data class AppSettingEntity(
    @androidx.room.PrimaryKey val id: Int = SINGLETON_ID,
    val handedness: String,
    @ColumnInfo(name = "palm_guard_enabled") val palmGuardEnabled: Boolean,
    @ColumnInfo(name = "palm_guard_width_fraction") val palmGuardWidthFraction: Float,
    @ColumnInfo(name = "palm_guard_height_fraction") val palmGuardHeightFraction: Float,
    @ColumnInfo(name = "default_paper_style") val defaultPaperStyle: String,
    @ColumnInfo(name = "brush_color_argb") val brushColorArgb: Long,
    @ColumnInfo(name = "brush_width") val brushWidth: Float,
    val theme: String,
    @ColumnInfo(name = "keep_awake_in_workspace") val keepAwakeInWorkspace: Boolean,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
