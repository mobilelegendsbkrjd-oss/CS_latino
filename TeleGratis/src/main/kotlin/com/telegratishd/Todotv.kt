package com.todotv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class TodoTV : MainAPI() {
    override var name = "Todo TV"
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

    data class WebChannel(
        val name: String,
        val image: String?,
        val provider: String,
        val sources: List<ChannelSource>
    )

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

    private var channelsCache: List<WebChannel> = emptyList()
    private var lastFetch = 0L
    private val cacheMs = 2 * 60 * 60 * 1000L

    override val mainPage = mainPageOf(
        "home" to "Todo TV"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val channels = getAllChannels()

        val lists = mutableListOf<HomePageList>()

        lists.add(HomePageList("⭐ Todos los Canales", channels.toCards(), true))

        addCategory(lists, "⚽ Deportes", channels) {
            hasAny(it, "sport", "espn", "fox", "tudn", "sky", "bein", "dazn", "futbol", "fútbol", "directv", "tyc")
        }

        addCategory(lists, "📰 Noticias", channels) {
            hasAny(it, "news", "noticia", "cnn", "foro", "adn", "milenio", "nmas", "24h", "dw", "france 24")
        }

        addCategory(lists, "🎬 Cine y Series", channels) {
            hasAny(it, "hbo", "max", "cine", "warner", "star", "tnt", "space", "fx", "film", "movie", "paramount", "sony")
        }

        addCategory(lists, "👶 Infantil", channels) {
            hasAny(it, "cartoon", "disney", "nick", "boomerang", "toon", "kids", "infantil", "discovery kids")
        }

        addCategory(lists, "🇲🇽 México", channels) {
            hasAny(it, "azteca", "canal 5", "canal5", "las estrellas", "galavision", "galavisión", "foro", "imagen", "once", "mex")
        }

        addCategory(lists, "🌎 Internacional", channels) {
            hasAny(it, "arg", "chile", "colombia", "peru", "perú", "españa", "usa", "latino", "internacional")
        }

        TodoTVProviders.providers.forEach { provider ->
            val providerChannels = channels.filter { it.provider == provider.name }
            if (providerChannels.isNotEmpty()) {
                lists.add(HomePageList("📡 ${provider.name}", providerChannels.toCards(), true))
            }
        }

        return newHomePageResponse(lists, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return getAllChannels()
            .filter { it.name.contains(query, ignoreCase = true) }
            .toCards()
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val json = JSONObject(url)
        val name = json.optString("name")
        val image = json.optString("image").ifBlank { null }
        val sources = json.optJSONArray("sources") ?: JSONArray()

        return newLiveStreamLoadResponse(
            name = name,
            url = url,
            dataUrl = url
        ) {
            posterUrl = image
            plot = "TV en vivo - ${sources.length()} servidor(es)"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val json = JSONObject(data)
        val channelName = json.optString("name")
        val sources = json.optJSONArray("sources") ?: return false

        var added = false
        var index = 1

        for (i in 0 until sources.length()) {
            val item = sources.optJSONObject(i) ?: continue
            val source = parseSource(item) ?: continue

            val expanded = TodoTVResolver.expandServers(source)
            val finalSources = if (expanded.isNotEmpty()) expanded else listOf(source)

            finalSources.forEach { src ->
                val ok = TodoTVResolver.resolve(
                    channelName = channelName,
                    sourceIndex = index,
                    source = src,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
                if (ok) added = true
                index++
            }
        }

        return added
    }

    private suspend fun getAllChannels(): List<WebChannel> {
        val now = System.currentTimeMillis()
        if (channelsCache.isNotEmpty() && now - lastFetch < cacheMs) return channelsCache

        val fetched = coroutineScope {
            TodoTVProviders.providers.map { provider ->
                async { fetchProvider(provider) }
            }.awaitAll().flatten()
        }

        val merged = mergeChannels(fetched)

        if (merged.isNotEmpty()) {
            channelsCache = merged
            lastFetch = now
        }

        return channelsCache
    }

    private suspend fun fetchProvider(provider: TodoProvider): List<WebChannel> {
        return try {
            val html = app.get(
                provider.baseUrl,
                referer = provider.baseUrl,
                headers = mapOf("User-Agent" to userAgent)
            ).text

            val doc = Jsoup.parse(html, provider.baseUrl)
            parseChannels(doc, provider)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseChannels(doc: Document, provider: TodoProvider): List<WebChannel> {
        val results = mutableListOf<WebChannel>()

        doc.select("script").forEach { script ->
            val data = script.data()

            if (data.contains("homeChannels", true) || data.contains("const channels", true)) {
                try {
                    val htmlInsideScript = data.substringAfter("`").substringBeforeLast("`")
                    if (htmlInsideScript.length > 100) {
                        val scriptDoc = Jsoup.parse(htmlInsideScript, provider.baseUrl)
                        results.addAll(parseChannelLinks(scriptDoc, provider))
                    }
                } catch (_: Exception) {
                }
            }
        }

        results.addAll(parseChannelLinks(doc, provider))

        return results.distinctBy { it.sources.firstOrNull()?.url ?: it.name }
    }

    private fun parseChannelLinks(doc: Document, provider: TodoProvider): List<WebChannel> {
        val results = mutableListOf<WebChannel>()

        doc.select("a").forEach { a ->
            val link = a.attr("abs:href").ifBlank { a.attr("href") }.trim()
            val img = a.selectFirst("img")

            val title = a.attr("title").trim()
                .ifBlank { img?.attr("alt")?.trim() ?: "" }
                .ifBlank { a.text().trim() }

            val rawImg = img?.attr("data-src")
                ?.ifBlank { img.attr("data-lazy-src") }
                ?.ifBlank { img.attr("abs:src") }
                ?.ifBlank { img.attr("src") }
                ?.trim()

            if (!isValidChannel(link, title, provider.baseUrl)) return@forEach

            val finalUrl = fixUrl(link, provider.baseUrl)
            val finalImg = rawImg?.takeIf { it.isNotBlank() }?.let { fixUrl(it, provider.baseUrl) }

            results.add(
                WebChannel(
                    name = cleanDisplayName(title),
                    image = finalImg,
                    provider = provider.name,
                    sources = listOf(
                        ChannelSource(
                            name = "${cleanDisplayName(title)} - ${provider.name}",
                            url = finalUrl,
                            referer = provider.baseUrl,
                            userAgent = userAgent,
                            embed = true
                        )
                    )
                )
            )
        }

        return results
    }

    private fun mergeChannels(channels: List<WebChannel>): List<WebChannel> {
        val grouped = linkedMapOf<String, MutableList<WebChannel>>()

        channels.forEach { channel ->
            grouped.getOrPut(normalizeName(channel.name)) { mutableListOf() }.add(channel)
        }

        return grouped.map { (_, items) ->
            val first = items.first()
            WebChannel(
                name = first.name,
                image = first.image ?: items.firstNotNullOfOrNull { it.image },
                provider = first.provider,
                sources = items.flatMap { it.sources }.distinctBy { it.url }
            )
        }
    }

    private fun List<WebChannel>.toCards(): List<SearchResponse> {
        return this.map { channel ->
            val arr = JSONArray()
            channel.sources.forEach { arr.put(sourceToJson(it)) }

            val dataJson = JSONObject().apply {
                put("name", channel.name)
                put("image", channel.image ?: "")
                put("sources", arr)
            }.toString()

            newLiveSearchResponse(
                name = channel.name,
                url = dataJson,
                type = TvType.Live
            ) {
                posterUrl = channel.image
            }
        }
    }

    private fun addCategory(
        lists: MutableList<HomePageList>,
        title: String,
        channels: List<WebChannel>,
        filter: (String) -> Boolean
    ) {
        val list = channels.filter { filter(it.name.lowercase()) }
        if (list.isNotEmpty()) {
            lists.add(HomePageList(title, list.toCards(), true))
        }
    }

    private fun hasAny(text: String, vararg keys: String): Boolean {
        return keys.any { text.contains(it, true) }
    }

    private fun parseSource(json: JSONObject): ChannelSource? {
        val url = json.optString("url").trim()
        if (url.isBlank()) return null

        return ChannelSource(
            name = json.optString("name").trim().ifBlank { "Servidor" },
            url = url,
            referer = json.optString("referer").trim().ifBlank { null },
            userAgent = json.optString("userAgent").trim().ifBlank { userAgent },
            embed = json.optBoolean("embed", true)
        )
    }

    private fun sourceToJson(source: ChannelSource): JSONObject {
        return JSONObject().apply {
            put("name", source.name)
            put("url", source.url)
            put("referer", source.referer ?: "")
            put("userAgent", source.userAgent ?: userAgent)
            put("embed", source.embed)
        }
    }

    private fun isValidChannel(link: String, title: String, baseUrl: String): Boolean {
        val lowerLink = link.lowercase()
        val lowerTitle = title.lowercase()
        val cleanLink = link.trim().removeSuffix("/")
        val cleanBase = baseUrl.trim().removeSuffix("/")

        return link.isNotBlank() &&
                title.isNotBlank() &&
                (link.startsWith(baseUrl) || !link.startsWith("http")) &&
                cleanLink != cleanBase &&
                !lowerLink.contains("linktre.online") &&
                !lowerLink.contains("paypal") &&
                !lowerLink.contains("telegram") &&
                !lowerLink.contains("whatsapp") &&
                !lowerLink.contains("/category/") &&
                !lowerLink.contains("/tag/") &&
                !lowerTitle.contains("telegram") &&
                !lowerTitle.contains("soporte") &&
                !lowerTitle.contains("apoya") &&
                !lowerTitle.contains("donar")
    }

    private fun cleanDisplayName(name: String): String {
        return name
            .replace("📶", "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun normalizeName(name: String): String {
        return cleanDisplayName(name)
            .lowercase()
            .replace(Regex("""\b(stream|server|servidor|backup|opcion|opción|hd|fhd|sd|tv|canal)\b"""), "")
            .replace(Regex("""\b\d+\b"""), "")
            .replace(Regex("""[^a-z0-9áéíóúñ]+"""), "")
            .trim()
    }

    private fun fixUrl(url: String, baseUrl: String): String {
        val clean = url.trim().replace("\\/", "/").replace("&amp;", "&")

        return when {
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("http") -> clean
            clean.startsWith("/") -> baseUrl.removeSuffix("/") + clean
            else -> baseUrl.removeSuffix("/") + "/" + clean
        }
    }
}
