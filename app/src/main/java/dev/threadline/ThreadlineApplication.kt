package dev.threadline

import android.app.Application

class ThreadlineApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SessionRuntime.initialize(this)
    }
}
