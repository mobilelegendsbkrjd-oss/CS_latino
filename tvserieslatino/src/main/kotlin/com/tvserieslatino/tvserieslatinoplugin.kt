<<<<<<<< HEAD:tvserieslatino/src/main/kotlin/com/tvserieslatino/tvserieslatinoplugin.kt
package com.tvserieslatino
========
package com.idlix
>>>>>>>> upstream/master:IdlixProvider/src/main/kotlin/com/idlix/IdlixProviderPlugin.kt

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class TvSeriesLatinoPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(TvSeriesLatino())
    }
}