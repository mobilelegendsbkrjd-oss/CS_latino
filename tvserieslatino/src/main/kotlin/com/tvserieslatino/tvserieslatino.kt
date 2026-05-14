package com.tvserieslatino

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class TvSeriesLatino : MainAPI() {

    override var mainUrl = "https://www.tvserieslatino.com"
    override var name = "TVSeriesLatino"
    override val hasMainPage = true
    override var lang = "es"

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Anime
    )

    override val mainPage = mainPageOf(
        "$mainUrl/category/series-animadas/" to "Series Animadas",
        "$mainUrl/category/anime/" to "Anime",
        "$mainUrl/category/series-de-terror/" to "Terror",
        "$mainUrl/category/telecomedia/" to "Comedia"
    )

    // ================= HOME =================

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url = "${request.data}page/$page/"
        val doc = app.get(url).document

        val items = doc.select("article h2 a, h2.entry-title a")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    // ================= SEARCH =================

    override suspend fun search(query: String): List<SearchResponse> {

        val doc = app.get("$mainUrl/?s=$query").document

        return doc.select("article h2 a, h2.entry-title a")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    // ================= LOAD =================

    override suspend fun load(url: String): LoadResponse {

        val doc = app.get(url).document

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("h2")?.text()?.trim()
            ?: "Serie"

        val poster = doc.selectFirst("meta[property=og:image]")
            ?.attr("content")

        val plot = doc.selectFirst("meta[property=og:description]")
            ?.attr("content")

        val html = doc.html()

        val episodes = mutableListOf<Episode>()

        // ================= EXTRAER urls[] =================

        val urls = Regex(
            "var\\s+urls\\s*=\\s*\\[(.*?)\\]",
            RegexOption.DOT_MATCHES_ALL
        )
            .find(html)
            ?.groupValues?.get(1)
            ?.split(",")
            ?.map {
                it.replace("\"", "")
                    .replace("'", "")
                    .trim()
            }
            ?.filter { it.startsWith("http") }
            ?: emptyList()

        urls.forEachIndexed { index, link ->

            episodes.add(
                newEpisode(link) {
                    name = "Capítulo ${index + 1}"
                    episode = index + 1
                    season = 1
                }
            )
        }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes
        ) {
            posterUrl = poster
            this.plot = plot
        }
    }

    // ================= LINKS =================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val cleanUrl = Regex("(https://[^/]+/e/[a-zA-Z0-9]+)")
            .find(data)
            ?.value
            ?: data

        loadExtractor(
            cleanUrl,
            mainUrl,
            subtitleCallback,
            callback
        )

        return true
    }

    // ================= PARSER =================

    private fun Element.toSearchResult(): SearchResponse? {

        val link = attr("href")

        val title = text()
            .replace(
                Regex("(?i)ver|online|hd|latino|sub español"),
                ""
            )
            .trim()

        val poster = parent()?.parent()
            ?.selectFirst("img")
            ?.attr("src")

        return newTvSeriesSearchResponse(
            title,
            link
        ) {
            this.posterUrl = poster
        }
    }
}