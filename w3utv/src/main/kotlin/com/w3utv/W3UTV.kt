package com.w3utv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject

class W3UTV : MainAPI() {
    override var name = "TV Web"
    override var mainUrl = "https://www.tvporinternet2.com"
    override var lang = "es"
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Live)

    data class ChannelSource(
        val name: String,
        val url: String,
        val referer: String?,
        val userAgent: String?,
        val embed: Boolean
    )

    data class ChannelData(
        val name: String,
        val image: String?,
        val sources: List<ChannelSource>
    )

    override val mainPage = mainPageOf(
        "all" to "TV en vivo"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
    val channels = W3UWebSources.getWebChannels()

    val lists = mutableListOf<HomePageList>()

    fun addCategory(title: String, filter: (W3UWebSources.WebChannelResult) -> Boolean) {
        val cards = channels
            .filter(filter)
            .map { toCard(it) }
            .distinctBy { it.name }

        if (cards.isNotEmpty()) {
            lists.add(HomePageList(title, cards, isHorizontalImages = true))
        }
    }

    fun addProviderCategory(title: String, providerName: String) {
        val cards = channels
            .filter { channel ->
                channel.sources.any { source ->
                    source.name.contains(providerName, ignoreCase = true) ||
                    source.url.contains(providerUrlKey(providerName), ignoreCase = true)
                }
            }
            .map { toCard(it) }
            .distinctBy { it.name }

        if (cards.isNotEmpty()) {
            lists.add(HomePageList(title, cards, isHorizontalImages = true))
        }
    }

    // Categorías generales
    addCategory("🔥 Todos los canales") { true }
    addCategory("⚽ Deportes") { it.category == "Deportes" }
    addCategory("📰 Noticias") { it.category == "Noticias" }
    addCategory("🎬 Cine y Series") { it.category == "Cine y Series" }
    addCategory("👧 Infantiles") { it.category == "Infantiles" }
    addCategory("🎵 Música") { it.category == "Música" }
    addCategory("🌎 Internacionales") { it.category == "Internacionales" }
    addCategory("📺 Entretenimiento") { it.category == "Entretenimiento" }

    // Categorías por página
    addProviderCategory("🌐 TvporinternetHD", "TvporinternetHD")
    addProviderCategory("🌐 Tv Libre Futbol", "Tv Libre Futbol")
    addProviderCategory("🌐 CableVisionHD", "CableVisionHD")
    addProviderCategory("🌐 Teveplus", "Teveplus")
    addProviderCategory("🌐 Telegratis", "Telegratis")

    return newHomePageResponse(lists, false)
}

private fun providerUrlKey(providerName: String): String {
    return when (providerName.lowercase()) {
        "tvporinternethd" -> "tvporinternet2.com"
        "tv libre futbol" -> "librefutbol2.com"
        "cablevisionhd" -> "cablevisionhd.com"
        "teveplus" -> "tvplusgratis2.com"
        "telegratis" -> "telegratishd.com"
        else -> providerName.lowercase()
    }
}

    override suspend fun search(query: String): List<SearchResponse> {
        return W3UWebSources.getWebChannels()
            .filter { it.name.contains(query, ignoreCase = true) }
            .map { toCard(it) }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val data = parseChannelData(url)

        return newLiveStreamLoadResponse(
            name = data.name,
            url = url,
            dataUrl = url
        ) {
            this.posterUrl = data.image
            this.plot = "Canal en vivo - ${data.sources.size} servidor(es)"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val channel = parseChannelData(data)
        var added = false
        var index = 1

        for (source in channel.sources) {
            val expanded = W3UWebSources.expandWebSource(source)
            val finalSources = if (expanded.isNotEmpty()) expanded else listOf(source)

            for (finalSource in finalSources) {
                val ok = W3UResolver.resolve(
                    channelName = channel.name,
                    sourceIndex = index,
                    source = finalSource,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )

                if (ok) added = true
                index++
            }
        }

        return added
    }

    private fun toCard(channel: W3UWebSources.WebChannelResult): SearchResponse {
        val sources = JSONArray()
        channel.sources.forEach { sources.put(sourceToJson(it)) }

        val dataJson = JSONObject().apply {
            put("name", channel.name)
            put("image", channel.image ?: "")
            put("sources", sources)
        }.toString()

        return newLiveSearchResponse(
            name = channel.name,
            url = dataJson,
            type = TvType.Live
        ) {
            this.posterUrl = channel.image
        }
    }

    private fun parseChannelData(data: String): ChannelData {
        val json = JSONObject(data)
        val sources = mutableListOf<ChannelSource>()
        val arr = json.optJSONArray("sources")

        if (arr != null) {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                parseSource(item)?.let { sources.add(it) }
            }
        } else {
            parseSource(json)?.let { sources.add(it) }
        }

        return ChannelData(
            name = json.optString("name").trim(),
            image = json.optString("image").trim().ifBlank { null },
            sources = sources
        )
    }

    private fun parseSource(json: JSONObject): ChannelSource? {
        val url = json.optString("url").trim()
        if (url.isBlank()) return null

        val userAgent = when {
            json.has("userAgent") -> json.optString("userAgent")
            json.has("UserAgent") -> json.optString("UserAgent")
            else -> ""
        }.trim().ifBlank { null }

        val embed = json.optBoolean("embed", false) || json.optBoolean("EMBED", false)

        return ChannelSource(
            name = json.optString("name").trim().ifBlank { "Servidor" },
            url = url,
            referer = json.optString("referer").trim().ifBlank { null },
            userAgent = userAgent,
            embed = embed
        )
    }

    private fun sourceToJson(source: ChannelSource): JSONObject {
        return JSONObject().apply {
            put("name", source.name)
            put("url", source.url)
            put("referer", source.referer ?: "")
            put("userAgent", source.userAgent ?: "")
            put("embed", source.embed)
        }
    }
}