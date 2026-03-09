package com.SoloLatino

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class SoloLatinoProvider : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(SoloLatino())
    }
}
