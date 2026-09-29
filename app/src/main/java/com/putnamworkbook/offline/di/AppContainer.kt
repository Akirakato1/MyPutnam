package com.putnamworkbook.offline.di

import android.content.Context
import com.putnamworkbook.offline.data.content.ContentDatabase
import com.putnamworkbook.offline.data.content.ContentRepository
import com.putnamworkbook.offline.data.content.RoomContentRepository
import com.putnamworkbook.offline.data.user.ProgressRepository
import com.putnamworkbook.offline.data.user.RoomProgressRepository
import com.putnamworkbook.offline.data.user.RoomSettingsRepository
import com.putnamworkbook.offline.data.user.SettingsRepository
import com.putnamworkbook.offline.data.user.UserDatabase
import com.putnamworkbook.offline.notebook.storage.FileNotebookRepository
import com.putnamworkbook.offline.notebook.storage.NotebookRepository

class AppContainer(context: Context) {
    val applicationContext: Context = context.applicationContext
    val contentDatabase: ContentDatabase by lazy { ContentDatabase.create(applicationContext) }
    val contentRepository: ContentRepository by lazy { RoomContentRepository(contentDatabase) }
    val userDatabase: UserDatabase by lazy { UserDatabase.create(applicationContext) }
    val progressRepository: ProgressRepository by lazy { RoomProgressRepository(userDatabase) }
    val settingsRepository: SettingsRepository by lazy { RoomSettingsRepository(userDatabase) }
    val notebookRepository: NotebookRepository by lazy {
        FileNotebookRepository(applicationContext.filesDir.toPath().resolve("notebooks"))
    }
}
