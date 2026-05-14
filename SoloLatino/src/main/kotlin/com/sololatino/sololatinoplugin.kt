package com.sololatino

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class SoloLatinoPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(SoloLatino())
    }
}