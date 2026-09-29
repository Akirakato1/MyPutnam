package com.putnamworkbook.offline.data.user

import com.putnamworkbook.offline.domain.model.AppSettings
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    fun observe(): Flow<AppSettings>

    suspend fun update(transform: (AppSettings) -> AppSettings)
}
