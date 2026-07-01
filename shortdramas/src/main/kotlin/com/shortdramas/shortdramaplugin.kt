package com.shortdramas

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ShortDramasPlugin : Plugin() {
    override fun load() {
        registerMainAPI(ShortDramas())
    }
}