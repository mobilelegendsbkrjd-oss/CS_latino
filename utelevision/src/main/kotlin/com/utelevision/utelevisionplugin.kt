package com.utelevision

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class UTelevisionPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(UTelevision())
    }
}
