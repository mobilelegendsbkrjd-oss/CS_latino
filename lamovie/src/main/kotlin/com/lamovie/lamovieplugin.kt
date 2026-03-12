package com.lamovie

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class LaMoviePlugin: Plugin() {
    override fun load() {
        registerMainAPI(LaMovie())
    }
}