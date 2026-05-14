package lacartoons

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class LACartoonsPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(LACartoons())
    }
}
