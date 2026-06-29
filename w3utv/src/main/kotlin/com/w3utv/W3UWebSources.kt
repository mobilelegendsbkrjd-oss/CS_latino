package com.w3utv

import com.lagradost.cloudstream3.app
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

object W3UWebSources {

    private const val DEFAULT_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

    data class WebChannelResult(
        val name: String,
        val image: String?,
        val category: String,
        val sources: List<W3UTV.ChannelSource>,
        val url: String
    )

    private data class WebProvider(
        val name: String,
        val baseUrl: String
    )

    private data class WebChannel(
        val name: String,
        val url: String,
        val image: String?,
        val provider: WebProvider
    )

    private val providers = listOf(
    WebProvider("TvporinternetHD", "https://www.tvporinternet2.com"),
    WebProvider("Tv Libre Futbol", "https://www.librefutbol2.com"),
    WebProvider("CableVisionHD", "https://www.cablevisionhd.com"),
    WebProvider("Teveplus", "https://www.tvplusgratis2.com"),
    WebProvider("Telegratis", "https://www.telegratishd.com"),
)

    private var cache: List<WebChannelResult> = emptyList()
    private var lastFetch: Long = 0L
    private const val CACHE_MS = 2 * 60 * 60 * 1000L

    suspend fun getWebChannels(): List<WebChannelResult> {
        val now = System.currentTimeMillis()

        if (cache.isNotEmpty() && now - lastFetch < CACHE_MS) {
            return cache
        }

        val grouped = linkedMapOf<String, MutableList<WebChannel>>()

        providers.forEach { provider ->
            try {
                val html = app.get(
                    provider.baseUrl,
                    referer = provider.baseUrl,
                    headers = mapOf("User-Agent" to DEFAULT_UA)
                ).text

                val doc = Jsoup.parse(html, provider.baseUrl)
                val channels = parseChannels(doc, provider)

                channels.forEach { channel ->
                    grouped.getOrPut(normalizeName(channel.name)) { mutableListOf() }.add(channel)
                }
            } catch (_: Exception) {
            }
        }

        cache = grouped.mapNotNull { (_, channels) ->
            val first = channels.firstOrNull() ?: return@mapNotNull null
            val cleanName = cleanDisplayName(first.name)

            val sources = channels.map { channel ->
                W3UTV.ChannelSource(
                    name = "${cleanDisplayName(channel.name)} - ${channel.provider.name}",
                    url = channel.url,
                    referer = channel.provider.baseUrl,
                    userAgent = DEFAULT_UA,
                    embed = true
                )
            }.distinctBy { it.url }

            WebChannelResult(
                name = cleanName,
                image = first.image,
                category = detectCategory(cleanName),
                sources = sources,
                url = first.url
            )
        }.sortedBy { it.name }

        lastFetch = now
        return cache
    }

    suspend fun expandWebSource(source: W3UTV.ChannelSource): List<W3UTV.ChannelSource> {
        if (!isKnownWebSource(source.url)) return emptyList()

        return try {
            val base = getProviderBase(source.url) ?: source.referer ?: source.url

            val html = app.get(
                source.url,
                referer = source.referer ?: base,
                headers = mapOf("User-Agent" to (source.userAgent ?: DEFAULT_UA))
            ).text

            val doc = Jsoup.parse(html, source.url)
            val servers = mutableListOf<W3UTV.ChannelSource>()

            doc.select("a").forEach { a ->
                val text = a.text().trim()
                val href = a.attr("abs:href").ifBlank { a.attr("href") }.trim()

                if (
                    href.isNotBlank() &&
                    isValidServerText(text) &&
                    isValidServerUrl(href)
                ) {
                    servers.add(
                        W3UTV.ChannelSource(
                            name = "${source.name} - ${text.ifBlank { "Servidor" }}",
                            url = fixUrl(href, source.url),
                            referer = source.url,
                            userAgent = source.userAgent ?: DEFAULT_UA,
                            embed = true
                        )
                    )
                }
            }

            doc.select("iframe").forEachIndexed { index, iframe ->
                val src = iframe.attr("abs:src").ifBlank { iframe.attr("src") }.trim()

                if (src.isNotBlank()) {
                    servers.add(
                        W3UTV.ChannelSource(
                            name = "${source.name} - Iframe ${index + 1}",
                            url = fixUrl(src, source.url),
                            referer = source.url,
                            userAgent = source.userAgent ?: DEFAULT_UA,
                            embed = true
                        )
                    )
                }
            }

            if (servers.isEmpty()) {
                servers.add(
                    W3UTV.ChannelSource(
                        name = "${source.name} - Reproductor Automático",
                        url = source.url,
                        referer = source.referer ?: base,
                        userAgent = source.userAgent ?: DEFAULT_UA,
                        embed = true
                    )
                )
            }

            servers.distinctBy { it.url }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseChannels(doc: Document, provider: WebProvider): List<WebChannel> {
        val results = mutableListOf<WebChannel>()

        doc.select("script").forEach { script ->
            val data = script.data()

            if (data.contains("homeChannels", true) || data.contains("const channels", true)) {
                try {
                    val htmlInsideScript = data.substringAfter("`").substringBeforeLast("`")

                    if (htmlInsideScript.length > 100) {
                        val scriptDoc = Jsoup.parse(htmlInsideScript, provider.baseUrl)

                        scriptDoc.select("a").forEach { a ->
                            addChannelFromElement(a, provider, results)
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        doc.select("a:has(img), a").forEach { a ->
            addChannelFromElement(a, provider, results)
        }

        return results.distinctBy { it.url }
    }

    private fun addChannelFromElement(
        a: org.jsoup.nodes.Element,
        provider: WebProvider,
        results: MutableList<WebChannel>
    ) {
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

        if (isValidChannel(link, title, provider.baseUrl)) {
            results.add(
                WebChannel(
                    name = cleanDisplayName(title),
                    url = fixUrl(link, provider.baseUrl),
                    image = rawImg?.let { fixUrl(it, provider.baseUrl) },
                    provider = provider
                )
            )
        }
    }

    private fun detectCategory(name: String): String {
        val n = name.lowercase()

        return when {
            listOf("espn", "fox sport", "fox sports", "tudn", "bein", "sport", "deportes", "directv", "tyc", "gol", "liga", "champions", "futbol", "fútbol", "nba", "nfl", "mlb", "wwe", "ufc").any { n.contains(it) } ->
                "Deportes"

            listOf("cnn", "foro", "milenio", "azteca noticias", "noticias", "news", "24h", "adn", "nmas", "n+", "dw", "euronews").any { n.contains(it) } ->
                "Noticias"

            listOf("hbo", "cinemax", "cine", "warner", "tnt", "space", "star", "fx", "sony", "paramount", "universal", "studio", "film", "movie", "golden", "amc").any { n.contains(it) } ->
                "Cine y Series"

            listOf("cartoon", "disney", "nick", "boomerang", "tooncast", "dreamworks", "baby", "kids", "infantil", "discovery kids").any { n.contains(it) } ->
                "Infantiles"

            listOf("mtv", "music", "música", "musica", "telehit", "vh1", "bandamax", "trace", "htv").any { n.contains(it) } ->
                "Música"

            listOf("españa", "spain", "argentina", "chile", "colombia", "peru", "perú", "venezuela", "usa", "latino", "internacional").any { n.contains(it) } ->
                "Internacionales"

            else -> "Entretenimiento"
        }
    }

    private fun isKnownWebSource(url: String): Boolean {
        return providers.any { url.startsWith(it.baseUrl, true) }
    }

    private fun getProviderBase(url: String): String? {
        return providers.firstOrNull { url.startsWith(it.baseUrl, true) }?.baseUrl
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
                !lowerTitle.contains("donar") &&
                !lowerTitle.contains("reportar")
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
                lower.contains("reproducir") ||
                lower.contains("player")
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

    private fun cleanDisplayName(name: String): String {
        return name
            .replace("📶", "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun normalizeName(name: String): String {
        return cleanDisplayName(name)
            .lowercase()
            .replace(Regex("""\b(stream|server|servidor|backup|opcion|opción|hd|fhd|sd|mx|lat|latino)\b"""), "")
            .replace(Regex("""\b\d+\b"""), "")
            .replace(Regex("""[^a-z0-9áéíóúñ]+"""), "")
            .trim()
    }
}