package com.pelisplushd

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class PelisplushdPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Pelisplushd())
    }
}