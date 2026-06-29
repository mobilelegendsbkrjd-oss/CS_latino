package com.w3utv

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class W3UTVPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(W3UTV())
    }
}