package com.putnamworkbook.offline.di

import android.content.Context
import com.putnamworkbook.offline.data.content.ContentDatabase
import com.putnamworkbook.offline.data.content.ContentRepository
import com.putnamworkbook.offline.data.content.RoomContentRepository

class AppContainer(context: Context) {
    val applicationContext: Context = context.applicationContext
    val contentDatabase: ContentDatabase by lazy { ContentDatabase.create(applicationContext) }
    val contentRepository: ContentRepository by lazy { RoomContentRepository(contentDatabase) }
}
