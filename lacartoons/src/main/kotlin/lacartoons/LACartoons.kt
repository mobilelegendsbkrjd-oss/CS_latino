package lacartoons

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document

class LACartoons : MainAPI() {
    override var mainUrl = "https://www.lacartoons.com"
    override var name = "LACartoons"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Cartoon,
        TvType.TvSeries
    )

    private fun Document.toSearchResult(): List<SearchResponse> {
        return this.select(".categorias .conjuntos-series a").mapNotNull { it ->
            val title = it.selectFirst("p.nombre-serie")?.text() ?: return@mapNotNull null
            val href = fixUrl(it.attr("href"))
            val img = it.selectFirst("img")?.attr("src") ?: return@mapNotNull null
            val poster = fixUrl(img)

            newTvSeriesSearchResponse(title, href) {
                posterUrl = poster
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val soup = app.get(mainUrl).document
        val home = soup.toSearchResult()
        return newHomePageResponse("Series", home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?utf8=✓&Titulo=$query").document
        return doc.toSearchResult()
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h2.text-center")?.text() ?: "Sin título"
        val description =
            doc.selectFirst(".informacion-serie-seccion p:contains(Reseña)")?.text()
                ?.substringAfter("Reseña:")
                ?.trim()

        val poster = doc.selectFirst(".imagen-serie img")?.attr("src")
        val backposter = doc.selectFirst("img.fondo-serie-seccion")?.attr("src")

        val episodes = doc.select("ul.listas-de-episodion li").mapNotNull { li ->
            val regexep = Regex("Capitulo.(\\d+)|Capitulo.(\\d+)\\-")
            val a = li.selectFirst("a") ?: return@mapNotNull null
            val href = fixUrl(a.attr("href"))
            val rawName = a.text()
            val name = rawName.replace(regexep, "").replace("-", "").trim()

            val seasonNum = href.substringAfter("t=", "").toIntOrNull()
            val epNum = regexep.find(rawName)?.destructured?.component1()?.toIntOrNull()

            newEpisode(href) {
                this.name = name
                this.season = seasonNum
                this.episode = epNum
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes) {
            posterUrl = poster?.let { fixUrl(it) }
            backgroundPosterUrl = backposter?.let { fixUrl(it) }
            plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = app.get(data).document
        res.select(".serie-video-informacion iframe").forEach {
            val link = it.attr("src")
                ?.replace("https://short.ink/", "https://abysscdn.com/?v=")
            if (!link.isNullOrBlank()) {
                loadExtractor(link, data, subtitleCallback, callback)
            }
        }
        return true
    }
}
