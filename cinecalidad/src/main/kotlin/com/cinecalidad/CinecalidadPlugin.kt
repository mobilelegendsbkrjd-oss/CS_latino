package cinecalidad

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class CinecalidadPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(CinecalidadProvider())
    }
}