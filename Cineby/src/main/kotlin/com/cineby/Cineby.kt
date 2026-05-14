package com.cineby

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Cineby : MainAPI() {

    override var mainUrl = "https://www.cineby.gd"
    override var name = "Cineby Ultra"
    override var lang = "en"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val hasMainPage = true
    override val hasQuickSearch = true

    // Varias entradas para raspar links de títulos
    override val mainPage = mainPageOf(
        "$mainUrl" to "Trending",
        "$mainUrl/movie" to "Movies",
        "$mainUrl/tv" to "TV Shows"
    )

    // =============================
    // HOME
    // =============================

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val doc = app.get(request.data).document

        val items = doc.select("a[href*=\"/movie/\"], a[href*=\"/tv/\"]")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(
            HomePageList(request.name, items)
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {

        val href = attr("href")
        val title = attr("title").ifBlank { text() }

        if (title.isBlank()) return null

        val poster = selectFirst("img")?.attr("src")

        return if (href.contains("/tv/")) {

            newTvSeriesSearchResponse(
                title,
                fixUrl(href),
                TvType.TvSeries
            ) {
                posterUrl = poster
            }

        } else {

            newMovieSearchResponse(
                title,
                fixUrl(href),
                TvType.Movie
            ) {
                posterUrl = poster
            }
        }
    }

    // =============================
    // SEARCH
    // =============================

    override suspend fun search(query: String): List<SearchResponse> {

        val url = "$mainUrl/search?q=$query"

        val doc = app.get(url).document

        return doc.select("a[href*=\"/movie/\"], a[href*=\"/tv/\"]")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    // =============================
    // LOAD
    // =============================

    override suspend fun load(url: String): LoadResponse {

        val doc = app.get(url).document

        val title =
            doc.selectFirst("meta[property=og:title]")
                ?.attr("content") ?: "Unknown"

        val poster =
            doc.selectFirst("meta[property=og:image]")
                ?.attr("content")

        val plot =
            doc.selectFirst("meta[property=og:description]")
                ?.attr("content")

        val isSeries = url.contains("/tv/")

        if (!isSeries) {

            return newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        val tmdbId = url.substringAfter("/tv/").substringBefore("/")

        val episodes = mutableListOf<Episode>()

        for (season in 1..10) {
            for (episode in 1..24) {

                episodes.add(
                    newEpisode(
                        "$mainUrl/tv/$tmdbId/$season/$episode?play=true"
                    ) {
                        this.season = season
                        this.episode = episode
                        this.name = "Episode $episode"
                    }
                )
            }
        }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes
        ) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // =============================
    // PLAYER ROBUSTO
    // =============================

    private val movieEmbeds = listOf(
        "https://vidsrc.cc/v2/embed/movie/%s",
        "https://vidsrc.dev/embed/movie/%s",
        "https://vidsrc.vip/embed/movie/%s"
    )

    private val tvEmbeds = listOf(
        "https://vidsrc.cc/v2/embed/tv/%s/%s/%s",
        "https://vidsrc.dev/embed/tv/%s/%s/%s",
        "https://vidsrc.vip/embed/tv/%s/%s/%s"
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val url = data.substringBefore("?")
        val parts = url.split("/")

        if (url.contains("/tv/")) {

            val tmdb = parts[parts.indexOf("tv") + 1]
            val season = parts[parts.indexOf("tv") + 2]
            val episode = parts[parts.indexOf("tv") + 3]

            tvEmbeds.forEach {

                val embed = it.format(tmdb, season, episode)

                loadExtractor(
                    embed,
                    data,
                    subtitleCallback,
                    callback
                )
            }
        }

        if (url.contains("/movie/")) {

            val tmdb = parts[parts.indexOf("movie") + 1]

            movieEmbeds.forEach {

                val embed = it.format(tmdb)

                loadExtractor(
                    embed,
                    data,
                    subtitleCallback,
                    callback
                )
            }
        }

        // fallback con resolver propio
        val doc = app.get(data).document
        val iframe = doc.selectFirst("iframe")?.attr("src")

        if (iframe != null) {

            val resolved = StreamflixResolver.resolve(
                iframe,
                data
            )

            if (resolved != null) {

                loadExtractor(
                    resolved,
                    data,
                    subtitleCallback,
                    callback
                )
            }
        }

        return true
    }
}