package cinecalidad

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class CinecalidadProvider : MainAPI() {
    override var mainUrl = "https://www.cinecalidad.ec"
    override var name = "Cinecalidad"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )
    override val vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        Pair("$mainUrl/ver-serie/page/", "Series"),
        Pair("$mainUrl/page/", "Peliculas"),
        Pair("$mainUrl/genero-de-la-pelicula/peliculas-en-calidad-4k/page/", "4K UHD"),
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.data + page
        val soup = app.get(url).document

        val home = soup.select(".item.movies").mapNotNull {
            val link = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null

            // Quitar accesos tipo Netflix, Amazon, Disney, etc.
            if (!link.contains("/ver-pelicula/") && !link.contains("/ver-serie/")) {
                return@mapNotNull null
            }

            val title = it.selectFirst("div.in_title")?.text() ?: return@mapNotNull null
            val poster = it.selectFirst(".poster.custom img")?.attr("data-src")
            val type = if (link.contains("/ver-pelicula/")) TvType.Movie else TvType.TvSeries

            if (type == TvType.Movie) {
                newMovieSearchResponse(title, link, TvType.Movie) {
                    posterUrl = poster
                }
            } else {
                newTvSeriesSearchResponse(title, link) {
                    posterUrl = poster
                }
            }
        }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document

        return document.select("article.item").mapNotNull {
            val title = it.selectFirst("div.in_title")?.text() ?: return@mapNotNull null
            val href = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val image = it.selectFirst("img.lazy")?.attr("data-src")
            val isMovie = href.contains("/ver-pelicula/")

            if (isMovie) {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    posterUrl = image
                }
            } else {
                newTvSeriesSearchResponse(title, href) {
                    posterUrl = image
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val soup = app.get(url, timeout = 120).document

        val title = soup.selectFirst(".single_left h1")?.text() ?: "Sin título"
        val description = soup.selectFirst("div.single_left table tbody tr td p")?.text()?.trim()
        val poster = soup.selectFirst(".alignnone")?.attr("data-src")

        val episodes = soup.select("div.se-c div.se-a ul.episodios li").mapNotNull { li ->
            val href = li.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val epThumb = li.selectFirst("img.lazy")?.attr("data-src")
            val name = li.selectFirst(".episodiotitle a")?.text() ?: "Episodio"
            val seasonid =
                li.selectFirst(".numerando")?.text()
                    ?.replace(Regex("(S|E)"), "")
                    ?.split("-")
                    ?.mapNotNull { it.toIntOrNull() }

            val season = seasonid?.getOrNull(0)
            val episode = seasonid?.getOrNull(1)

            newEpisode(href) {
                this.name = name
                this.season = season
                this.episode = episode
                this.posterUrl = if (epThumb?.contains("svg") == true) null else epThumb
            }
        }

        return if (url.contains("/ver-pelicula/")) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                plot = description
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                plot = description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        for (it in doc.select(".linklist ul li")) {
            val url = it.attr("data-option")
            if (url.isNotBlank()) {
                loadExtractor(url, mainUrl, subtitleCallback, callback)
            }
        }
        return true
    }
}
