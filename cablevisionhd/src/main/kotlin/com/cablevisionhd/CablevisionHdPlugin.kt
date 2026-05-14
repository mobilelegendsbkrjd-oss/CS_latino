package com.cablevision

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CablevisionHdPlugin : Plugin() {

    override fun load(context: Context) {
        registerMainAPI(CablevisionHdProvider())
    }
}
