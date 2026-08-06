package com.syna

import android.app.Application
import android.content.Context

class SynaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: SynaApp
            private set

        val context: Context
            get() = instance.applicationContext
    }
}
