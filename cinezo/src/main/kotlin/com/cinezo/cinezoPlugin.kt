package com.cinezo

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CinezoPlugin : Plugin() {
    override fun load() {
        registerMainAPI(Cinezo())
    }
}