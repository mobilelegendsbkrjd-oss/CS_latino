package com.granpirata

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class GranPirataPlugin : Plugin() {
    override fun load(context: Context) {
        // Register main API
        registerMainAPI(GranPirata())
    }
}