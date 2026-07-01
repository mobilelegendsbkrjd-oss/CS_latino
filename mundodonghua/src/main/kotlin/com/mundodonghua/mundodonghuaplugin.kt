package com.mundodonghua

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MundoDonghuaPlugin : Plugin() {
    override fun load() {
        registerMainAPI(MundoDonghua())

        registerExtractorAPI(BysekozeMundo())
        registerExtractorAPI(VidHideMundo())
        registerExtractorAPI(CallistaniseMundo())
        registerExtractorAPI(MDNemonicPlayerExtractor())
        registerExtractorAPI(MDPlayerExtractor())
    }
}
