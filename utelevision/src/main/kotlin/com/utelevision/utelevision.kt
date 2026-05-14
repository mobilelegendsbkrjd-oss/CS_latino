package com.utelevision

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mainpage.MainPageRequest
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Document

class UTelevision : MainAPI() {

    override var mainUrl = "https://utelevision.cc"
    override var name = "uTelevision"
    override var lang = "es"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val mainPage = listOf(
        MainPageRequest("Películas", "$mainUrl/movies"),
        MainPageRequest("Series", "$mainUrl/series"),
        MainPageRequest("Películas de moda", "$mainUrl/trending-movies"),
        MainPageRequest("Series de moda", "$mainUrl/trending-series"),
        MainPageRequest("Navideñas", "$mainUrl/christmas-movies"),
    )

    override fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val doc = app.get(request.data).document
        return HomePageResponse(
            request.name,
            parseCards(doc)
        )
    }

    override fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?keyword=${query.replace(" ", "+")}"
        val doc = app.get(url).document
        return parseCards(doc)
    }

    override fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1")?.text() ?: "Sin título"
        val poster = doc.selectFirst("picture img")?.attr("src")
        val description = doc.selectFirst("p.text-muted")?.text()

        return MovieLoadResponse(
            name = title,
            url = url,
            apiName = this.name,
            type = TvType.Movie,
            posterUrl = poster,
            plot = description,
            year = null
        )
    }

    override fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val doc = app.get(data).document

        val iframeSrc = doc.selectFirst("iframe#ve-iframe")
            ?.attr("src")
            ?: return false

        val playerUrl = fixUrl(iframeSrc)
        val playerHtml = app.get(playerUrl, referer = mainUrl).text

        // 🔥 Streams (m3u8 / txt)
        Regex("""https?:\/\/[^\s"'<>]+?\.(m3u8|txt)""", RegexOption.IGNORE_CASE)
            .findAll(playerHtml)
            .map { it.value }
            .distinct()
            .forEachIndexed { index, stream ->
                callback(
                    ExtractorLink(
                        source = name,
                        name = "Servidor ${index + 1}",
                        url = stream,
                        referer = playerUrl,
                        quality = Qualities.Unknown.value,
                        isM3u8 = true
                    )
                )
            }

        // 🧩 Subtítulos externos
        Regex("""https?:\/\/[^\s"'<>]+?\.vtt""", RegexOption.IGNORE_CASE)
            .findAll(playerHtml)
            .map { it.value }
            .distinct()
            .forEach {
                subtitleCallback(
                    SubtitleFile(
                        lang = "Español",
                        url = it
                    )
                )
            }

        return true
    }

    private fun parseCards(doc: Document): List<SearchResponse> {
        return doc.select("a.card-movie").mapNotNull { card ->
            val href = card.attr("href")
            val title = card.selectFirst("h3.title")?.text() ?: return@mapNotNull null
            val poster = card.selectFirst("img")?.attr("src")

            MovieSearchResponse(
                name = title,
                url = fixUrl(href),
                apiName = this.name,
                type = TvType.Movie,
                posterUrl = poster,
                year = null
            )
        }
    }
}
