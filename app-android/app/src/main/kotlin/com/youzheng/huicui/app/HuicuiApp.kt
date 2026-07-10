package com.youzheng.huicui.app

import android.app.Application

class HuicuiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }
}
