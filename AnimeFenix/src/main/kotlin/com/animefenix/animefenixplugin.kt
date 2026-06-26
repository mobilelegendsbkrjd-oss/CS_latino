package com.animefenix

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class animefenixplugin: Plugin() {
    override fun load(context: Context) {
        // All providers should be added in this manner. Please don't edit the providers list directly.
        registerMainAPI(Animefenix())
        registerExtractorAPI(Zilla())
        registerExtractorAPI(Animeav1upn())
        registerExtractorAPI(IronHentai())
        registerExtractorAPI(HqqExtractor())
    }
}