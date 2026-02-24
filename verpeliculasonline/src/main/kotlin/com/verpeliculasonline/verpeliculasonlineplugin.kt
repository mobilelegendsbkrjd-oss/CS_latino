package com.verpeliculasonline

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class VerPeliculasOnlinePlugin : Plugin() {
    override fun load() {
        registerMainAPI(VerPeliculasOnline())
    }
}