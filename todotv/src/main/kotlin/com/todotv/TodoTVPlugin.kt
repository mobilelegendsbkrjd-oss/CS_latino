package com.todotv

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TodoTVPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(TodoTV())
    }
}
