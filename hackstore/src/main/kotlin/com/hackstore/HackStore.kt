package com.hackstore

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class HackStore : MainAPI() {

    override var mainUrl = "https://hackstore2.com"
    override var name = "HackStore2"
    override val hasMainPage = true
    override var lang = "es"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override val mainPage = mainPageOf(
        "peliculas/" to "🎬 Películas",
        "series/" to "📺 Series",
        "animes/" to "🍥 Anime"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data}${if (page > 1) "?page=$page" else ""}"
        val doc = app.get(url, referer = mainUrl).document

        // Selectores mejorados
        val items = doc.select("article.post, div.post, .item, .movie, .card, a[href*='/pelicula/'], a[href*='/series/'], a[href*='/anime/']").mapNotNull { el ->
            val linkEl = el.selectFirst("a[href*='/pelicula/'], a[href*='/series/'], a[href*='/anime/'], a")
                ?: el.selectFirst("a")

            val titleEl = el.selectFirst("h1, h2, h3, .entry-title, .title, .name, .post-title")
                ?: linkEl?.selectFirst("h1, h2, h3")

            val imgEl = el.selectFirst("img")

            val title = titleEl?.text()?.trim() ?: return@mapNotNull null
            val link = linkEl?.attr("href")?.let { fixUrl(it) } ?: return@mapNotNull null

            val poster = imgEl?.attr("src")
                ?: imgEl?.attr("data-src")
                ?: imgEl?.attr("data-lazy")
                ?: imgEl?.attr("data-original")

            val type = when {
                link.contains("/series/", ignoreCase = true) || link.contains("/anime/", ignoreCase = true) -> TvType.TvSeries
                else -> TvType.Movie
            }

            newMovieSearchResponse(title, link, type) {
                this.posterUrl = fixUrl(poster)
            }
        }

        return newHomePageResponse(
            list = listOf(
                HomePageList(
                    name = request.name,
                    list = items,
                    isHorizontalImages = false
                )
            ),
            hasNext = items.isNotEmpty() && page < 10  // límite temporal
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val doc = app.get(url, referer = mainUrl).document

        return doc.select("article.post, div.post, .search-result, .item").mapNotNull { el ->
            val titleEl = el.selectFirst("h1, h2, h3, .entry-title, .title")
            val linkEl = el.selectFirst("a")
            val imgEl = el.selectFirst("img")

            val title = titleEl?.text()?.trim() ?: return@mapNotNull null
            val link = linkEl?.attr("href")?.let { fixUrl(it) } ?: return@mapNotNull null
            val poster = imgEl?.attr("src") ?: imgEl?.attr("data-src")

            val type = if (link.contains("/series/") || link.contains("/anime/")) TvType.TvSeries else TvType.Movie

            newMovieSearchResponse(title, link, type) {
                this.posterUrl = fixUrl(poster)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, referer = mainUrl).document

        val title = doc.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: "Sin título"
        val poster = doc.selectFirst("img.wp-post-image, img")?.attr("abs:src")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        val plot = doc.selectFirst(".entry-content p, .sinopsis, .description, .summary, meta[property=og:description]")?.text()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")

        val isSeries = url.contains("/series/", ignoreCase = true)
                || url.contains("/animes/", ignoreCase = true)
                || url.contains("/anime/", ignoreCase = true)
                || doc.select(".episodios, .episodes").isNotEmpty()

        return if (!isSeries) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            // TODO: Mejorar episodios más adelante
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, emptyList()) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, referer = mainUrl).document
        var found = false

        // Buscar reproductores
        doc.select("iframe, video source, a[href*='player'], a[href*='embed'], a[href*='hls'], a[href*='m3u8']").forEach { el ->
            val link = el.attr("src") ?: el.attr("href") ?: el.attr("data-src") ?: el.attr("data-lazy")
            if (link.isNotBlank() && (link.startsWith("http") || link.startsWith("//"))) {
                loadExtractor(fixUrl(link), mainUrl, subtitleCallback, callback)
                found = true
            }
        }

        // También buscar enlaces directos en el contenido
        doc.select(".entry-content a, .download a").forEach { el ->
            val href = el.attr("href")
            if (href.contains(".mp4") || href.contains("m3u8") || href.contains("player")) {
                loadExtractor(fixUrl(href), mainUrl, subtitleCallback, callback)
                found = true
            }
        }

        return found
    }

    private fun fixUrl(url: String?): String {
        if (url.isNullOrBlank()) return ""
        val clean = url.trim()
        return when {
            clean.startsWith("http") -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> mainUrl + clean
            else -> mainUrl + "/" + clean
        }
    }
}