package com.oneclip.app

import android.app.Application
import com.oneclip.app.core.Downloads
import com.oneclip.app.core.Engine
import com.oneclip.app.core.Prefs

class OneClipApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Downloads.init(this)
        Engine.init(this)
    }
}
