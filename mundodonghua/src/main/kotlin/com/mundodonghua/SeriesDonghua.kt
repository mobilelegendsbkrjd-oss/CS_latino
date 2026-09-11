package com.mundodonghua

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
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
        "$mainUrl/donghuas-finalizados" to "✅ Finalizadas"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}?pag=$page"
        val doc = app.get(url, timeout = 120, headers = siteHeaders).document
        val items = parseCards(doc).distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val doc = app.get(
            "$mainUrl/?s=$q",
            timeout = 90,
            headers = siteHeaders
        ).document
        return parseCards(doc).distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val fixed = fixUrl(url)
        val doc = app.get(fixed, timeout = 120, headers = siteHeaders).document

        val title = doc.selectFirst("h1, .title-serie, .sf.fc-dark.f-bold")?.text()?.trim()
            ?.replace(Regex("""\s*【.*?】\s*"""), " ")
            ?.replace(Regex("""\s*Episodio\s*\d+.*""", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?: doc.title().substringBefore("|").trim()

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst(".banner-serie")?.attr("style")
                ?.let { Regex("""url\(['"]?([^'")]+)['"]?\)""").find(it)?.groupValues?.getOrNull(1) }
                ?.let { fixUrl(it) }

        val plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val episodeLinks = doc.select("a[href*=-episodio-]").mapNotNull { a ->
            val href = a.attr("abs:href").ifBlank { fixUrl(a.attr("href")) }
            val text = a.text().trim()
            val num = Regex("""episodio-(\d+)""", RegexOption.IGNORE_CASE)
                .find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""(?:Ep(?:isodio)?\.?\s*)(\d+)""", RegexOption.IGNORE_CASE)
                    .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (href.isBlank() || num == null) null
            else newEpisode(href) {
                this.name = text.ifBlank { "Episodio $num" }
                this.episode = num
            }
        }.distinctBy { it.episode }.sortedBy { it.episode }

        if (episodeLinks.isNotEmpty()) {
            return newAnimeLoadResponse(title, fixed, TvType.Anime) {
                this.posterUrl = poster
                this.plot = plot
                addEpisodes(DubStatus.Subbed, episodeLinks)
            }
        }

        val epNum = Regex("""episodio-(\d+)""", RegexOption.IGNORE_CASE)
            .find(fixed)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1

        val seriesUrl = fixed
            .replace(Regex("""-episodio-\d+/?(?:#.*)?$""", RegexOption.IGNORE_CASE), "/")
            .removeSuffix("/")
            .let { if (it.endsWith(mainUrl)) fixed else "$it/" }

        return newAnimeLoadResponse(title, seriesUrl, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            addEpisodes(
                DubStatus.Subbed,
                listOf(
                    newEpisode(fixed) {
                        this.name = "Episodio $epNum"
                        this.episode = epNum
                    }
                )
            )
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

        val sources = linkedMapOf<String, String>()

        candidateTexts.forEach { text ->
            extractVideoMapSources(text).forEach { (platform, value) ->
                sources[platform] = value
            }

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

            Regex(""""asura"\s*:\s*"\\*"([A-Za-z0-9]{6,})\\*"""").find(text)?.groupValues?.getOrNull(1)?.let {
                sources.putIfAbsent("asura", it)
            }
        }

        val seenUrls = mutableSetOf<String>()

        fun normalizeUrl(u: String): String {
            return u.trim()
                .replace("\\/", "/")
                .substringBefore("#")
                .substringBefore("?")
                .lowercase()
                .removeSuffix("/")
        }

        val dedupeCallback: (ExtractorLink) -> Unit = { link ->
            val key = normalizeUrl(link.url)
            if (key.isNotBlank() && seenUrls.add(key)) {
                callback.invoke(link)
            }
        }

        sources.forEach { (platform, value) ->
            val ok = resolveSeriesSource(platform, value, episodeUrl, subtitleCallback, dedupeCallback)
            found = ok || found
        }

        if (!found) {
            MundoHostResolver.extractUrls(html).forEach { raw ->
                val clean = raw.replace("\\/", "/").replace("&amp;", "&")
                if (MundoHostResolver.isVideoHost(clean) ||
                    clean.contains("dailymotion", true) ||
                    clean.contains("ok.ru", true) ||
                    clean.contains("rumble", true) ||
                    clean.contains("voe.", true)
                ) {
                    found = MundoHostResolver.resolve(clean, episodeUrl, subtitleCallback, dedupeCallback) || found
                }
            }
        }

        return found
    }

    private fun extractVideoMapSources(text: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val normalized = text
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")

        val platforms = listOf("asura", "skadi", "fembed", "tape")
        for (p in platforms) {
            val rx = Regex(""""$p"\s*:\s*"((?:\\.|[^"\\])*)"""", RegexOption.IGNORE_CASE)
            val m = rx.find(normalized) ?: rx.find(text) ?: continue
            var value = m.groupValues[1]
                .replace("\\/", "/")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .trim()

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

        val targets = ArrayList<String>()

        when {
            platform.equals("asura", true) -> {
                if (value.startsWith("http", true)) {
                    targets.add(value)
                    Regex("""dailymotion\.com/(?:embed/)?video/([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
                        .find(value)?.groupValues?.getOrNull(1)?.let {
                            targets.add("https://www.dailymotion.com/embed/video/$it")
                            targets.add("https://www.dailymotion.com/video/$it")
                        }
                } else {
                    targets.add("https://www.dailymotion.com/embed/video/$value")
                    targets.add("https://www.dailymotion.com/video/$value")
                }
            }
            value.startsWith("http", true) -> targets.add(value)
            else -> return false
        }

        var ok = false
        for (url in targets.distinct()) {
            if (MundoHostResolver.resolve(url, referer, subtitleCallback, callback)) {
                ok = true
                continue
            }
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
                if (pos >= 0) j += pos * pow
                if (idx > 0) {
                    val next = pow * e
                    if (next <= 0) break
                    pow = next
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
            i++

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

    private fun parseCards(doc: Document): List<SearchResponse> {
        val items = ArrayList<SearchResponse>()

        val cards = doc.select(
            ".item, .angled-img, .col-lg-3, .col-md-3, .col-sm-4, article, .card, a[href]"
        )

        for (el in cards) {
            val element: Element = el

            val a: Element? = if (element.tagName() == "a") {
                element
            } else {
                element.selectFirst("a[href]")
            }
            if (a == null) continue

            var href: String = a.attr("abs:href")
            if (href.isBlank()) href = a.attr("href")
            if (href.isBlank()) continue
            href = fixUrl(href)

            if (href.contains("-episodio-", ignoreCase = true)) continue
            if (!href.startsWith(mainUrl)) continue
            if (href == mainUrl || href == "$mainUrl/") continue
            if (href.contains("/css/") || href.contains("/js/") || href.contains("/imagenes-")) continue

            val titleEl: Element? = element.selectFirst("h5, h4, h3, .title, .bottom-info h5, .nombre")
            var title: String = titleEl?.text()?.trim().orEmpty()
            if (title.isBlank()) title = a.attr("title").trim()
            if (title.isBlank()) title = a.text().trim()
            if (title.isBlank() || title.length < 2) continue

            val img: Element? = element.selectFirst("img")
            var poster: String? = null
            if (img != null) {
                poster = img.attr("abs:src")
                if (poster.isNullOrBlank()) poster = img.attr("abs:data-src")
                if (poster.isNullOrBlank()) poster = img.attr("data-src")
                if (poster.isNullOrBlank()) poster = img.attr("src")
                if (!poster.isNullOrBlank()) poster = fixUrl(poster)
                else poster = null
            }

            items.add(
                newAnimeSearchResponse(cleanTitle(title), href, TvType.Anime) {
                    this.posterUrl = poster
                }
            )
        }

        return items.distinctBy { it.url }
    }

    private fun cleanTitle(title: String): String {
        return title
            .replace(Regex("""\s*【.*?】\s*"""), " ")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
    }
}