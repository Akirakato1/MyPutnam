package com.putnamworkbook.offline.domain.model

enum class Handedness {
    LEFT,
    RIGHT,
}

enum class AppTheme {
    SYSTEM,
    LIGHT,
    DARK,
}

data class PalmGuardSettings(
    val enabled: Boolean = true,
    val widthFraction: Float = 0.24f,
    val heightFraction: Float = 0.42f,
) {
    init {
        require(widthFraction in 0.1f..0.5f) { "Palm-guard width must be between 0.1 and 0.5" }
        require(heightFraction in 0.1f..0.8f) { "Palm-guard height must be between 0.1 and 0.8" }
    }
}

data class AppSettings(
    val handedness: Handedness = Handedness.RIGHT,
    val palmGuard: PalmGuardSettings = PalmGuardSettings(),
    val defaultPaperStyle: PaperStyle = PaperStyle.SQUARE_GRID,
    val brush: BrushSpec = BrushSpec(),
    val theme: AppTheme = AppTheme.SYSTEM,
    val keepAwakeInWorkspace: Boolean = true,
)
