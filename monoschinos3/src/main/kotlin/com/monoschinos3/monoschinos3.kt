package com.monoschinos3

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder
import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable

@Serializable
data class VideoDetails(
    val id: Int,
    val code: String,
    val title: String,
    val poster_url: String?,
    val description: String?,
    val embed_frame_url: String?,
    val cache_status: String?
)

@Serializable
data class VideoSource(
    val file: String,
    val type: String,
    val label: String?
)

@Serializable
data class VideoPlayback(
    val sources: List<VideoSource>? = null
)

class MonosChinos3 : MainAPI() {
    override var mainUrl = "https://monoschinos.st"
    override var name = "MonosChinos3"
    override var lang = "es"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val requestHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Referer" to mainUrl,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
    )

    override val mainPage = mainPageOf(
        "" to "Últimos capítulos 🔥",
        "" to "Series recientes ⛩"
    )

    private fun getTvType(text: String): TvType {
        return when {
            text.contains("pelicula", ignoreCase = true) -> TvType.AnimeMovie
            text.contains("anime", ignoreCase = true) -> TvType.Anime
            text.contains("ova", ignoreCase = true) -> TvType.Anime
            text.contains("especial", ignoreCase = true) -> TvType.Anime
            else -> TvType.Anime
        }
    }

    private fun String.base64Decode(): String {
        return try {
            String(Base64.decode(this, Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            ""
        }
    }

    // ✅ Devuelve la imagen real de la tarjeta: usa data-src si existe y no está vacío,
    // si no usa src, e ignora los placeholders (capblank.png / anime.png).
    private fun Element.cardImage(): String? {
        val img = this.selectFirst("img") ?: return null
        val raw = img.attr("data-src").ifBlank { img.attr("src") }
        if (raw.isBlank() || raw.contains("capblank") || raw.endsWith("/img/anime.png")) return null
        return fixUrlNull(raw)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a") ?: return null
        val href = fixUrl(a.attr("href"))
        if (href.isBlank() || !href.contains("/anime/")) return null
        val title = a.selectFirst("h3")?.text()?.trim()
            ?: a.selectFirst("img")?.attr("alt")?.trim()
            ?: return null
        val posterUrl = a.cardImage()
        val genre = a.selectFirst("div.font-mono")?.text()?.trim() ?: ""
        val type = getTvType(genre)
        return newAnimeSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.toEpisodeSearchResult(): SearchResponse? {
        val a = this.selectFirst("a") ?: return null
        val episodeHref = fixUrl(a.attr("href"))
        if (episodeHref.isBlank() || !episodeHref.contains("/ver/")) return null
        val seriesSlug = episodeHref
            .substringAfter("/ver/")
            .substringBeforeLast("-episodio")
        if (seriesSlug.isBlank()) return null
        val seriesHref = "$mainUrl/anime/$seriesSlug-sub-espanol"
        val title = a.selectFirst("h3")?.text()?.trim()
            ?: a.selectFirst("img")?.attr("alt")?.trim()
            ?: return null
        val posterUrl = a.cardImage()
        return newAnimeSearchResponse(title, seriesHref, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val requestUrl = when (request.name) {
            "Últimos capítulos 🔥" -> if (page <= 1) mainUrl else "$mainUrl/emision?page=$page"
            "Series recientes ⛩" -> if (page <= 1) mainUrl else "$mainUrl/animes?page=$page"
            else -> mainUrl
        }

        val document = app.get(requestUrl, headers = requestHeaders).document

        val list: List<SearchResponse> = when (request.name) {
            "Series recientes ⛩" -> {
                document.select("section:has(h2:contains(Series recientes)) a.card-wrap[href*='/anime/']")
                    .mapNotNull { it.toSearchResult() }
                    .distinctBy { it.url }
            }
            "Últimos capítulos 🔥" -> {
                document.select("section:has(h2:contains(Últimos capítulos)) a.card-wrap[href*='/ver/']")
                    .mapNotNull { it.toEpisodeSearchResult() }
                    .distinctBy { it.url }
            }
            else -> {
                document.select("a.card-wrap[href*='/anime/']")
                    .mapNotNull { it.toSearchResult() }
                    .distinctBy { it.url }
            }
        }

        val hasNext = document.selectFirst("a:contains(Siguiente), a[rel=next]") != null

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = list,
                isHorizontalImages = false
            ),
            hasNext = hasNext
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.trim().isEmpty()) return emptyList()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/buscar?q=$encoded", headers = requestHeaders).document
        return document.select("a.card-wrap[href*='/anime/']")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = requestHeaders).document

        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.substringBefore(" - ")?.trim()
            ?: "Sin título"

        val poster = fixUrlNull(
            document.selectFirst("meta[property='og:image']")?.attr("content")
                ?: document.selectFirst("img[data-src]")?.attr("data-src")
                ?: document.selectFirst("img[src*='/assets/']")?.attr("src")
        )

        val plot = document.selectFirst("p.text-white\\/75")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:description']")?.attr("content")
            ?: ""

        val typeText = document.selectFirst("span.font-mono.text-\\[11px\\]")?.text()?.trim() ?: ""
        val type = getTvType(typeText)

        val tags = document.select("a[href*='/genero/']").map { it.text().trim() }.filter { it.isNotEmpty() }

        val yearText = document.selectFirst("span.font-mono.text-\\[11px\\].tracking-widest")?.text()?.trim()
        val year = Regex("""(\d{4})""").find(yearText ?: "")?.groupValues?.get(1)?.toIntOrNull()

        val statusText = document.selectFirst("div.inline-flex.items-center.gap-1\\.5")?.text()?.trim() ?: ""
        val showStatus = when {
            statusText.contains("Finalizado", true) -> ShowStatus.Completed
            statusText.contains("En emisión", true) -> ShowStatus.Ongoing
            else -> null
        }

        // ✅ CORREGIDO: se pide página por página hasta que el servidor ya no devuelva
        // capítulos nuevos. Ya no dependemos de "eps.length()" para saber el total.
        val episodes = mutableListOf<Episode>()
        val addedUrls = mutableSetOf<String>()
        val ajaxUrl = document.selectFirst("section.caplist[data-ajax]")?.attr("data-ajax")

        if (!ajaxUrl.isNullOrBlank()) {
            try {
                val firstJson = org.json.JSONObject(
                    app.get(fixUrl(ajaxUrl), headers = requestHeaders).text
                )
                println("MonosChinos: AJAX inicial -> $firstJson")

                val paginateUrl = firstJson.optString("paginate_url", "")

                if (paginateUrl.isNotBlank()) {
                    var p = 1
                    while (p <= 100) { // tope de seguridad
                        val pageJson = org.json.JSONObject(
                            app.get(
                                fixUrl(paginateUrl),
                                headers = requestHeaders,
                                params = mapOf("p" to p.toString())
                            ).text
                        )
                        val caps = pageJson.optJSONArray("caps")
                        if (caps == null || caps.length() == 0) break

                        var newInThisPage = 0
                        for (i in 0 until caps.length()) {
                            val cap = caps.getJSONObject(i)
                            val epUrl = cap.optString("url", "")
                            if (epUrl.isBlank()) continue
                            val epHref = fixUrl(epUrl)
                            if (!addedUrls.add(epHref)) continue
                            newInThisPage++

                            val epNum = cap.optInt("episodio", cap.optInt("num", episodes.size + 1))
                            val epThumb = fixUrlNull(cap.optString("thumb", ""))

                            episodes.add(newEpisode(epHref) {
                                this.name = "Capítulo $epNum"
                                this.episode = epNum
                                this.posterUrl = epThumb
                                this.season = 1
                            })
                        }
                        if (newInThisPage == 0) break // la página se repitió, paramos
                        p++
                    }
                }
            } catch (e: Exception) {
                println("MonosChinos: error cargando episodios AJAX -> ${e.message}")
            }
        }

        // Fallback final: buscar enlaces directos en el HTML
        if (episodes.isEmpty()) {
            document.select("a[href*='/ver/']").forEach { epA ->
                val epHref = fixUrl(epA.attr("href"))
                if (epHref.isBlank()) return@forEach
                if (!addedUrls.add(epHref)) return@forEach
                val epNum = Regex("""-episodio-(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""(\d+)""").find(epHref.substringAfterLast("-"))?.groupValues?.get(1)?.toIntOrNull()
                episodes.add(newEpisode(epHref) {
                    this.name = "Capítulo ${epNum ?: (episodes.size + 1)}"
                    this.episode = epNum ?: (episodes.size + 1)
                    this.season = 1
                })
            }
        }

        episodes.sortBy { it.episode }

        return newAnimeLoadResponse(title, url, type) {
            addEpisodes(DubStatus.Subbed, episodes)
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.showStatus = showStatus
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        val document = try {
            app.get(data, headers = requestHeaders, timeout = 45).document
        } catch (e: Exception) {
            println("MonosChinos: error cargando episodio -> ${e.message}")
            return false
        }

        document.select("button.play-video[data-player]").forEach { btn ->
            val base64 = btn.attr("data-player").trim()
            val serverName = btn.text().trim()
            if (base64.isNotBlank()) {
                try {
                    val decoded = base64.base64Decode().trim()
                    println("MonosChinos: $serverName -> $decoded")
                    if (decoded.startsWith("http://") || decoded.startsWith("https://")) {
                        loadExtractor(decoded, data, subtitleCallback, callback)
                        found = true
                    }
                } catch (e: Exception) {
                    println("MonosChinos: error decodificando $serverName -> ${e.message}")
                }
            }
        }

        document.select("a.direct-link[href]").forEach { a ->
            val dlUrl = a.attr("abs:href").trim()
            if (dlUrl.isNotBlank()) {
                try {
                    loadExtractor(dlUrl, data, subtitleCallback, callback)
                    found = true
                } catch (_: Exception) {}
            }
        }

        return found
    }

    private inline fun <reified T> parseJson(json: String): T {
        return Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }.decodeFromString<T>(json)
    }
}