package com.tlnovelas

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class TlnovelasProvider : Plugin() {
    override fun load(context: Context) {
        // Registrar la API principal
        registerMainAPI(Tlnovelas())
        
        // Si en el futuro necesitas registrar extractores adicionales, puedes hacerlo aquí
        // registerExtractorAPI(MiExtractor())
    }
}