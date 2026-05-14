package com.novelas360

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Novelas360Provider : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Novelas360())
        registerExtractorAPI(ExtractorNovelas360())
    }
}
