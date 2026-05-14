package com.pelisgratishd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class pelisgratishd : MainAPI() {
    override var mainUrl = "https://www.pelisgratishd.net"
    override var name = "PelisGratisHD"
    override var lang = "es"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/peliculas" to "🎬 Películas Populares",
        "$mainUrl/series" to "📺 Series Populares",
        "$mainUrl/peliculas/genero/accion" to "🔥 Acción",
        "$mainUrl/peliculas/genero/comedia" to "😂 Comedia",
        "$mainUrl/peliculas/genero/drama" to "🎭 Drama",
        "$mainUrl/peliculas/genero/terror" to "👻 Terror",
        "$mainUrl/peliculas/genero/ciencia-ficcion" to "🚀 Ciencia Ficción",
        "$mainUrl/peliculas/genero/animacion" to "🐱 Animación",
        "$mainUrl/series/ano/2025" to "🆕 Series 2025"
    )

    private fun getImage(el: Element?): String? {
        if (el == null) return null
        val attrs = listOf("data-src", "src", "data-original", "srcset")
        for (attr in attrs) {
            val v = el.attr(attr)
            if (v.isNotBlank() && !v.startsWith("data:image")) {
                return v.split(",").first().trim().split(" ").first()
            }
        }
        return null
    }

    private fun extractPoster(document: Document): String? {
        val og = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
        if (!og.isNullOrEmpty()) return fixUrl(og)
        val posterImg = document.selectFirst(".full-poster img")?.attr("src")?.trim()
        if (!posterImg.isNullOrEmpty()) return fixUrl(posterImg)
        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}/page/$page" else request.data
        val document = app.get(url).document
        val items = mutableListOf<SearchResponse>()

        val elements = if (request.data.contains("/peliculas")) {
            document.select(".movie-item2 a.mi2-in-link")
        } else {
            document.select("a[href*='/ver-']")
        }

        elements.forEach { link ->
            val href = link.attr("href").trim()
            if (href.isBlank()) return@forEach
            if (href.contains("/ver-episodio-")) return@forEach

            val title = link.selectFirst(".mi2-title, .title, .side-title")?.text()?.trim()
                ?: link.attr("title")?.trim()
                ?: link.text().trim()

            if (title.isBlank() || title.contains("próximamente", true)) return@forEach

            val poster = getImage(link.selectFirst("img"))?.let { fixUrl(it) }
            val isSeries = href.contains("/series/")

            if (isSeries) {
                items.add(newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
                    posterUrl = poster
                })
            } else {
                items.add(newMovieSearchResponse(title, fixUrl(href)) {
                    posterUrl = poster
                })
            }
        }

        val hasNext = document.select("a[href*='/page/']:contains(SIGUIENTE), .pnext a").isNotEmpty()

        return newHomePageResponse(
            list = HomePageList(request.name, items.distinctBy { it.url }, false),
            hasNext = hasNext
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = query.replace(" ", "+")
        val document = app.get("$mainUrl/buscar?q=$encoded").document
        val items = mutableListOf<SearchResponse>()

        document.select("a[href*='/ver-']").forEach { link ->
            val href = link.attr("href").trim()
            if (href.isBlank()) return@forEach
            if (href.contains("/ver-episodio-")) return@forEach

            val title = link.selectFirst(".mi2-title, .side-title, .title")?.text()?.trim()
                ?: link.attr("title")?.trim()
                ?: link.text().trim()

            if (title.isBlank()) return@forEach

            val poster = getImage(link.selectFirst("img"))?.let { fixUrl(it) }
            val isSeries = href.contains("/series/")

            if (isSeries) {
                items.add(newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
                    posterUrl = poster
                })
            } else {
                items.add(newMovieSearchResponse(title, fixUrl(href)) {
                    posterUrl = poster
                })
            }
        }

        return items.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.kino-h, h1.title, h2.h2-f")?.text()?.trim()
            ?: "Desconocido"
        val cleanTitle = title.replace("próximamente", "", true).trim()

        val poster = extractPoster(document)
        val description = document.selectFirst(".full-desc, .kino-desc p, .full-text p")?.text()?.trim()
        val year = document.selectFirst(".details-f:contains(Año) a, .details-f:contains(lanzamiento) a")
            ?.text()?.toIntOrNull()
        val genres = document.select(".details-f:contains(Género) a").map { it.text().trim() }

        val isSeries = url.contains("/series/") &&
                document.select("a[href*='temporada-'], a[href*='ver-episodio-']").isNotEmpty()

        return if (isSeries) {
            val episodes = mutableListOf<Episode>()
            document.select("a[href*='temporada-']").forEach { seasonLink ->
                val seasonUrl = fixUrl(seasonLink.attr("href"))
                val seasonNum = Regex("temporada-(\\d+)").find(seasonUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val seasonDoc = app.get(seasonUrl).document

                seasonDoc.select("a[href*='ver-episodio-']").forEach { ep ->
                    val epUrl = fixUrl(ep.attr("href"))
                    val epNum = Regex("episodio-(\\d+)").find(epUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                    episodes.add(newEpisode(epUrl) {
                        name = "Episodio $epNum"
                        episode = epNum
                        season = seasonNum
                        posterUrl = poster
                    })
                }
            }

            newTvSeriesLoadResponse(cleanTitle, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                backgroundPosterUrl = poster
                plot = description
                tags = genres
                this.year = year
            }
        } else {
            newMovieLoadResponse(cleanTitle, url, TvType.Movie, url) {
                posterUrl = poster
                backgroundPosterUrl = poster
                plot = description
                tags = genres
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var found = false

        document.select(".lien[data-hash]").forEach { element ->
            val hash = element.attr("data-hash").trim()
            if (hash.isBlank()) return@forEach
            val videoUrl = getVideoUrlFromHash(hash)
            if (!videoUrl.isNullOrBlank()) {
                loadExtractor(videoUrl, mainUrl, subtitleCallback, callback)
                found = true
            }
        }

        if (!found) {
            document.select("script").forEach { script ->
                val text = script.html()
                Regex("""https?://[^\s"']+""").findAll(text).forEach { match ->
                    var url = match.value.replace("\\/", "/")
                    if (url.contains("ads") || url.contains("google")) return@forEach
                    if (!url.startsWith("http")) url = "https:$url"
                    loadExtractor(url, mainUrl, subtitleCallback, callback)
                    found = true
                }
            }
        }

        if (!found) {
            document.select("iframe").forEach { iframe ->
                var src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                if (src.isBlank()) return@forEach
                if (src.startsWith("//")) src = "https:$src"
                if (!src.startsWith("http")) src = "$mainUrl/$src"
                loadExtractor(src, mainUrl, subtitleCallback, callback)
                found = true
            }
        }

        return found
    }

    private suspend fun getVideoUrlFromHash(hash: String): String? {
        return try {
            val response = app.post(
                "$mainUrl/hashembedlink",
                data = mapOf(
                    "hash" to hash,
                    "_token" to "Ej9BURxPWeVe9LfWohfz3XYKIM92y5NXYF5c7w7D"
                ),
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to mainUrl,
                    "Origin" to mainUrl,
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
                )
            )
            val text = response.text
            Regex(""""link"\s*:\s*"([^"]+)"""").find(text)?.groupValues?.get(1)?.replace("\\/", "/")
        } catch (e: Exception) {
            null
        }
    }
}
