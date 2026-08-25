package com.sololatino

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class SoloLatinoPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(SoloLatino())

        registerExtractorAPI(DoodExtractor())
        registerExtractorAPI(F75s())
        registerExtractorAPI(PlayHydrax())

        // VidHidePro
        registerExtractorAPI(DhtpreCom())
        registerExtractorAPI(DingtezuniCom())
        registerExtractorAPI(MinochinosExtractorV2())
        registerExtractorAPI(Ryderjet())
        registerExtractorAPI(VidHideHub())
        registerExtractorAPI(VidHidePro1())
        registerExtractorAPI(VidHidePro2())
        registerExtractorAPI(VidHidePro3())
        registerExtractorAPI(VidHidePro4())
        registerExtractorAPI(VidHidePro5())
        registerExtractorAPI(VidHidePro6())
        registerExtractorAPI(Smoothpre())
        registerExtractorAPI(Peytonepre())

        // Filemoon
        registerExtractorAPI(FileMoon2())
        registerExtractorAPI(FileMoonIn())
        registerExtractorAPI(FileMoonSx())
        registerExtractorAPI(Bysedikamoum())
    }
}