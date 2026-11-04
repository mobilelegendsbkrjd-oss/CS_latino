package com.mundodonghua

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

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
        if (query.isBlank()) return emptyList()
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val doc = app.get(
            "$mainUrl/buscar.php?s=$q",
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
        println("SD_DEBUG[LOADLINKS_START] $episodeUrl")

        // 1. GET inicial para obtener cookies y CSRF
        val response = try {
            app.get(
                episodeUrl,
                timeout = 30,
                headers = siteHeaders + mapOf(
                    "Referer" to "$mainUrl/",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("SD_DEBUG[EPISODE_GET_ERROR] ${e.message}")
            return false
        }

        val doc = response.document
        val cookies = response.cookies

        // Construir el header Cookie manualmente por si acaso
        val cookieHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        println("SD_DEBUG[COOKIES] $cookieHeader")

        val videoId = Regex("""data-video-id="(\d+)"""")
            .find(response.text)
            ?.groupValues
            ?.getOrNull(1)
            ?: run {
                println("SD_DEBUG[NO_VIDEO_ID]")
                return false
            }

        val csrf = doc.selectFirst("meta[name=csrf-token]")?.attr("content") ?: ""
        println("SD_DEBUG[CSRF] $csrf")

        val serverIndices = doc.select(".server-tab-btn[data-server-index]")
            .mapNotNull { it.attr("data-server-index").trim().toIntOrNull() }
            .distinct()
            .ifEmpty { listOf(0, 1, 2, 3) }

        val serverNames = doc.select(".server-tab-btn").map { btn ->
            btn.selectFirst("span")?.text()?.trim() ?: "?"
        }

        println("SD_DEBUG[VIDEO_ID] $videoId")
        println("SD_DEBUG[SERVERS] $serverIndices -> $serverNames")

        val produced = AtomicInteger(0)
        val counting: (ExtractorLink) -> Unit = { link ->
            produced.incrementAndGet()
            callback(link)
        }

        // 2. Pedir las URLs de forma SECUENCIAL (evita problemas de cookies concurrentes)
        for ((idx, serverIndex) in serverIndices.withIndex()) {
            val serverName = serverNames.getOrNull(idx) ?: "Server $serverIndex"
            println("SD_DEBUG[FETCH_START] $serverName (index=$serverIndex)")

            val embedUrl = fetchEmbedUrl(videoId, serverIndex, csrf, episodeUrl, cookieHeader)

            if (embedUrl == null) {
                println("SD_DEBUG[EMBED_NULL] $serverName")
                continue
            }

            println("SD_DEBUG[EMBED] $serverName -> $embedUrl")

            // Resolver la URL
            if (embedUrl.contains("rumble.com", ignoreCase = true)) {
                try {
                    MdRumble().getUrl(embedUrl, episodeUrl, subtitleCallback, counting)
                    if (produced.get() > 0) continue
                } catch (e: Exception) {
                    println("SD_DEBUG[RUMBLE_ERR] ${e.message}")
                }
            }

            try {
                if (MundoHostResolver.resolve(embedUrl, episodeUrl, subtitleCallback, counting)) {
                    continue
                }
            } catch (e: Exception) {
                println("SD_DEBUG[RESOLVER_ERR] ${e.message}")
            }

            try {
                loadExtractor(embedUrl, episodeUrl, subtitleCallback, counting)
            } catch (e: Exception) {
                println("SD_DEBUG[LOADEXTRACTOR_ERR] ${e.message}")
            }
        }

        val found = produced.get() > 0
        println("SD_DEBUG[LOADLINKS_END] found=$found links=${produced.get()}")
        return found
    }

    private suspend fun fetchEmbedUrl(
        videoId: String,
        serverIndex: Int,
        csrf: String,
        referer: String,
        cookieHeader: String
    ): String? {
        return try {
            val body = """{"video_id":$videoId,"server_index":$serverIndex}"""
            println("SD_DEBUG[FETCH_BODY_$serverIndex] $body")

            val res = app.post(
                "$mainUrl/api/player/get-server",
                requestBody = body.toRequestBody("application/json; charset=utf-8".toMediaType()),
                referer = referer,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "application/json, text/plain, */*",
                    "Accept-Language" to "es-419,es;q=0.9",
                    "Content-Type" to "application/json",
                    "X-CSRF-TOKEN" to csrf,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Origin" to mainUrl,
                    "Referer" to referer,
                    "Cookie" to cookieHeader
                ),
                timeout = 20
            ).text

            println("SD_DEBUG[API_RESPONSE_$serverIndex] ${res.take(500)}")

            val json = JSONObject(res)
            if (!json.optBoolean("success", false)) {
                println("SD_DEBUG[API_FAIL_$serverIndex] success=false")
                return null
            }
            val url = json.optString("embed_url").trim()
            if (url.isBlank()) {
                println("SD_DEBUG[API_EMPTY_$serverIndex]")
                return null
            }
            url
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("SD_DEBUG[API_ERROR_$serverIndex] ${e.message}")
            println("SD_DEBUG[API_ERROR_${serverIndex}_STACK] ${e.stackTraceToString().take(500)}")
            null
        }
    }
    // Selectores del catálogo original para no capturar categorías
    private fun parseCards(doc: Document): List<SearchResponse> {
        val items = ArrayList<SearchResponse>()
        val seenUrls = mutableSetOf<String>()

        // Buscamos específicamente las tarjetas que contienen las series
        val cards = doc.select("article.donghua-card, .item, .angled-img, .col-lg-3, .col-md-3, .col-sm-4, .card, a[href]")

        for (el in cards) {
            val element: Element = el

            val a: Element? = if (element.tagName() == "a") {
                element
            } else {
                element.selectFirst("a[href]")
            }
            if (a == null) continue

            var href: String = a.attr("abs:href").ifBlank { a.attr("href") }
            if (href.isBlank()) continue
            href = fixUrl(href)

            // Filtros para evitar que agarre paginación, episodios o categorías
            if (href.contains("-episodio-", ignoreCase = true)) continue
            if (!href.startsWith(mainUrl)) continue
            if (href == mainUrl || href == "$mainUrl/") continue
            if (href.contains("/css/") || href.contains("/js/") || href.contains("/imagenes-")) continue
            if (href.contains("?page=")) continue
            if (href.endsWith("/accion/") || href.endsWith("/artes-marciales/") ||
                href.endsWith("/aventura/") || href.endsWith("/comedia/") ||
                href.endsWith("/todos-los-donghuas") || href.endsWith("/donghuas-en-emision") ||
                href.endsWith("/donghuas-finalizados")) continue

            if (!seenUrls.add(href)) continue

            // LA CLAVE: Solo procesar si tiene una imagen (las categorías no tienen imagen)
            val img: Element? = element.selectFirst("img")
            if (img == null) continue

            val titleEl: Element? = element.selectFirst(".card-title, h3, h5, h4, .title")
            var title: String = titleEl?.text()?.trim().orEmpty()
            if (title.isBlank()) title = a.attr("title").trim()
            if (title.isBlank()) title = img.attr("alt").trim()
            if (title.isBlank() || title.length < 2) continue

            var poster: String? = img.attr("abs:src")
            if (poster.isNullOrBlank()) poster = img.attr("abs:data-src")
            if (poster.isNullOrBlank()) poster = img.attr("data-src")
            if (poster.isNullOrBlank()) poster = img.attr("src")
            if (!poster.isNullOrBlank()) poster = fixUrl(poster)
            else poster = null

            items.add(
                newAnimeSearchResponse(cleanTitle(title), href, TvType.Anime) {
                    this.posterUrl = poster
                }
            )
        }

        return items
    }

    private fun cleanTitle(title: String): String {
        return title
            .replace(Regex("""\s*【.*?】\s*"""), " ")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
    }
}