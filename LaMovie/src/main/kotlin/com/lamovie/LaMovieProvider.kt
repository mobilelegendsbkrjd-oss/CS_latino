package com.lamovie

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class LaMovieProvider : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(LaMovie())
    }
}
