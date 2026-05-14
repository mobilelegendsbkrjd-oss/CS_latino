package com.byayzen

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import org.jsoup.nodes.Element

class GnulaHD : MainAPI() {

    override var mainUrl = "https://ww3.gnulahd.nu"
    override var name = "GnulaHD"
    override var lang = "es"
    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to mainUrl
    )

    // ============================
    // MAIN PAGE
    // ============================

    override val mainPage = mainPageOf(
        "$mainUrl/ver/?type=Pelicula&order=latest" to "Últimas Películas",
        "$mainUrl/ver/?type=Serie&order=latest" to "Últimas Series",
        "$mainUrl/ver/?type=Anime&order=latest" to "Últimos Animes"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val doc = app.get(request.data, headers = headers).document

        val items = doc.select("article, div.item")
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, items)
    }

    // ============================
    // SEARCH
    // ============================

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.encodeUrl()}"
        val doc = app.get(url, headers = headers).document

        return doc.select("article, div.result-item")
            .mapNotNull { it.toSearchResult() }
    }

    // ============================
    // LOAD
    // ============================

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = headers).document

        val title = doc.selectFirst("h1")?.text() ?: return null
        val poster = doc.selectFirst(".poster img")?.attr("src")
        val description = doc.selectFirst(".sinopsis, .description")?.text()

        val episodes = doc.select(".episode-item").mapIndexed { index, ep ->
            val epTitle = ep.text()
            val epUrl = ep.attr("href")

            newEpisode(epUrl) {
                this.name = epTitle
                this.season = 1
                this.episode = index + 1
            }
        }

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    // ============================
    // LOAD LINKS
    // ============================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val visited = mutableSetOf<String>()

        return try {
            supervisorScope {
                extractLinks(data, data, subtitleCallback, callback, visited)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun extractLinks(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        visited: MutableSet<String>
    ) {
        if (visited.contains(url)) return
        visited.add(url)

        val doc = app.get(url, headers = headers).document

        // Iframes
        doc.select("iframe[src]").forEach {
            val link = fixUrl(it.attr("src"))
            loadExtractor(link, referer, subtitleCallback, callback)
        }

        // Links directos
        doc.select("a[href]").forEach {
            val link = it.attr("href")
            if (link.contains("voe") ||
                link.contains("filemoon") ||
                link.contains("stream")
            ) {
                loadExtractor(link, referer, subtitleCallback, callback)
            }
        }
    }

    // ============================
    // PARSER ITEM
    // ============================

    private fun Element.toSearchResult(): SearchResponse? {

        val title = selectFirst("h2, h3")?.text() ?: return null
        val href = selectFirst("a")?.attr("href") ?: return null
        val poster = selectFirst("img")?.attr("src")

        val type = when {
            href.contains("pelicula") -> TvType.Movie
            href.contains("serie") -> TvType.TvSeries
            href.contains("anime") -> TvType.Anime
            else -> TvType.Movie
        }

        return newTvSeriesSearchResponse(title, fixUrl(href), type) {
            this.posterUrl = poster
        }
    }

    private fun String.encodeUrl(): String =
        java.net.URLEncoder.encode(this, "UTF-8")
}