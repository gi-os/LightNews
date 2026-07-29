package com.gios.lightnews

import android.app.Application
import com.gios.lightnews.sync.SyncWorker
import com.gios.lightnews.ui.WebViewSupport

class LightNewsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SyncWorker.schedule(this)
        // Constructing the first WebView costs hundreds of milliseconds and must happen
        // on the main thread. Do it here rather than inside the first composition of a
        // page, where it would show up as a stall on opening a newsletter.
        WebViewSupport.prime(this)
    }
}
