package com.tubepelis

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TubePelisPlugin : Plugin() {
    override fun load() {
        registerMainAPI(TubePelis())
    }
}