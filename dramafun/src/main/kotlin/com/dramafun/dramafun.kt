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
        TvType.TvSeries
    )

    // ================= HOME =================

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val list = getCategory("$mainUrl/category.php?cat=Novelas-y-Telenovelas-Completas&page=$page")

        return newHomePageResponse(
            listOf(HomePageList("Novelas y Telenovelas", list)),
            false
        )
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

        var fixedUrl = url

        // Si entramos a watch.php → buscar la serie
        if (url.contains("watch.php")) {

            val doc = app.get(url).document

            doc.selectFirst("a[href*=view-serie]")
                ?.attr("href")
                ?.let {
                    fixedUrl = "$mainUrl/$it"
                }
        }

        val doc = app.get(fixedUrl).document

        val title = doc.selectFirst("h1")?.text() ?: "Drama"

        val poster = doc.selectFirst(".pm-video-thumb img")
            ?.attr("src")

        val episodes = doc.select("ul.s a").map {

            val epUrl = "$mainUrl/${it.attr("href")}"

            newEpisode(epUrl) {
                name = "Episodio ${it.text()}"
            }
        }

        return newTvSeriesLoadResponse(
            title,
            fixedUrl,
            TvType.TvSeries,
            episodes
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

        val decoded = String(
            Base64.decode(post, Base64.DEFAULT)
        )

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

        val link = selectFirst("a")?.attr("href") ?: return null

        val title = selectFirst("h3")?.text() ?: return null

        val poster = selectFirst("img.pm-thumb")
            ?.attr("src")

        val fixedLink =
            if (link.startsWith("http"))
                link
            else
                "$mainUrl/$link"

        return newTvSeriesSearchResponse(
            title,
            fixedLink
        ) {
            this.posterUrl = poster
        }
    }
}
