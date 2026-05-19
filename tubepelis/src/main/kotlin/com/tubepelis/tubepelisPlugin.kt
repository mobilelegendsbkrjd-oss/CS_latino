package com.tubepelis

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class tubepelisPlugin : Plugin() {
    override fun load(context: Context) {
        // Register main API
        registerMainAPI(tubepelis())
    }
}