package com.SoloLatino

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class SoloLatino : MainAPI() {
    override var mainUrl = "https://sololatino.net"
    override var name = "SoloLatino"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Cartoon,
    )

    override val mainPage = mainPageOf(
        "$mainUrl/series/" to "Series Recientes",
        "$mainUrl/peliculas/" to "Películas Recientes",
        "$mainUrl/animes/" to "Animes Recientes",
        "$mainUrl/genre_series/toons/" to "CarToons",
        "$mainUrl/genre_series/kdramas/" to "Doramas",
        "$mainUrl/network/netflix/" to "Netflix",
        "$mainUrl/network/amazon/" to "Amazon",
        "$mainUrl/network/disney/" to "Disney+",
        "$mainUrl/network/hbo-max/" to "HBO Max",
        "$mainUrl/network/apple-tv/" to "Apple TV",
        "$mainUrl/network/hulu/" to "Hulu"
    )

    private fun getImage(el: Element?): String? {
        if (el == null) return null
        val attrs = listOf("data-src","data-lazy","data-original","data-srcset","src","srcset")
        for (attr in attrs) {
            val v = el.attr(attr)
            if (v.isNotBlank() && !v.startsWith("data:image")) {
                return v.split(",").first().trim().split(" ").first()
            }
        }
        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val urls = listOf(
            "Series Recientes" to "$mainUrl/series/",
            "Películas Recientes" to "$mainUrl/peliculas/",
            "Animes Recientes" to "$mainUrl/animes/",
            "CarToons" to "$mainUrl/genre_series/toons/",
            "Doramas" to "$mainUrl/genre_series/kdramas/",
            "Netflix" to "$mainUrl/network/netflix/",
            "Amazon" to "$mainUrl/network/amazon/",
            "Disney+" to "$mainUrl/network/disney/",
            "HBO Max" to "$mainUrl/network/hbo-max/",
            "Apple TV" to "$mainUrl/network/apple-tv/",
            "Hulu" to "$mainUrl/network/hulu/"
        )

        val lists = urls.map { (section, url) ->
            val tvType = when (section) {
                "Películas Recientes" -> TvType.Movie
                "Series Recientes" -> TvType.TvSeries
                "Animes Recientes" -> TvType.Anime
                "CarToons" -> TvType.Cartoon
                "Doramas" -> TvType.TvSeries
                "Netflix", "Amazon", "Disney+", "HBO Max", "Apple TV", "Hulu" -> TvType.TvSeries
                else -> TvType.Others
            }

            val doc = app.get(url).document
            val items = doc.select("div.items article.item").mapNotNull {
                val title = it.selectFirst("a div.data h3")?.text() ?: return@mapNotNull null
                val link = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val img = getImage(it.selectFirst("div.poster img"))

                newTvSeriesSearchResponse(title, link, tvType) {
                    posterUrl = img
                }
            }

            HomePageList(section, items)
        }

        return HomePageResponse(lists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("div.items article.item").mapNotNull {
            val title = it.selectFirst("a div.data h3")?.text() ?: return@mapNotNull null
            val link = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val img = getImage(it.selectFirst("div.poster img"))

            newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                posterUrl = img
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        if (url.contains("/episodios/")) {
            val title = doc.selectFirst("title")?.text()?.substringBefore(" -") ?: return null
            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
            }
        }

        val tvType = if (url.contains("peliculas")) TvType.Movie else TvType.TvSeries
        val title = doc.selectFirst("div.data h1")?.text() ?: return null
        val poster = getImage(doc.selectFirst("div.poster img"))
        val description = doc.selectFirst("div.wp-content")?.text()
        val tags = doc.select("div.sgeneros a").map { it.text() }

        val episodes = if (tvType == TvType.TvSeries) {
            doc.select("ul.episodios li").mapNotNull {
                val epUrl = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val epTitle = it.selectFirst("div.epst")?.text() ?: "Episodio"
                val nums = it.selectFirst("div.numerando")?.text()
                    ?.split("-")?.map { n -> n.trim().toIntOrNull() }
                val img = getImage(it.selectFirst("div.imagen img"))

                newEpisode(epUrl) {
                    name = epTitle
                    season = nums?.getOrNull(0)
                    episode = nums?.getOrNull(1)
                    posterUrl = img
                }
            }
        } else emptyList()

        return when (tvType) {
            TvType.TvSeries -> newTvSeriesLoadResponse(title, url, tvType, episodes) {
                posterUrl = poster
                backgroundPosterUrl = poster
                plot = description
                this.tags = tags
            }
            TvType.Movie -> newMovieLoadResponse(title, url, tvType, url) {
                posterUrl = poster
                backgroundPosterUrl = poster
                plot = description
                this.tags = tags
            }
            else -> null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        var found = false

        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                val finalSrc = if (src.startsWith("//")) "https:$src" else src
                loadExtractor(finalSrc, mainUrl, subtitleCallback, callback)
                found = true
            }
        }

        val html = doc.html()
        val regex = Regex("""(https?://[^\s'"]*(embed|player|stream|video)[^\s'"]+)""")
        regex.findAll(html).forEach { match ->
            val urlMatch = match.value
            loadExtractor(urlMatch, mainUrl, subtitleCallback, callback)
            found = true
        }

        return found
    }
}