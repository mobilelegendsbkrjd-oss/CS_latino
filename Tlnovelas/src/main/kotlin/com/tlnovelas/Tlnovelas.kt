package com.tlnovelas

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

class Tlnovelas : MainAPI() {
    override var mainUrl = "https://ww2.tlnovelas.net"
    override var name = "Tlnovelas"
    override val hasMainPage = true
    override var lang = "es"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "" to "Últimos Capítulos",
        "gratis/telenovelas/" to "Ver Telenovelas"
    )

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) {
            "$mainUrl/${request.data}"
        } else {
            "$mainUrl/${request.data.removeSuffix("/")}/page/$page"
        }

        val document = app.get(url, headers = headers()).document

        val home = document.select(".vk-poster, .ani-card, .p-content, .ani-txt")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, home, true)
    }

    private fun headers(referer: String = mainUrl): Map<String, String> {
        return mapOf(
            "User-Agent" to ua,
            "Referer" to referer,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst(".ani-txt, .p-title, .vk-info p")?.text()
            ?: selectFirst("a")?.attr("title")
            ?: return null

        if (title.isBlank()) return null

        var href = selectFirst("a")?.attr("href") ?: return null
        val poster = selectFirst("img")?.attr("src")?.let { fixUrl(it) }

        if (href.contains("/ver/")) {
            val slug = href.removeSuffix("/")
                .substringAfterLast("/")
                .replace(Regex("(?i)-capitulo-\\d+|-capítulo-\\d+"), "")
            href = "$mainUrl/novela/$slug/"
        }

        return newTvSeriesSearchResponse(title.trim(), fixUrl(href), TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        return app.get("$mainUrl/buscar/?q=$q", headers = headers())
            .document
            .select(".vk-poster, .ani-card")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers()).document
        val novelaLink = document.selectFirst("a[href*='/novela/']")?.attr("href")?.let { fixUrl(it) }

        val finalDoc = if (url.contains("/ver/") && novelaLink != null) {
            app.get(novelaLink, headers = headers(url)).document
        } else {
            document
        }

        val title = finalDoc.selectFirst("h1.card-title, .vk-title-main, h1")
            ?.text()
            ?.replace(Regex("(?i)Capitulos de|Capítulos de|Ver"), "")
            ?.trim()
            ?: "Telenovela"

        val poster = finalDoc.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) }
            ?: finalDoc.selectFirst(".ani-img img")?.attr("src")?.let { fixUrl(it) }

        val episodes = finalDoc.select("a[href*='/ver/']")
            .mapNotNull {
                val epUrl = it.attr("href").takeIf { href -> href.isNotBlank() }?.let { href -> fixUrl(href) }
                    ?: return@mapNotNull null

                val epName = it.text()
                    .replace(title, "", true)
                    .replace(Regex("(?i)Ver|Capitulo|Capítulo"), "")
                    .trim()

                newEpisode(epUrl) {
                    name = if (epName.isBlank()) "Capítulo" else "Capítulo $epName"
                }
            }
            .distinctBy { it.data }
            .reversed()

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = finalDoc.selectFirst(".card-text, .ani-description")?.text()
        }
    }

    private fun decodeVideoUrl(encoded: String): String {
        return try {
            val parts = encoded.split("|")

            if (parts.size == 2 && parts[1].toIntOrNull() != null) {
                val encodedStr = parts[0]
                val key = parts[1].toInt()
                val decodedChars = mutableListOf<Char>()

                for (i in encodedStr.indices) {
                    decodedChars.add((encodedStr[i].code - key - i).toChar())
                }

                URLDecoder.decode(decodedChars.joinToString(""), "UTF-8")
            } else {
                encoded
            }
        } catch (_: Exception) {
            encoded
        }
    }

    private fun expandVideoLinks(rawInput: String): List<String> {
        val raw = rawInput.trim().trim('\'', '"')
        if (raw.isBlank()) return emptyList()

        // Caso real:
        // e[0]='DVIHJl1Av2ed|1'
        // Debe entrar como embed HQQ /e/, porque get_player_image/get_md5 usan ese referer.
        if (raw.matches(Regex("""[A-Za-z0-9]+(?:\|\d+)?"""))) {
            val id = raw.substringBefore("|")
            return listOf("https://hqq.to/e/$id")
        }

        val decoded = decodeVideoUrl(raw).trim()

        return when {
            decoded.startsWith("http") -> listOf(decoded)
            raw.startsWith("//") -> listOf("https:$raw")
            decoded.startsWith("//") -> listOf("https:$decoded")
            decoded.matches(Regex("""[A-Za-z0-9]+""")) -> listOf("https://hqq.to/e/$decoded")
            else -> emptyList()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = app.get(data, headers = headers(mainUrl))
        val response = res.text
        val document = res.document

        val videoLinks = linkedSetOf<String>()

        document.select("iframe[src]").forEach {
            val link = fixUrl(it.attr("src").trim())

            if (
                link.startsWith("http") &&
                !link.contains("google", true) &&
                !link.contains("facebook", true) &&
                !link.contains("ads", true)
            ) {
                videoLinks.add(link)
            }
        }

        Regex("""e\[(\d+)\]\s*=\s*['"]([^'"]+)['"]""")
            .findAll(response)
            .forEach {
                expandVideoLinks(it.groupValues[2]).forEach { fixed ->
                    videoLinks.add(fixed)
                }
            }

        Regex("""var\s+e\s*=\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
            .find(response)
            ?.groupValues
            ?.get(1)
            ?.split(",")
            ?.forEach {
                expandVideoLinks(it).forEach { fixed ->
                    videoLinks.add(fixed)
                }
            }

        Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""")
            .findAll(response)
            .forEach {
                videoLinks.add(it.groupValues[1].replace("\\/", "/"))
            }

        Regex("""(https?://(?:hqq\.to|waaw\.to|netu\.tv|bysejikuar\.com|f75s\.com|dooodster\.com|dood\.[^/"']+|iplayerhls\.com)/[^\s"'<>]+)""")
            .findAll(response)
            .forEach {
                videoLinks.add(it.groupValues[1].replace("\\/", "/"))
            }

        if (response.contains("eval(function(p,a,c,k,e")) {
            try {
                val unpacker = JsUnpacker(response)

                if (unpacker.detect()) {
                    unpacker.unpack()?.let { unpacked ->
                        Regex("""file\s*:\s*["'](https?://[^"']+)""")
                            .findAll(unpacked)
                            .forEach { m ->
                                videoLinks.add(m.groupValues[1].replace("\\/", "/"))
                            }

                        Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*["']([^"']+)""")
                            .findAll(unpacked)
                            .forEach { m ->
                                expandVideoLinks(m.groupValues[1]).forEach { fixed ->
                                    videoLinks.add(fixed)
                                }
                            }
                    }
                }
            } catch (_: Exception) {}
        }

        var success = false

        videoLinks.forEach { link ->
            try {
                val referer = if (
                    link.contains("hqq.to", true) ||
                    link.contains("waaw.to", true) ||
                    link.contains("netu.tv", true)
                ) {
                    mainUrl
                } else {
                    data
                }

                if (UniversalResolver.resolve(link, referer, subtitleCallback, callback)) {
                    success = true
                }
            } catch (_: Exception) {}
        }

        return success
    }
}