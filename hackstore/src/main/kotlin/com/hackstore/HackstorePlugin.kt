package com.hackstore

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class HackStorePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(HackStore())
    }
}