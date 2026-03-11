package com.dramafun

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.json.JSONObject

class DramaFun : MainAPI() {

    override var mainUrl = "https://ww6.dramafuntv.com"
    override var name = "DramaFun"
    override val hasMainPage = true
    override var lang = "es"

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie
    )

    // ================= HOME =================

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val home = mutableListOf<HomePageList>()

        val top = getCategory("$mainUrl/topvideos.php")
        val peliculas = getCategory("$mainUrl/category.php?cat=peliculas-audio-espanol-latino")
        val doramas = getCategory("$mainUrl/category.php?cat=Doramas-Sub-Espanol")
        val nuevos = getCategory("$mainUrl/newvideos.php")

        home.add(HomePageList("Nuevos Episodios", nuevos))
        home.add(HomePageList("Top Videos", top))
        home.add(HomePageList("Películas Latino", peliculas))
        home.add(HomePageList("Doramas Sub Español", doramas))

        return newHomePageResponse(home)
    }

    // ================= CATEGORY =================

    private suspend fun getCategory(url: String): List<SearchResponse> {

        val doc = app.get(url).document

        return doc.select("ul.pm-ul-browse-videos li")
            .mapNotNull { it.toSearchResult() }
    }

    // ================= SEARCH =================

    override suspend fun search(query: String): List<SearchResponse> {

        val doc = app.get("$mainUrl/search.php?keywords=$query").document

        return doc.select("ul.pm-ul-browse-videos li")
            .mapNotNull { it.toSearchResult() }
    }

    // ================= LOAD =================

    override suspend fun load(url: String): LoadResponse {

        val doc = app.get(url).document

        val title =
            doc.selectFirst("h1")?.text()
                ?: doc.selectFirst("h2")?.text()
                ?: "Drama"

        val cleanTitle = title.replace(Regex("(?i)Capítulo.*|\\(en Español\\)|online sub español HD|en Español"), "").trim()

        val poster =
            doc.selectFirst(".pm-video-thumb img")
                ?.attr("src")

        val episodes = doc.select("ul.s a")

        if (episodes.isNotEmpty()) {

            val epList = episodes.map {

                val epUrl = "\( mainUrl/ \){it.attr("href")}"

                newEpisode(epUrl) {
                    name = "Episodio ${it.text()}"
                }
            }

            return newTvSeriesLoadResponse(
                cleanTitle,
                url,
                TvType.TvSeries,
                epList
            ) {
                posterUrl = poster
            }
        }

        return newMovieLoadResponse(
            cleanTitle,
            url,
            TvType.Movie,
            url
        ) {
            posterUrl = poster
        }
    }

    // ================= LOAD LINKS =================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val doc = app.get(data).document

        val enfun = doc.selectFirst("a.xtgo")?.attr("href")
            ?: return false

        val post = enfun.substringAfter("post=")

        val decoded =
            String(Base64.decode(post, Base64.DEFAULT))

        val json = JSONObject(decoded)

        val servers = json.getJSONObject("servers")

        val keys = servers.keys()

        while (keys.hasNext()) {

            val key = keys.next()

            val server = servers.getString(key)

            loadExtractor(
                server,
                data,
                subtitleCallback,
                callback
            )
        }

        return true
    }

    // ================= PARSER =================

    private fun Element.toSearchResult(): SearchResponse? {

        val link =
            selectFirst("a")?.attr("href") ?: return null

        val title =
            selectFirst("h3")?.text() ?: return null

        // Limpieza de título (sin capítulo ni basura)
        val cleanTitle = title
            .replace(Regex("(?i)\\[|\\]|\\(en\\s*Español\\)|Sub\\s*Español|HD|online|completo|Capítulo \\d+|Episodio \\d+"), "")
            .trim()

        if (cleanTitle.isBlank()) return null

        // Poster: priorizamos data-echo (lazy load), fallback a src
        val posterRaw = selectFirst("img[data-echo]")?.attr("data-echo")
            ?: selectFirst("img")?.attr("src")

        val poster = if (posterRaw?.startsWith("http") == true) posterRaw
        else posterRaw?.let { "$mainUrl/$it" }

        val fixedLink =
            if (link.startsWith("http"))
                link
            else
                "$mainUrl/$link"

        return newTvSeriesSearchResponse(
            cleanTitle,
            fixedLink
        ) {
            this.posterUrl = poster
        }
    }
}
