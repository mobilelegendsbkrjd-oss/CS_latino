package com.dramafun

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class DramaFun : MainAPI() {

    override var mainUrl = "https://ww6.dramafuntv.com"
    override var name = "DramaFun"
    override var lang = "es"
    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie
    )

    private val chromeUA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"

    private suspend fun getDoc(url: String) =
        app.get(
            url,
            headers = mapOf("User-Agent" to chromeUA)
        ).document

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val sections = listOf(

            Pair("Popular", "$mainUrl/popular.php"),

            Pair("Doramas Sub Español",
                "$mainUrl/category/doramas-sub-espanol"),

            Pair("Anime",
                "$mainUrl/category/anime"),

            Pair("Series",
                "$mainUrl/category/series"),

            Pair("Reality TV",
                "$mainUrl/category/reality-tv"),

            Pair(
                "Películas Subtituladas",
                "$mainUrl/category/peliculas-subtituladas"
            ),

            Pair(
                "Novelas y Telenovelas Completas",
                "$mainUrl/category.php?cat=Novelas-y-Telenovelas-Completas"
            )
        )

        val lists = sections.map { (title, url) ->

            val doc = getDoc(url)

            val items = doc.select("div.col-xs-6.col-sm-4.col-md-3").mapNotNull {

                val a = it.selectFirst("a") ?: return@mapNotNull null

                val link = fixUrlNull(a.attr("href")) ?: return@mapNotNull null

                val img = it.selectFirst("img")

                val poster = fixUrlNull(img?.attr("src"))

                val name =
                    img?.attr("alt")
                        ?: a.attr("title")
                        ?: return@mapNotNull null

                newTvSeriesSearchResponse(name, link) {
                    this.posterUrl = poster
                }
            }

            HomePageList(title, items)
        }

        return HomePageResponse(lists)
    }

    override suspend fun search(query: String): List<SearchResponse> {

        val doc = getDoc("$mainUrl/search.php?q=$query")

        return doc.select("div.col-xs-6.col-sm-4.col-md-3").mapNotNull {

            val a = it.selectFirst("a") ?: return@mapNotNull null

            val link = fixUrlNull(a.attr("href")) ?: return@mapNotNull null

            val img = it.selectFirst("img")

            val title =
                img?.attr("alt")
                    ?: a.attr("title")
                    ?: return@mapNotNull null

            val poster = fixUrlNull(img.attr("src"))

            newTvSeriesSearchResponse(title, link) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {

        val doc = getDoc(url)

        val title =
            doc.selectFirst("h1")
                ?.text()
                ?: "Drama"

        val poster =
            doc.selectFirst(".img-responsive")
                ?.attr("src")

        val episodes = doc.select("a[href*=\"watch.php\"]").map {

            val epUrl = fixUrl(it.attr("href"))

            newEpisode(epUrl) {
                name = it.text().trim()
            }
        }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes
        ) {
            this.posterUrl = poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val doc = getDoc(data)

        val iframe =
            doc.selectFirst("#Playerholder iframe")
                ?.attr("src")
                ?: return false

        val src = fixUrl(iframe)

        loadExtractor(src, mainUrl, subtitleCallback, callback)

        return true
    }
}
