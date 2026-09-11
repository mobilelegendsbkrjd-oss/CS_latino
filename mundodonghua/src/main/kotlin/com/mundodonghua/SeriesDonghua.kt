package com.mundodonghua

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class SeriesDonghua : MainAPI() {
    override var mainUrl = "https://seriesdonghua.com"
    override var name = "SeriesDonghua"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override var lang = "es"

    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val siteHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "es-419,es;q=0.9",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/todos-los-donghuas" to "🐉 Todos los Donghuas",
        "$mainUrl/donghuas-en-emision" to "📡 En emisión",
        "$mainUrl/donghuas-finalizados" to "✅ Finalizadas",
        "$mainUrl/episodios" to "🆕 Últimos episodios"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}?pag=$page"
        val doc = app.get(url, timeout = 90, headers = siteHeaders).document
        val items = parseCards(doc).distinctBy { it.url }
        return newHomePageResponse(
            listOf(HomePageList(request.name, items)),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val urls = listOf(
            "$mainUrl/?s=$q",
            "$mainUrl/busquedas/$q",
            "$mainUrl/busquedas/?donghua=$q"
        )
        val out = mutableListOf<SearchResponse>()
        for (url in urls) {
            try {
                val doc = app.get(url, timeout = 90, headers = siteHeaders).document
                out.addAll(parseCards(doc))
            } catch (_: Exception) {
            }
        }
        return out.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = fixUrl(url).trimEnd('/')
        val doc = app.get(fixedUrl, timeout = 90, headers = siteHeaders).document

        // Página de episodio suelta
        if (fixedUrl.contains("-episodio-")) {
            val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: doc.selectFirst("h1, h3")?.text()
                ?: "SeriesDonghua"
            val poster = fixUrlNull(
                doc.selectFirst("meta[property=og:image]")?.attr("content")
                    ?: doc.selectFirst("img")?.imgAttr()
            )
            return newMovieLoadResponse(title, fixedUrl, TvType.AnimeMovie, fixedUrl) {
                posterUrl = poster
            }
        }

        val rawTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.selectFirst("h1")?.text()
            ?: "SeriesDonghua"

        val title = rawTitle
            .replace(Regex("""\s*🥇.*"""), "")
            .replace(Regex("""\s*【.*?】"""), "")
            .replace(Regex("""\s*DONGHUA.*""", RegexOption.IGNORE_CASE), "")
            .replace("| SeriesDonghua", "")
            .trim()

        val poster = fixUrlNull(
            doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst(".fit-1 img, .img img, img")?.imgAttr()
        )

        val plot = doc.selectFirst("meta[name=description]")?.attr("content")
            ?: doc.selectFirst(".sf.fc-dark p, p")?.text()

        val episodes = doc.select("a[href*='-episodio-']")
            .mapNotNull { a ->
                val href = fixUrl(a.attr("abs:href").ifBlank { a.attr("href") })
                val epNum = Regex("""-episodio-(\d+)""", RegexOption.IGNORE_CASE)
                    .find(href)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?: return@mapNotNull null

                newEpisode(href) {
                    name = "Episodio $epNum"
                    episode = epNum
                    this.posterUrl = poster
                }
            }
            .distinctBy { it.episode }
            .sortedBy { it.episode }

        val isMovie = title.contains("Movie", true) ||
                doc.text().contains(Regex("Tipo.*Pel[ií]cula", RegexOption.IGNORE_CASE))

        if (episodes.isEmpty() || isMovie) {
            val movieData = if (fixedUrl.contains("-episodio-")) {
                fixedUrl
            } else {
                "$fixedUrl-episodio-1".let { u ->
                    if (u.startsWith("http")) u else "$mainUrl/${u.trimStart('/')}"
                }
            }
            return newMovieLoadResponse(title, fixedUrl, TvType.AnimeMovie, movieData) {
                posterUrl = poster
                this.plot = plot
            }
        }

        return newAnimeLoadResponse(title, fixedUrl, TvType.Anime) {
            posterUrl = poster
            this.plot = plot
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeUrl = fixUrl(data)
        var found = false

        val response = try {
            app.get(
                episodeUrl,
                timeout = 90,
                headers = siteHeaders + mapOf(
                    "Referer" to "$mainUrl/",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
            )
        } catch (_: Exception) {
            return false
        }

        val html = response.text
            .replace("\\/", "/")
            .replace("&amp;", "&")

        // 1) Intentar VIDEO_MAP plano (por si algún día lo dejan sin ofuscar)
        val plainSources = extractVideoMapSources(html)
        plainSources.forEach { (platform, value) ->
            found = resolveSeriesSource(platform, value, episodeUrl, subtitleCallback, callback) || found
        }

        // 2) Desempaquetar scripts tipo eval(function(h,u,n,t,e,r){...}("...",63,"...",39,5,20))
        val packedRegex = Regex(
            """eval\(function\(h,u,n,t,e,r\)\{.*?}\("((?:\\.|[^"\\])*)"\s*,\s*(\d+)\s*,\s*"((?:\\.|[^"\\])*)"\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\)\)""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

        packedRegex.findAll(html).forEach { m ->
            val packedData = m.groupValues[1]
            val nStr = m.groupValues[3]
            val t = m.groupValues[4].toIntOrNull() ?: return@forEach
            val e = m.groupValues[5].toIntOrNull() ?: return@forEach

            val unpacked = try {
                unpackSeriesDonghua(packedData, nStr, t, e)
            } catch (_: Exception) {
                null
            } ?: return@forEach

            // Dentro del unpack suele venir document.write('<script>const VIDEO_MAP_JSON=...');
            val sources = extractVideoMapSources(unpacked)
            sources.forEach { (platform, value) ->
                found = resolveSeriesSource(platform, value, episodeUrl, subtitleCallback, callback) || found
            }

            // También links sueltos del unpack
            MundoHostResolver.extractUrls(unpacked).forEach { raw ->
                val clean = raw.replace("\\/", "/").replace("&amp;", "&")
                if (MundoHostResolver.isVideoHost(clean) ||
                    clean.contains(".m3u8", true) ||
                    clean.contains(".mp4", true)
                ) {
                    found = MundoHostResolver.resolve(clean, episodeUrl, subtitleCallback, callback) || found
                }
            }
        }

        // 3) Fallback: iframes / urls del HTML
        response.document.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("abs:src").ifBlank { iframe.attr("src") }
            if (src.isNotBlank()) {
                found = MundoHostResolver.resolve(src, episodeUrl, subtitleCallback, callback) || found
            }
        }

        MundoHostResolver.extractUrls(html).forEach { raw ->
            val clean = raw.replace("\\/", "/").replace("&amp;", "&")
            if (MundoHostResolver.isVideoHost(clean) ||
                clean.contains(".m3u8", true) ||
                clean.contains(".mp4", true)
            ) {
                found = MundoHostResolver.resolve(clean, episodeUrl, subtitleCallback, callback) || found
            }
        }

        return found
    }

    private fun extractVideoMapSources(text: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()

        // const VIDEO_MAP_JSON={...}
        val mapMatch = Regex(
            """VIDEO_MAP_JSON\s*=\s*(\{.*?\})""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        ).find(text)

        val mapJson = mapMatch?.groupValues?.getOrNull(1) ?: text

        Regex(""""(\w+)"\s*:\s*"((?:\\.|[^"\\])*)"""")
            .findAll(mapJson)
            .forEach { m ->
                val platform = m.groupValues[1].lowercase()
                var value = m.groupValues[2]
                    .replace("\\\"", "\"")
                    .replace("\\/", "/")
                    .replace("\\\\", "\\")
                    .trim()

                // a veces viene "\"id\"" o "\"https://...\""
                while (value.startsWith("\"") && value.endsWith("\"") && value.length >= 2) {
                    value = value.removeSurrounding("\"")
                }
                value = value.trim()
                if (value.isNotBlank() && platform in setOf("asura", "skadi", "fembed", "tape")) {
                    out.add(platform to value)
                }
            }

        return out.distinctBy { it.first to it.second }
    }

    private suspend fun resolveSeriesSource(
        platform: String,
        value: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (value.isBlank()) return false

        return when {
            platform == "asura" -> {
                val dm = if (value.startsWith("http", true)) value
                else "https://www.dailymotion.com/embed/video/$value"
                MundoHostResolver.resolve(dm, referer, subtitleCallback, callback)
            }
            value.startsWith("http", true) -> {
                MundoHostResolver.resolve(value, referer, subtitleCallback, callback)
            }
            else -> false
        }
    }

    /**
     * Decoder del packer de seriesdonghua:
     * eval(function(h,u,n,t,e,r){...}(h, u, n, t, e, r))
     */
    private fun unpackSeriesDonghua(h: String, nStr: String, t: Int, e: Int): String {
        val alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ+/"
        fun unbase(str: String, radixFrom: Int): Int {
            val table = alphabet.substring(0, radixFrom)
            var j = 0
            str.reversed().forEachIndexed { idx, ch ->
                val pos = table.indexOf(ch)
                if (pos >= 0) j += pos * Math.pow(radixFrom.toDouble(), idx.toDouble()).toInt()
            }
            return j
        }

        if (e < 0 || e >= nStr.length) return ""
        val sep = nStr[e]
        val sb = StringBuilder()
        var i = 0
        while (i < h.length) {
            val chunk = StringBuilder()
            while (i < h.length && h[i] != sep) {
                chunk.append(h[i])
                i++
            }
            i++ // saltar separador

            var s = chunk.toString()
            for (j in nStr.indices) {
                s = s.replace(nStr[j].toString(), j.toString())
            }

            // s ahora es "dígitos" en base e (como string de chars del alphabet index)
            // En el JS original: unbase(s, e, 10) - t
            // Después del replace, s son caracteres '0','1',... que representan dígitos en base e
            val code = try {
                unbase(s, e) - t
            } catch (_: Exception) {
                continue
            }
            if (code in 0..0x10FFFF) {
                sb.append(code.toChar())
            }
        }
        return sb.toString()
    }

    private fun parseCards(element: Element): List<SearchResponse> {
        val seen = mutableSetOf<String>()

        return element.select("div.item, a.angled-img, .bg-carousel, a[href]")
            .mapNotNull { card ->
                val a = if (card.tagName() == "a") card else card.selectFirst("a[href]")
                val hrefRaw = a?.attr("abs:href")?.ifBlank { a.attr("href") } ?: return@mapNotNull null
                val href = fixUrl(hrefRaw)

                if (
                    href.contains("-episodio-") ||
                    href.contains("todos-los-donghuas") ||
                    href.contains("donghuas-en-emision") ||
                    href.contains("donghuas-finalizados") ||
                    href.contains("/episodios") ||
                    href == mainUrl ||
                    href == "$mainUrl/"
                ) return@mapNotNull null

                val path = href.removePrefix(mainUrl).trim('/')
                if (path.isBlank() || path.contains("/") || path.contains("?")) return@mapNotNull null
                if (!seen.add(href)) return@mapNotNull null

                val img = card.selectFirst("img") ?: a?.selectFirst("img")
                val poster = fixUrlNull(img?.imgAttr())

                val rawTitle = card.selectFirst("h5, .bg-titulo, .bottom-info h5")?.text()
                    ?: img?.attr("alt")
                    ?: a?.attr("title")
                    ?: path.replace("-", " ").replaceFirstChar { it.uppercase() }

                val title = cleanTitle(rawTitle)
                if (title.isBlank()) return@mapNotNull null

                newAnimeSearchResponse(title, href, TvType.Anime) {
                    posterUrl = poster
                    addDubStatus(DubStatus.Subbed)
                }
            }
    }

    private fun Element.imgAttr(): String {
        return attr("data-src")
            .ifBlank { attr("src") }
            .ifBlank { attr("abs:src") }
            .trim()
    }

    private fun cleanTitle(title: String): String {
        return title
            .replace("&amp;", "&")
            .replace("&#039;", "'")
            .replace(Regex("""Episodio\s*\d*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""Cap[ií]tulo\s*\d*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}