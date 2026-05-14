package com.tioanime

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class TioAnimeProviderPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(TioAnimeProvider())
    }
}
