package com.zonaleros

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.logError
import org.jsoup.nodes.Element

class Zonaleros : MainAPI() {
    override var mainUrl = "https://www.zona-leros.com"
    override var name = "Zonaleros"
    override val hasMainPage = true
    override var lang = "es"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/series-h?order=views&page=" to "Series Populares",
        "$mainUrl/peliculas-hd-online-lat?order=published&page=" to "Películas Recientes",
        "$mainUrl/juegos-pc?order=views&page=" to "Juegos PC"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page
        val doc = app.get(url, referer = mainUrl).document

        val items = doc.select(".ListAnimes .Anime").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, items, hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst(".Title")?.text()?.trim() ?: return null
        val href = selectFirst("a")?.attr("abs:href") ?: return null
        val img = selectFirst("img")?.getImageUrl() ?: ""

        return newAnimeSearchResponse(title, href) {
            this.posterUrl = img
            type = if (href.contains("/peliculas", ignoreCase = true)) TvType.Movie else TvType.TvSeries
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/search?q=$query", referer = mainUrl).document
        return doc.select(".ListAnimes .Anime").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, referer = mainUrl).document

        val title = doc.selectFirst("h1.Title, .Title")?.text()?.trim() ?: "Sin título"
        val poster = doc.selectFirst("img")?.getImageUrl()
        val description = doc.selectFirst(".Description, .Main section .Description p")?.text()

        val episodes = if (url.contains("/peliculas", ignoreCase = true) || url.contains("pelicula", ignoreCase = true)) {
            listOf(newEpisode(url) { name = "Película Completa" })
        } else {
            doc.select(".ListEpisodios a, a[href*=/episode/], [id*=temp] a").map { ep ->
                val epTitle = ep.selectFirst(".Capi, .Title")?.text() ?: ep.text()
                newEpisode(ep.attr("abs:href")) {
                    name = epTitle
                }
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, referer = mainUrl).document

        var scriptText = doc.selectFirst("script:containsData(var video)")?.data()
            ?: doc.selectFirst("script:containsData(sources)")?.data()
            ?: doc.selectFirst("script:containsData(player)")?.data()
            ?: doc.select("script").joinToString("\n") { it.data() }

        val links = extractLinks(scriptText)

        links.forEach { link ->
            try {
                if (link.contains("anomizador", ignoreCase = true)) {
                    val realDoc = app.get(link, referer = mainUrl).document
                    val finalLink = realDoc.selectFirst("a[href^=http]")?.attr("abs:href")
                        ?: realDoc.text().substringAfter("url=", "").substringBefore("\"", "")

                    if (finalLink.isNotBlank() && finalLink.startsWith("http")) {
                        loadExtractor(finalLink, mainUrl, subtitleCallback, callback)
                    }
                } else if (link.containsAny(listOf("voe", "dood", "filemoon", "streamwish", "mixdrop", "mp4upload", "vidhide", "uqload"))) {
                    loadExtractor(link, mainUrl, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                logError(e)
            }
        }

        return true
    }

    private fun extractLinks(text: String): List<String> {
        val regex = """(https?://[^\s"']+(?:php|html?|embed|player|anomizador)[^\s"']*)""".toRegex(RegexOption.IGNORE_CASE)
        return regex.findAll(text).map { it.value }.toList().distinct()
    }

    private fun String.containsAny(keywords: List<String>): Boolean {
        return keywords.any { this.contains(it, ignoreCase = true) }
    }

    private fun Element.getImageUrl(): String? {
        return attr("data-src")
            .ifBlank { attr("src") }
            .ifBlank { attr("data-lazy-src") }
            .takeIf { it.isNotBlank() && !it.contains("data:image") }
    }
}