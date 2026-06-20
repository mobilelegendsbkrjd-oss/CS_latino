package com.todotv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class TodoTV : MainAPI() {
    override var name = "TodoTV"
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
        val url: String,
        val image: String?,
        val provider: TodoProvider
    )

    data class ChannelData(
        val name: String,
        val image: String?,
        val sources: List<ChannelSource>
    )

    private val defaultUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

    override val mainPage = mainPageOf(
        "general" to "TV en vivo"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val allChannels = fetchAllChannels()
        val grouped = groupChannels(allChannels)

        val lists = mutableListOf<HomePageList>()

        fun addCategory(title: String, filter: (ChannelData) -> Boolean) {
            val cards = grouped.filter(filter).map { it.toCard() }
            if (cards.isNotEmpty()) {
                lists.add(HomePageList(title, cards, isHorizontalImages = true))
            }
        }

        addCategory("⭐ Todos los Canales") { true }
        addCategory("⚽ Deportes") {
            hasAny(it.name, listOf("sport", "sports", "espn", "fox", "tudn", "bein", "directv", "tyc", "gol", "liga", "futbol", "fútbol", "canal+", "azteca deportes"))
        }
        addCategory("📰 Noticias") {
            hasAny(it.name, listOf("news", "noticia", "cnn", "foro", "milenio", "adn", "24h", "nmas", "ñ", "dw", "bbc", "rt", "euronews"))
        }
        addCategory("🎬 Cine y Series") {
            hasAny(it.name, listOf("hbo", "max", "cine", "cinema", "warner", "star", "tnt", "film", "movie", "paramount", "universal", "fx", "sony", "golden"))
        }
        addCategory("👶 Infantil") {
            hasAny(it.name, listOf("cartoon", "disney", "nick", "toon", "boomerang", "kids", "infantil", "discovery kids", "baby", "pakapaka"))
        }
        addCategory("🇲🇽 México") {
            hasAny(it.name, listOf("azteca", "canal 5", "canal5", "las estrellas", "galavision", "galavisión", "foro", "imagen", "milenio", "unicable", "distrito comedia", "telehit", "adn40"))
        }
        addCategory("🌎 Internacional") {
            hasAny(it.name, listOf("latino", "argentina", "colombia", "chile", "peru", "perú", "ecuador", "uruguay", "españa", "usa", "internacional"))
        }

        TodoTVProviders.providers.forEach { provider ->
            val providerChannels = allChannels
                .filter { it.provider.name == provider.name }
                .map { channel ->
                    ChannelData(
                        name = channel.name,
                        image = channel.image,
                        sources = listOf(channel.toSource())
                    ).toCard()
                }

            if (providerChannels.isNotEmpty()) {
                lists.add(HomePageList("📡 ${provider.name}", providerChannels, isHorizontalImages = true))
            }
        }

        return newHomePageResponse(lists, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val grouped = groupChannels(fetchAllChannels())

        return grouped
            .filter { it.name.contains(query, ignoreCase = true) }
            .map { it.toCard() }
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
            val expanded = expandSource(source)
            val finalSources = if (expanded.isNotEmpty()) expanded else listOf(source)

            for (finalSource in finalSources) {
                val ok = TodoTVResolver.resolve(
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

    private suspend fun fetchAllChannels(): List<WebChannel> {
        val results = mutableListOf<WebChannel>()

        for (provider in TodoTVProviders.providers) {
            try {
                val html = app.get(
                    provider.baseUrl,
                    referer = provider.baseUrl,
                    headers = mapOf("User-Agent" to defaultUa)
                ).text

                val doc = Jsoup.parse(html, provider.baseUrl)
                results.addAll(parseChannels(doc, provider))
            } catch (_: Exception) {
            }
        }

        return results.distinctBy { normalizeName(it.name) + "|" + it.url }
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

                        scriptDoc.select("a").forEach { a ->
                            val link = a.attr("abs:href").ifBlank { a.attr("href") }
                            val img = a.selectFirst("img")

                            val title = a.text().trim()
                                .ifBlank { img?.attr("alt")?.trim() ?: "" }

                            val rawImg = img?.attr("abs:src")
                                ?.ifBlank { img.attr("src") }
                                ?.trim()

                            if (isValidChannel(link, title, provider.baseUrl)) {
                                results.add(
                                    WebChannel(
                                        name = title,
                                        url = fixUrl(link, provider.baseUrl),
                                        image = rawImg?.let { fixUrl(it, provider.baseUrl) },
                                        provider = provider
                                    )
                                )
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        doc.select("a:has(img), a").forEach { a ->
            val link = a.attr("abs:href").ifBlank { a.attr("href") }
            val img = a.selectFirst("img")

            val title = a.attr("title").trim()
                .ifBlank { img?.attr("alt")?.trim() ?: "" }
                .ifBlank { a.text().trim() }

            val rawImg = img?.attr("data-src")
                ?.ifBlank { img.attr("data-lazy-src") }
                ?.ifBlank { img.attr("abs:src") }
                ?.ifBlank { img.attr("src") }
                ?.trim()

            if (isValidChannel(link, title, provider.baseUrl)) {
                results.add(
                    WebChannel(
                        name = title,
                        url = fixUrl(link, provider.baseUrl),
                        image = rawImg?.let { fixUrl(it, provider.baseUrl) },
                        provider = provider
                    )
                )
            }
        }

        return results.distinctBy { it.url }
    }

    private suspend fun expandSource(source: ChannelSource): List<ChannelSource> {
        return try {
            val html = app.get(
                source.url,
                referer = source.referer ?: source.url,
                headers = mapOf("User-Agent" to (source.userAgent ?: defaultUa))
            ).text

            val doc = Jsoup.parse(html, source.url)
            val servers = mutableListOf<ChannelSource>()

            doc.select("a").forEach { a ->
                val text = a.text().trim()
                val href = a.attr("abs:href").ifBlank { a.attr("href") }.trim()

                if (href.isNotBlank() && isValidServerText(text) && isValidServerUrl(href)) {
                    servers.add(
                        ChannelSource(
                            name = "${source.name} - ${text.ifBlank { "Servidor" }}",
                            url = fixUrl(href, source.url),
                            referer = source.url,
                            userAgent = source.userAgent ?: defaultUa,
                            embed = true
                        )
                    )
                }
            }

            if (servers.isEmpty() && doc.select("iframe").isNotEmpty()) {
                servers.add(
                    ChannelSource(
                        name = "${source.name} - Reproductor Automático",
                        url = source.url,
                        referer = source.referer,
                        userAgent = source.userAgent ?: defaultUa,
                        embed = true
                    )
                )
            }

            servers.distinctBy { it.url }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun groupChannels(channels: List<WebChannel>): List<ChannelData> {
        val grouped = linkedMapOf<String, MutableList<WebChannel>>()

        channels.forEach { channel ->
            val key = normalizeName(channel.name)
            grouped.getOrPut(key) { mutableListOf() }.add(channel)
        }

        return grouped.map { (_, items) ->
            val first = items.first()
            ChannelData(
                name = cleanDisplayName(first.name),
                image = first.image,
                sources = items.map { it.toSource() }
            )
        }.sortedBy { it.name.lowercase() }
    }

    private fun WebChannel.toSource(): ChannelSource {
        return ChannelSource(
            name = "${cleanDisplayName(name)} - ${provider.name}",
            url = url,
            referer = provider.baseUrl,
            userAgent = defaultUa,
            embed = true
        )
    }

    private fun ChannelData.toCard(): SearchResponse {
        val arr = JSONArray()
        sources.forEach { arr.put(sourceToJson(it)) }

        val dataJson = JSONObject().apply {
            put("name", name)
            put("image", image ?: "")
            put("sources", arr)
        }.toString()

        return newLiveSearchResponse(
            name = name,
            url = dataJson,
            type = TvType.Live
        ) {
            this.posterUrl = image
        }
    }

    private fun parseChannelData(data: String): ChannelData {
        val json = JSONObject(data)
        val arr = json.optJSONArray("sources") ?: JSONArray()
        val sources = mutableListOf<ChannelSource>()

        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            parseSource(item)?.let { sources.add(it) }
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

        return ChannelSource(
            name = json.optString("name").trim().ifBlank { "Servidor" },
            url = url,
            referer = json.optString("referer").trim().ifBlank { null },
            userAgent = json.optString("userAgent").trim().ifBlank { null },
            embed = json.optBoolean("embed", true)
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

    private fun isValidChannel(link: String, title: String, baseUrl: String): Boolean {
        val cleanLink = link.trim().removeSuffix("/")
        val cleanBase = baseUrl.removeSuffix("/")
        val lowerLink = link.lowercase()
        val lowerTitle = title.lowercase()

        return link.isNotBlank() &&
                title.isNotBlank() &&
                (link.startsWith(baseUrl) || !link.startsWith("http")) &&
                cleanLink != cleanBase &&
                !lowerLink.contains("linktre.online") &&
                !lowerLink.contains("paypal.com") &&
                !lowerLink.contains("/category/") &&
                !lowerLink.contains("/tag/") &&
                !lowerTitle.contains("telegram") &&
                !lowerTitle.contains("soporte") &&
                !lowerTitle.contains("apoya") &&
                !lowerTitle.contains("donar")
    }

    private fun isValidServerText(text: String): Boolean {
        val lower = text.lowercase()

        return lower.contains("opción") ||
                lower.contains("opcion") ||
                lower.contains("servidor") ||
                lower.contains("server") ||
                lower.contains("fhd") ||
                lower.contains("hd") ||
                lower.contains("ver") ||
                lower.contains("reproducir")
    }

    private fun isValidServerUrl(url: String): Boolean {
        val lower = url.lowercase()

        return url.isNotBlank() &&
                !lower.contains("paypal") &&
                !lower.contains("telegram") &&
                !lower.contains("whatsapp") &&
                !lower.contains("facebook") &&
                !lower.contains("instagram") &&
                !lower.contains("twitter") &&
                !lower.contains("linktre.online")
    }

    private fun hasAny(text: String, keys: List<String>): Boolean {
        return keys.any { text.contains(it, ignoreCase = true) }
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
            .replace(Regex("""\b(stream|server|servidor|backup|opcion|opción|hd|fhd|sd|online|en vivo|vivo)\b"""), "")
            .replace(Regex("""\b\d+\b"""), "")
            .replace(Regex("""[^a-z0-9áéíóúñ]+"""), "")
            .trim()
    }

    private fun fixUrl(url: String, baseUrl: String): String {
        val clean = url.trim()
            .replace("\\/", "/")
            .replace("&amp;", "&")

        return when {
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("http") -> clean
            clean.startsWith("/") -> baseUrl.removeSuffix("/") + clean
            else -> baseUrl.removeSuffix("/") + "/" + clean
        }
    }
}
