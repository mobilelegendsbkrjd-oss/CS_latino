package com.locopelis

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class LocoPelisPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(LocoPelis())
    }
}
