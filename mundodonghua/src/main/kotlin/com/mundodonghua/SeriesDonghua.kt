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

        val html = try {
            app.get(
                episodeUrl,
                timeout = 90,
                headers = siteHeaders + mapOf(
                    "Referer" to "$mainUrl/",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
            ).text
        } catch (_: Exception) {
            return false
        }

        // ---------- A) Desempaquetar TODOS los payloads tipo seriesdonghua ----------
        // Patrón real del sitio:
        // }("PACKED...", 11, "ARuPtcMnx", 37, 6, 59))
        val payloadRegex = Regex(
            """\}\("([^"]{80,})"\s*,\s*(\d+)\s*,\s*"([^"]{3,20})"\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\)\)"""
        )

        val candidateTexts = mutableListOf(html)

        payloadRegex.findAll(html).forEach { m ->
            val packed = m.groupValues[1]
            val nStr = m.groupValues[3]
            val t = m.groupValues[4].toIntOrNull() ?: return@forEach
            val e = m.groupValues[5].toIntOrNull() ?: return@forEach

            val unpacked = runCatching { unpackSeriesDonghua(packed, nStr, t, e) }.getOrNull()
            if (!unpacked.isNullOrBlank()) {
                candidateTexts.add(unpacked)
            }
        }

        // ---------- B) Sacar sources de cada texto ----------
        val sources = linkedMapOf<String, String>() // platform -> value

        candidateTexts.forEach { text ->
            extractVideoMapSources(text).forEach { (platform, value) ->
                sources[platform] = value
            }

            // por si el map no matchea bien, buscar hosts conocidos directo
            Regex(
                """https?://(?:www\.)?(?:dailymotion\.com/(?:embed/)?video/[A-Za-z0-9]+|ok\.ru/videoembed/\d+|rumble\.com/embed/[A-Za-z0-9_-]+[^"'\\s]*|voe\.(?:sx|to|ninja)/e/[A-Za-z0-9]+)""",
                RegexOption.IGNORE_CASE
            ).findAll(text.replace("\\/", "/")).forEach { m ->
                val u = m.value
                val key = when {
                    u.contains("dailymotion", true) -> "asura"
                    u.contains("ok.ru", true) -> "skadi"
                    u.contains("rumble", true) -> "fembed"
                    u.contains("voe.", true) -> "tape"
                    else -> u
                }
                sources.putIfAbsent(key, u)
            }

            // IDs de dailymotion sueltos tipo \"k41MaraEQ7nOxYJKyw6\"
            Regex(""""asura"\s*:\s*"\\*"([A-Za-z0-9]{6,})\\*"""").find(text)?.groupValues?.getOrNull(1)?.let {
                sources.putIfAbsent("asura", it)
            }
        }

        // ---------- C) Resolver cada source ----------
        sources.forEach { (platform, value) ->
            val ok = resolveSeriesSource(platform, value, episodeUrl, subtitleCallback, callback)
            found = ok || found
        }

        // ---------- D) Fallback genérico con MundoHostResolver ----------
        if (!found) {
            MundoHostResolver.extractUrls(html).forEach { raw ->
                val clean = raw.replace("\\/", "/").replace("&amp;", "&")
                if (MundoHostResolver.isVideoHost(clean) ||
                    clean.contains("dailymotion", true) ||
                    clean.contains("ok.ru", true) ||
                    clean.contains("rumble", true) ||
                    clean.contains("voe.", true)
                ) {
                    found = MundoHostResolver.resolve(clean, episodeUrl, subtitleCallback, callback) || found
                }
            }
        }

        return found
    }

    private fun extractVideoMapSources(text: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val normalized = text
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")

        // busca bloques asura/skadi/fembed/tape aunque estén hiper-escapados
        val platforms = listOf("asura", "skadi", "fembed", "tape")
        for (p in platforms) {
            val rx = Regex(
                """"$p"\s*:\s*"((?:\\.|[^"\\])*)"""",
                RegexOption.IGNORE_CASE
            )
            val m = rx.find(normalized) ?: rx.find(text) ?: continue
            var value = m.groupValues[1]
                .replace("\\/", "/")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .trim()

            // quitar comillas anidadas: "\"abc\"" -> abc
            repeat(3) {
                if (value.startsWith("\"") && value.endsWith("\"") && value.length >= 2) {
                    value = value.substring(1, value.length - 1).trim()
                }
            }
            value = value.trim('"', '\'', ' ', '\n', '\r', '\t')
            if (value.isNotBlank()) out.add(p to value)
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

        val targets = mutableListOf<String>()

        when {
            platform.equals("asura", true) -> {
                if (value.startsWith("http", true)) {
                    targets += value
                    // también normalizar a embed
                    Regex("""dailymotion\.com/(?:embed/)?video/([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
                        .find(value)?.groupValues?.getOrNull(1)?.let {
                            targets += "https://www.dailymotion.com/embed/video/$it"
                            targets += "https://www.dailymotion.com/video/$it"
                        }
                } else {
                    targets += "https://www.dailymotion.com/embed/video/$value"
                    targets += "https://www.dailymotion.com/video/$value"
                }
            }
            value.startsWith("http", true) -> targets += value
            else -> return false
        }

        var ok = false
        for (url in targets.distinct()) {
            // 1) tu resolver compartido
            if (MundoHostResolver.resolve(url, referer, subtitleCallback, callback)) {
                ok = true
                continue
            }
            // 2) extractores nativos de CloudStream
            try {
                loadExtractor(url, referer, subtitleCallback) { link ->
                    callback.invoke(link)
                    ok = true
                }
            } catch (_: Exception) {
            }
        }
        return ok
    }

    /** Packer de seriesdonghua: }("h", u, "n", t, e, r)) */
    private fun unpackSeriesDonghua(h: String, nStr: String, t: Int, e: Int): String {
        if (e <= 0 || e >= nStr.length) return ""

        val alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ+/"
        val table = alphabet.substring(0, e)
        val sep = nStr[e]

        fun unbase(str: String): Int {
            var j = 0
            var pow = 1
            for (idx in str.length - 1 downTo 0) {
                val pos = table.indexOf(str[idx])
                if (pos >= 0) {
                    j += pos * pow
                }
                // pow *= e, cuidando overflow absurdo
                if (idx > 0) {
                    val next = pow * e
                    pow = if (next > 0) next else break
                }
            }
            return j
        }

        val out = StringBuilder()
        var i = 0
        while (i < h.length) {
            val chunk = StringBuilder()
            while (i < h.length && h[i] != sep) {
                chunk.append(h[i])
                i++
            }
            i++ // skip sep

            var s = chunk.toString()
            for (j in nStr.indices) {
                s = s.replace(nStr[j].toString(), j.toString())
            }
            if (s.isEmpty()) continue

            val code = unbase(s) - t
            if (code in 0..0xFFFF) {
                out.append(code.toChar())
            }
        }
        return out.toString()
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