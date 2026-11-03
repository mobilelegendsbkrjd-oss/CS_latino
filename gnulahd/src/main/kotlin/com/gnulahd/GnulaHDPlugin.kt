package com.gnulahd

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class GnulaHDPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(GnulaHD())
        gnulaExtractors.forEach { registerExtractorAPI(it) }
    }
}