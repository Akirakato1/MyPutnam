package com.putnamworkbook.offline

import android.app.Application
import com.putnamworkbook.offline.di.AppContainer

class PutnamApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
