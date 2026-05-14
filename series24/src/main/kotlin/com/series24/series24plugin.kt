package com.series24

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class Series24Plugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Series24())
    }
}
