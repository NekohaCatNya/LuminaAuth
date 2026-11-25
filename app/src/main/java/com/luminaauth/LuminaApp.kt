package com.luminaauth

import android.app.Application

class LuminaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
