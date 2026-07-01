package com.mundodonghua

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.getAndUnpack
import org.jsoup.nodes.Element
import java.net.URLEncoder

class MundoDonghua : MainAPI() {
    override var mainUrl = "https://www.mundodonghua.com"
    override var name = "MundoDonghua"
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
        "$mainUrl/lista-donghuas" to "🐉 Donghuas",
        "$mainUrl/lista-episodios" to "🆕 Últimos episodios",
        "$mainUrl/lista-donghuas-emision" to "📡 En emisión",
        "$mainUrl/lista-donghuas-finalizados" to "✅ Finalizadas",
        "$mainUrl/lista-donghuas-recopilados" to "📦 Recopiladas"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}/$page"
        val doc = app.get(url, timeout = 120, headers = siteHeaders).document
        val items = parseCards(doc).distinctBy { it.url }

        return newHomePageResponse(
            listOf(HomePageList(request.name, items)),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()

        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val urls = listOf("$mainUrl/busquedas/$q", "$mainUrl/busquedas/?donghua=$q")
        val out = mutableListOf<SearchResponse>()

        for (url in urls) {
            try {
                val doc = app.get(url, timeout = 120, headers = siteHeaders).document
                out.addAll(parseCards(doc))
            } catch (_: Exception) {
            }
        }

        return out.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = fixUrl(url)
        val doc = app.get(fixedUrl, timeout = 120, headers = siteHeaders).document

        if (fixedUrl.contains("/ver/")) {
            val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: doc.selectFirst("h1.md-player-episode-title, h1")?.text()
                ?: "MundoDonghua"

            val poster = fixUrlNull(
                doc.selectFirst("meta[property=og:image]")?.attr("content")
                    ?: doc.selectFirst(".fit-1 img, .md-detail-poster img, img")?.imgAttr()
            )

            val plot = doc.selectFirst("meta[name=description]")?.attr("content")
                ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
                ?: doc.selectFirst("p.text-justify.fc-dark")?.text()

            return newMovieLoadResponse(title, fixedUrl, TvType.AnimeMovie, fixedUrl) {
                posterUrl = poster
                this.plot = plot
            }
        }

        val rawTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.selectFirst("h1.md-detail-title, h1, h2, .ls-title-serie")?.text()
            ?: "MundoDonghua"

        val baseTitle = rawTitle
            .replace(Regex("""\s+\d+\s*$"""), "")
            .replace(Regex("""\s+SP\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Especial\s*$""", RegexOption.IGNORE_CASE), "")
            .trim()

        val poster = fixUrlNull(
            doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst(".fit-1 img, .md-detail-poster img, img")?.imgAttr()
        )

        val background = fixUrlNull(doc.selectFirst(".md-detail-banner-bg img")?.imgAttr())

        val plot = doc.selectFirst("meta[name=description]")?.attr("content")
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst(".md-detail-synopsis, p.text-justify, p.text-justify.fc-dark")?.text()

        val slug = fixedUrl.substringAfter("/donghua/").substringBefore("?").trim('/')
        val baseSlug = normalizeSeasonBaseSlug(slug)

        val relatedUrls = mutableListOf<String>()
        relatedUrls.add("$mainUrl/donghua/$baseSlug")

        for (i in 2..12) {
            relatedUrls.add("$mainUrl/donghua/$baseSlug-$i")
            relatedUrls.add("$mainUrl/donghua/$baseSlug-$i-sp")
        }

        val allEpisodes = mutableListOf<Episode>()

        relatedUrls.distinct().forEach { seasonUrl ->
            try {
                val seasonDoc = app.get(seasonUrl, timeout = 120, headers = siteHeaders).document

                val seasonSlug = seasonUrl.substringAfter("/donghua/").substringBefore("?").trim('/')
                val seasonNumber = extractMundoSeasonNumber(seasonSlug)

                val seasonPoster = fixUrlNull(
                    seasonDoc.selectFirst("meta[property=og:image]")?.attr("content")
                        ?: seasonDoc.selectFirst(".fit-1 img, .md-detail-poster img, img")?.imgAttr()
                ) ?: poster

                val eps = seasonDoc.select("ul.donghua-list a[href], li.md-episode-item a[href], a.md-ep-link[href], a[href*='/ver/']")
                    .mapNotNull { ep ->
                        val epUrl = fixUrl(ep.attr("abs:href").ifBlank { ep.attr("href") })
                        if (!epUrl.contains("/ver/")) return@mapNotNull null

                        val epNum = Regex("""/ver/[^/]+/(\d+)""")
                            .find(epUrl)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()

                        newEpisode(epUrl) {
                            name = if (epNum != null) "Episodio $epNum" else ep.text().ifBlank { "Episodio" }
                            episode = epNum
                            season = seasonNumber
                            posterUrl = seasonPoster
                        }
                    }

                allEpisodes.addAll(eps)
            } catch (_: Exception) {
            }
        }

        val finalEpisodes = allEpisodes
            .distinctBy { it.data }
            .sortedWith(compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: 0 })

        val tvType = if (
            doc.select("div.row div.col-md-6.pl-15 p.fc-dark").text()
                .contains(Regex("Tipo.*Pel.cula", RegexOption.IGNORE_CASE))
        ) TvType.AnimeMovie else TvType.Anime

        if (finalEpisodes.isEmpty()) {
            return newMovieLoadResponse(baseTitle, fixedUrl, TvType.AnimeMovie, "$mainUrl/ver/$slug/1") {
                posterUrl = poster
                backgroundPosterUrl = background ?: poster
                this.plot = plot
            }
        }

        return newAnimeLoadResponse(baseTitle, fixedUrl, tvType) {
            posterUrl = poster
            backgroundPosterUrl = background ?: poster
            this.plot = plot
            addEpisodes(DubStatus.Subbed, finalEpisodes)
        }
    }
    private fun normalizeSeasonBaseSlug(slug: String): String {
        return slug
            .replace(Regex("""-\d+-sp$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""-\d+$"""), "")
            .trim('-')
    }

    private fun extractMundoSeasonNumber(slug: String): Int {
        Regex("""-(\d+)-sp$""", RegexOption.IGNORE_CASE)
            .find(slug)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.let { return it }

        Regex("""-(\d+)$""")
            .find(slug)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.let { return it }

        return 1
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeUrl = fixUrl(data.replace("ñ", "%C3%B1"))
        var found = false

        println("MD_DEBUG[LOADLINKS_START] data=$data")
        println("MD_DEBUG[EPISODE_URL] $episodeUrl")

        val response = try {
            app.get(
                episodeUrl,
                timeout = 120,
                headers = siteHeaders + mapOf(
                    "Referer" to episodeUrl,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
            )
        } catch (e: Exception) {
            println("MD_DEBUG[EPISODE_GET_ERROR] ${e.message}")
            return false
        }

        val html = response.text
            .replace("\\/", "/")
            .replace("&amp;", "&")

        val doc = response.document

        println("MD_DEBUG[EPISODE_HTML_LEN] ${html.length}")
        println("MD_DEBUG[SCRIPT_COUNT] ${doc.select("script").size}")

        applyViewIfExists(html, episodeUrl)

        val chunks = mutableListOf<String>()
        chunks.add(html)

        doc.select("script").forEachIndexed { index, script ->
            val scriptText = script.data().ifBlank { script.html() }

            println("MD_DEBUG[SCRIPT_" + index + "_LEN] " + scriptText.length)
            println("MD_DEBUG[SCRIPT_" + index + "_HAS_EVAL] " + scriptText.contains("eval(function", true))
            println("MD_DEBUG[SCRIPT_" + index + "_HAS_API] " + scriptText.contains("api_donghua", true))
            println("MD_DEBUG[SCRIPT_" + index + "_HAS_BYSE] " + scriptText.contains("bysekoze", true))
            println("MD_DEBUG[SCRIPT_" + index + "_HAS_VOE] " + scriptText.contains("voe", true))
            println("MD_DEBUG[SCRIPT_" + index + "_HAS_VIDHIDE] " + scriptText.contains("vidhide", true))

            if (scriptText.contains("eval(function", true)) {
                val linePacked = scriptText
                    .split("\n")
                    .map { it.trim() }
                    .filter { it.contains("eval(function", true) }

                val regexPacked = Regex(
                    """eval\(function\(p,a,c,k,e,.*?\)\)""",
                    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
                ).findAll(scriptText)
                    .map { it.value }
                    .toList()

                val packedList = (linePacked + regexPacked).distinct()

                println("MD_DEBUG[SCRIPT_" + index + "_PACKED_COUNT] " + packedList.size)

                packedList.forEachIndexed { pIndex, packed ->
                    println("MD_DEBUG[PACKED_" + index + "_" + pIndex + "_LEN] " + packed.length)

                    val customUnpack = try {
                        JsUnpacker(packed).unpack()
                    } catch (e: Exception) {
                        println("MD_DEBUG[CUSTOM_UNPACK_ERROR_${index}_$pIndex] ${e.message}")
                        null
                    }

                    println("MD_DEBUG[CUSTOM_UNPACK_${index}_$pIndex] len=${customUnpack?.length ?: 0}")

                    val csUnpack = try {
                        getAndUnpack(packed)
                    } catch (e: Exception) {
                        println("MD_DEBUG[CS_UNPACK_ERROR_${index}_$pIndex] ${e.message}")
                        null
                    }

                    println("MD_DEBUG[CS_UNPACK_${index}_$pIndex] len=${csUnpack?.length ?: 0}")

                    val unpack = customUnpack ?: csUnpack

                    if (!unpack.isNullOrBlank()) {
                        val clean = unpack.cleanText()
                        println("MD_DEBUG[UNPACK_" + index + "_" + pIndex + "_PREVIEW] " + clean.take(700))
                        chunks.add(clean)
                    } else {
                        println("MD_DEBUG[UNPACK_" + index + "_" + pIndex + "_EMPTY]")
                    }
                }

                val wholeCustom = try {
                    JsUnpacker(scriptText).unpack()
                } catch (e: Exception) {
                    println("MD_DEBUG[WHOLE_CUSTOM_UNPACK_ERROR_$index] ${e.message}")
                    null
                }

                if (!wholeCustom.isNullOrBlank()) {
                    println("MD_DEBUG[WHOLE_CUSTOM_UNPACK_$index] len=${wholeCustom.length}")
                    chunks.add(wholeCustom.cleanText())
                }
            }
        }

        val uniqueChunks = chunks
            .map { it.cleanText() }
            .distinct()

        println("MD_DEBUG[CHUNKS_TOTAL] ${uniqueChunks.size}")

        uniqueChunks.forEachIndexed { chunkIndex, chunk ->
            val urls = MundoHostResolver.extractUrls(chunk)
            val slugs = extractSlugs(chunk)

            println("MD_DEBUG[CHUNK_" + chunkIndex + "_LEN] " + chunk.length)
            println("MD_DEBUG[CHUNK_" + chunkIndex + "_URL_COUNT] " + urls.size)
            println("MD_DEBUG[CHUNK_" + chunkIndex + "_SLUG_COUNT] " + slugs.size)

            urls.forEach { println("MD_DEBUG[CHUNK_" + chunkIndex + "_URL] " + it) }
            slugs.forEach { println("MD_DEBUG[CHUNK_" + chunkIndex + "_SLUG] " + it) }

            Regex("""<iframe[^>]+src=['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
                .findAll(chunk)
                .map { it.groupValues[1].replace("\\/", "/").replace("&amp;", "&") }
                .distinct()
                .forEach { iframe ->
                    println("MD_DEBUG[IFRAME_FOUND] $iframe")
                    val ok = MundoHostResolver.resolve(
                        iframe,
                        episodeUrl,
                        subtitleCallback,
                        callback
                    )
                    println("MD_DEBUG[IFRAME_RESULT] ok=$ok iframe=$iframe")
                    found = ok || found
                }

            urls.forEach { raw ->
                val cleanUrl = raw.replace("\\/", "/").replace("&amp;", "&")
                val isVideo = MundoHostResolver.isVideoHost(cleanUrl)

                println("MD_DEBUG[TRY_URL] isVideo=$isVideo url=$cleanUrl")

                if (isVideo) {
                    val ok = MundoHostResolver.resolve(
                        cleanUrl,
                        episodeUrl,
                        subtitleCallback,
                        callback
                    )
                    println("MD_DEBUG[TRY_URL_RESULT] ok=$ok url=$cleanUrl")
                    found = ok || found
                }
            }

            slugs.forEach { slug ->
                println("MD_DEBUG[TRY_TAMAMO] $slug")
                val ok = MundoHostResolver.resolveTamamo(
                    slug,
                    episodeUrl,
                    subtitleCallback,
                    callback
                )
                println("MD_DEBUG[TRY_TAMAMO_RESULT] ok=$ok slug=$slug")
                found = ok || found
            }
        }

        println("MD_DEBUG[LOADLINKS_END] found=$found")
        return found
    }

    private suspend fun applyViewIfExists(html: String, episodeUrl: String) {
        try {
            val idMedia = Regex("""ApplyView\((\d+)\)""", RegexOption.IGNORE_CASE)
                .find(html)
                ?.groupValues
                ?.getOrNull(1)
                ?: Regex("""id_media\s*[:=]\s*["']?(\d+)["']?""", RegexOption.IGNORE_CASE)
                    .find(html)
                    ?.groupValues
                    ?.getOrNull(1)
                ?: return

            app.post(
                "$mainUrl/ajax_apply_view.php",
                data = mapOf("id_media" to idMedia),
                referer = episodeUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "*/*",
                    "Accept-Language" to "es-419,es;q=0.9",
                    "Origin" to mainUrl,
                    "Referer" to episodeUrl,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
                )
            )
        } catch (_: Exception) {
        }
    }

    private fun String.cleanText(): String {
        return replace("diasfem", "embedsito")
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("&amp;", "&")
    }

    private fun extractSlugs(text: String): List<String> {
        val slugs = mutableListOf<String>()

        Regex(""""slug"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .forEach { slugs.add(it.groupValues[1]) }

        Regex("""api_donghua\.php\?slug=([^"'&<> ]+)""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .forEach { slugs.add(it.groupValues[1]) }

        Regex("""slug\s*[:=]\s*["']([^"']{20,})["']""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .forEach { slugs.add(it.groupValues[1]) }

        return slugs
            .map { it.trim() }
            .filter { it.length >= 20 }
            .distinct()
    }

    private fun parseCards(element: Element): List<SearchResponse> {
        return element.select("div.md-card, div.item, li.md-episode-item, a[href*=/donghua/], a[href*=/ver/], .col-xs-4")
            .mapNotNull { card ->
                val a = if (card.tagName() == "a") card else card.selectFirst("a[href]")
                if (a == null) return@mapNotNull null

                val href = fixUrl(a.attr("abs:href").ifBlank { a.attr("href") })
                if (!href.contains("/donghua/") && !href.contains("/ver/")) return@mapNotNull null

                val img = card.selectFirst("img") ?: a.selectFirst("img")
                val poster = fixUrlNull(img?.imgAttr())

                val rawTitle = card.selectFirst(".md-card-title, h3, h5, .fs-14, .md-episode-details h5")?.text()
                    ?: img?.attr("alt")
                    ?: a.attr("title")
                    ?: return@mapNotNull null

                val title = cleanTitle(rawTitle)
                if (title.isBlank()) return@mapNotNull null

                val seriesUrl = if (href.contains("/ver/")) {
                    val slug = href.substringAfter("/ver/").substringBefore("/")
                    "$mainUrl/donghua/$slug"
                } else {
                    href
                }

                newAnimeSearchResponse(title, seriesUrl, TvType.Anime) {
                    posterUrl = poster
                    addDubStatus(
                        if (rawTitle.contains("Latino", true) || rawTitle.contains("Castellano", true)) {
                            DubStatus.Dubbed
                        } else {
                            DubStatus.Subbed
                        }
                    )
                }
            }
            .filter { it.name.isNotBlank() }
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
            .replace("&#8217;", "'")
            .replace(Regex("""Episodio\s*\d*""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""Cap[ií]tulo\s*\d*""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\s+Movie\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+OVA\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+S\d+\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+T\d+\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}