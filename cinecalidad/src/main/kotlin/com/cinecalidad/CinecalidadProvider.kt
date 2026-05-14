package cinecalidad

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class CinecalidadProvider : MainAPI() {

    override var mainUrl = "https://www.cinecalidad.ec"
    override var name = "Cinecalidad"
    override var lang = "mx"

    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "$mainUrl/ver-serie/page/" to "Series",
        "$mainUrl/page/" to "Peliculas",
        "$mainUrl/genero-de-la-pelicula/peliculas-en-calidad-4k/page/" to "4K UHD"
    )

    // ============================
    // MAIN PAGE
    // ============================

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url = request.data + page
        val doc = app.get(url).document

        val items = doc.select("article").mapNotNull { element ->
            element.toSearchResult()
        }

        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst("a")?.attr("href") ?: return null
        val title = selectFirst("h2")?.text() ?: return null
        val poster = selectFirst("img")?.attr("src") ?: ""

        return if (link.contains("/ver-serie/")) {
            newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, link, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    // ============================
    // SEARCH
    // ============================

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val doc = app.get(url).document

        return doc.select("article").mapNotNull { it.toSearchResult() }
    }

    // ============================
    // LOAD
    // ============================

    override suspend fun load(url: String): LoadResponse {

        val doc = app.get(url).document

        val title = doc.selectFirst("h1")?.text() ?: return error("No title")
        val description = doc.selectFirst("div.entry-content p")?.text() ?: ""
        val poster = doc.selectFirst("img")?.attr("src") ?: ""

        val episodes = doc.select("div.season, div.episodios")

        return if (episodes.isNotEmpty()) {

            val episodeList = ArrayList<Episode>()

            doc.select("a[href*=\"capitulo\"], a[href*=\"episodio\"]")
                .forEachIndexed { index, ep ->
                    val epUrl = ep.attr("href")
                    val epName = ep.text()

                    episodeList.add(
                        Episode(
                            data = epUrl,
                            name = epName,
                            season = 1,
                            episode = index + 1
                        )
                    )
                }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeList) {
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

        val doc = app.get(data).document

        doc.select("a[href*=\"player\"], a[href*=\"iframe\"], iframe")
            .forEach { element ->

                val link = when {
                    element.tagName() == "iframe" -> element.attr("src")
                    else -> element.attr("href")
                }

                if (link.isNotBlank()) {
                    loadExtractor(link, data, subtitleCallback, callback)
                }
            }

        return true
    }
}