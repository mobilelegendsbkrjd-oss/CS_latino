package com.telegratishd

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class TeleGratisPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(TeleGratisProvider())
    }
}