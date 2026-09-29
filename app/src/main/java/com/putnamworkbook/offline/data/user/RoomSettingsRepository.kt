package com.putnamworkbook.offline.data.user

import androidx.room.withTransaction
import com.putnamworkbook.offline.domain.model.AppSettings
import com.putnamworkbook.offline.domain.model.AppTheme
import com.putnamworkbook.offline.domain.model.BrushSpec
import com.putnamworkbook.offline.domain.model.Handedness
import com.putnamworkbook.offline.domain.model.PalmGuardSettings
import com.putnamworkbook.offline.domain.model.PaperStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomSettingsRepository(
    private val database: UserDatabase,
) : SettingsRepository {
    private val dao = database.userDao()

    override fun observe(): Flow<AppSettings> =
        dao.observeSettings().map { it?.toDomain() ?: AppSettings() }

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        database.withTransaction {
            val current = dao.settings()?.toDomain() ?: AppSettings()
            dao.upsertSettings(transform(current).toEntity())
        }
    }
}

private fun AppSettingEntity.toDomain() = AppSettings(
    handedness = Handedness.valueOf(handedness),
    palmGuard = PalmGuardSettings(
        enabled = palmGuardEnabled,
        widthFraction = palmGuardWidthFraction,
        heightFraction = palmGuardHeightFraction,
    ),
    defaultPaperStyle = PaperStyle.valueOf(defaultPaperStyle),
    brush = BrushSpec(colorArgb = brushColorArgb, width = brushWidth),
    theme = AppTheme.valueOf(theme),
    keepAwakeInWorkspace = keepAwakeInWorkspace,
)

private fun AppSettings.toEntity() = AppSettingEntity(
    handedness = handedness.name,
    palmGuardEnabled = palmGuard.enabled,
    palmGuardWidthFraction = palmGuard.widthFraction,
    palmGuardHeightFraction = palmGuard.heightFraction,
    defaultPaperStyle = defaultPaperStyle.name,
    brushColorArgb = brush.colorArgb,
    brushWidth = brush.width,
    theme = theme.name,
    keepAwakeInWorkspace = keepAwakeInWorkspace,
)
