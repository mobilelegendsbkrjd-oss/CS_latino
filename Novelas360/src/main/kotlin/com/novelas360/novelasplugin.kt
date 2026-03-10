package com.novelas360

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NovelasPlugin : Plugin() {
    override fun load() {
        registerMainAPI(Novelas())
    }
}
