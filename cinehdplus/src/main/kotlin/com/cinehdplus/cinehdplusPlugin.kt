package com.cinehdplus

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class CineHDPlusPlugin : Plugin() {
    override fun load(context: Context) {
        // Register main API
        registerMainAPI(CineHDPlus())
    }
}