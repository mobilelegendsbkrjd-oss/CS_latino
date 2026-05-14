package com.Dailymotion

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class DailymotionPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Dailymotion())
    }
}
