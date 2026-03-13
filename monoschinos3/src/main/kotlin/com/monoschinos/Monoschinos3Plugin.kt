package com.monoschinos

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class MonoschinosPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Monoschinos())
    }
}