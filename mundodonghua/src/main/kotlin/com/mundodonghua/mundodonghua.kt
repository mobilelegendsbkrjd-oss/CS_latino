package com.mundodonghua

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.getAndUnpack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

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

        return out.distinctBy { responseBaseKey(it.url) }
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

        try {
            val searchDoc = app.get(
                "$mainUrl/busquedas/${baseSlug.replace("-", "+")}",
                timeout = 120,
                headers = siteHeaders
            ).document

            val foundUrls = parseCards(searchDoc)
                .map { it.url }
                .filter { it.contains("/donghua/") }
                .filter {
                    val s = it.substringAfter("/donghua/").substringBefore("?").trim('/')
                    normalizeSeasonBaseSlug(s) == baseSlug
                }

            relatedUrls.addAll(foundUrls)
        } catch (_: Exception) {
        }

        for (i in 2..6) {
            relatedUrls.add("$mainUrl/donghua/$baseSlug-$i")
            relatedUrls.add("$mainUrl/donghua/$baseSlug-$i-sp")
        }

        val finalRelatedUrls = relatedUrls.distinct()

        val allEpisodes = mutableListOf<Episode>()

        finalRelatedUrls.forEach { seasonUrl ->
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

    // ───────────────────────── loadLinks ─────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeUrl = fixUrl(data.replace("ñ", "%C3%B1"))
        println("MD_DEBUG[LOADLINKS_START] $episodeUrl")

        val response = try {
            app.get(
                episodeUrl,
                timeout = 30,
                headers = siteHeaders + mapOf(
                    "Referer" to episodeUrl,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("MD_DEBUG[EPISODE_GET_ERROR] ${e.message}")
            return false
        }

        val html = response.text
        val doc = response.document

        // ApplyView primero
        applyViewIfExists(html, episodeUrl)

        val candidates = linkedSetOf<String>()
        val flat = html.replace("\\/", "/")

        // 1) iframes directos
        Regex("""<iframe[^>]+src\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
            .findAll(flat)
            .map { it.groupValues[1].trim() }
            .map { if (it.startsWith("//")) "https:$it" else it }
            .filter { it.startsWith("http", true) }
            .forEach { candidates.add(it) }

        // 2) IDs por host en el HTML crudo
        val serverMap = listOf(
            "bysekoze"   to "https://bysekoze.com/e/",
            "voe.sx"     to "https://voe.sx/e/",
            "vidhidepro" to "https://vidhidepro.com/v/",
            "embedwish"  to "https://embedwish.com/e/"
        )
        val idRegex = Regex("""['"]([A-Za-z0-9]{10,16})['"]""")
        for ((hostKey, prefix) in serverMap) {
            var idx = flat.indexOf(hostKey, ignoreCase = true)
            while (idx >= 0) {
                val window = flat.substring(maxOf(0, idx - 500), minOf(flat.length, idx + 500))
                idRegex.findAll(window).forEach { m ->
                    val id = m.groupValues[1]
                    if (id.length >= 10 && id.any { it.isDigit() } && id.any { it.isLetter() }) {
                        candidates.add("$prefix$id")
                    }
                }
                idx = flat.indexOf(hostKey, idx + 1, ignoreCase = true)
            }
        }

        // 3) Desempaquetar TODOS los eval(...) y buscar iframes/IDs/slugs
        val tamamoSlugs = mutableSetOf<String>()

        doc.select("script").forEach { script ->
            val txt = script.data().ifBlank { script.html() }
            if (!txt.contains("eval(function(p,a,c,k,e", true)) return@forEach

            // Cada eval(...) por separado
            val parts = txt.split(Regex("""eval\s*\(""", RegexOption.IGNORE_CASE))
            parts.drop(1).forEach { rest ->
                val packed = "eval(" + rest
                val unpacked = try { JsUnpacker(packed).unpack() }
                catch (_: Exception) { null }
                    ?: try { getAndUnpack(packed) } catch (_: Exception) { null }

                if (unpacked.isNullOrBlank()) return@forEach
                val clean = unpacked.replace("\\/", "/").replace("&amp;", "&")
                println("MD_DEBUG[UNPACKED] ${clean.take(400)}")

                // iframes del JS desempaquetado
                Regex("""<iframe[^>]+src\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
                    .findAll(clean)
                    .map { it.groupValues[1].trim() }
                    .map { if (it.startsWith("//")) "https:$it" else it }
                    .filter { it.startsWith("http", true) }
                    .forEach { candidates.add(it) }

                // URLs de video absolutas
                Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
                    .findAll(clean)
                    .map { it.value.replace("&amp;", "&").trimEnd('"', '\'', ')', ',', ';') }
                    .filter { MundoHostResolver.isVideoHost(it) }
                    .forEach { candidates.add(it) }

                // IDs por host dentro del unpacked
                for ((hostKey, prefix) in serverMap) {
                    if (!clean.contains(hostKey, ignoreCase = true)) continue
                    idRegex.findAll(clean).forEach { m ->
                        val id = m.groupValues[1]
                        if (id.length >= 10 && id.any { it.isDigit() } && id.any { it.isLetter() }) {
                            candidates.add("$prefix$id")
                        }
                    }
                }

                // Slug de Tamamo: string base64 largo (> 100 chars) que es el data-slug
                Regex("""["']([A-Za-z0-9+/=]{100,})["']""")
                    .findAll(clean)
                    .forEach { tamamoSlugs.add(it.groupValues[1]) }
            }
        }

        // 4) Slug de Tamamo desde el HTML crudo (por si no se desempacó)
        Regex("""["']([A-Za-z0-9+/=]{200,})["']""")
            .findAll(flat)
            .forEach { tamamoSlugs.add(it.groupValues[1]) }

        // 5) Slugs de la API vieja
        extractSlugs(html).forEach { tamamoSlugs.add(it) }

        println("MD_DEBUG[CANDIDATES] ${candidates.joinToString(" | ")}")
        println("MD_DEBUG[TAMAMO_SLUGS] ${tamamoSlugs.map { it.take(40) + "..." }.joinToString(" | ")}")

        if (candidates.isEmpty() && tamamoSlugs.isEmpty()) {
            println("MD_DEBUG[NO_CANDIDATES]")
            return false
        }

        // Resolver todo en paralelo
        val produced = AtomicInteger(0)
        val counting: (ExtractorLink) -> Unit = { link ->
            produced.incrementAndGet()
            callback(link)
        }

        coroutineScope {
            val jobs = mutableListOf<kotlinx.coroutines.Deferred<Boolean>>()

            candidates.forEach { url ->
                jobs.add(async {
                    withTimeoutOrNull(15_000L) {
                        val local = localExtractor(url)
                        if (local != null) {
                            try { local.getUrl(url, episodeUrl, subtitleCallback, counting) }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { println("MD_DEBUG[LOCAL_ERR] ${local.name} $url -> ${e.message}") }
                        }
                        if (produced.get() > 0) return@withTimeoutOrNull true

                        try { loadExtractor(url, episodeUrl, subtitleCallback, counting) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { println("MD_DEBUG[LOADEXTRACTOR_ERR] $url -> ${e.message}") }
                        if (produced.get() > 0) return@withTimeoutOrNull true

                        try { MundoHostResolver.resolve(url, episodeUrl, subtitleCallback, counting) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { println("MD_DEBUG[LEGACY_ERR] $url -> ${e.message}") }
                        produced.get() > 0
                    } ?: false
                })
            }

            tamamoSlugs.forEach { slug ->
                jobs.add(async {
                    withTimeoutOrNull(20_000L) {
                        try {
                            MundoHostResolver.resolveTamamo(slug, episodeUrl, subtitleCallback, counting)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            println("MD_DEBUG[TAMAMO_ERR] $slug -> ${e.message}")
                        }
                        produced.get() > 0
                    } ?: false
                })
            }

            jobs.awaitAll()
        }

        val found = produced.get() > 0
        println("MD_DEBUG[LOADLINKS_END] found=$found links=${produced.get()}")
        return found
    }

    private fun localExtractor(url: String): ExtractorApi? {
        val host = Regex("""^https?://([^/?#]+)""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1)?.lowercase()?.removePrefix("www.") ?: return null

        val vidhideHosts = listOf(
            "vidhide", "filelions", "dhcplay", "kinoger", "smoothpre", "riderjet",
            "dhtpre", "peytonepre", "movearnpre", "dintezuvio", "mycloudz"
        )
        val wishHosts = listOf("wish", "swiftplayers", "embedwish", "hlswish", "flaswish")

        return when {
            host.contains("byse") || host.contains("filemoon") || host == "gn1r5n.org" -> MdByse()
            host.startsWith("voe.") -> MdVoe()
            vidhideHosts.any { host.contains(it) } -> MdVidHidePro()
            wishHosts.any { host.contains(it) } -> MdStreamWish()
            else -> null
        }
    }

    private suspend fun applyViewIfExists(html: String, episodeUrl: String) {
        try {
            val idMedia = Regex("""ApplyView\((\d+)\)""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1)
                ?: Regex("""id_media\s*[:=]\s*["']?(\d+)["']?""", RegexOption.IGNORE_CASE)
                    .find(html)?.groupValues?.getOrNull(1)
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
        val seen = mutableSetOf<String>()

        return element.select("div.md-card, div.item, li.md-episode-item, a[href*=/donghua/], a[href*=/ver/], .col-xs-4")
            .mapNotNull { card ->
                val a = if (card.tagName() == "a") card else card.selectFirst("a[href]")
                if (a == null) return@mapNotNull null

                val href = fixUrl(a.attr("abs:href").ifBlank { a.attr("href") })
                if (!href.contains("/donghua/") && !href.contains("/ver/")) return@mapNotNull null

                val seriesUrl = if (href.contains("/ver/")) {
                    val slug = href.substringAfter("/ver/").substringBefore("/")
                    "$mainUrl/donghua/$slug"
                } else {
                    href
                }

                val baseKey = responseBaseKey(seriesUrl)
                if (!seen.add(baseKey)) return@mapNotNull null

                val img = card.selectFirst("img") ?: a.selectFirst("img")
                val poster = fixUrlNull(img?.imgAttr())

                val rawTitle = card.selectFirst(".md-card-title, h3, h5, .fs-14, .md-episode-details h5")?.text()
                    ?: img?.attr("alt")
                    ?: a.attr("title")
                    ?: return@mapNotNull null

                val title = cleanTitle(rawTitle)
                if (title.isBlank()) return@mapNotNull null

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

    private fun responseBaseKey(url: String): String {
        val slug = url.substringAfter("/donghua/")
            .substringBefore("?")
            .trim('/')

        return normalizeSeasonBaseSlug(slug)
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