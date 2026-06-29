package com.tioplus

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TioPlusPlugin : Plugin() {
    override fun load() {
        registerMainAPI(TioPlus())
    }
}