package com.gnulahd

import com.lagradost.cloudstream3.plugins.*

@CloudstreamPlugin
class GnulaHDPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(GnulaHD())
    }
}