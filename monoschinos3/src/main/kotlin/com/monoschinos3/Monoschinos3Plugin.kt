package com.monoschinos3

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class Monoschinos3Plugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Monoschinos3())
    }
}