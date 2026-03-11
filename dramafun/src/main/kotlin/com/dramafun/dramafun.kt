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

        val nuevos = getCategory("$mainUrl/newvideos.php").distinctBy { it.url }
        val top = getCategory("$mainUrl/topvideos.php").distinctBy { it.url }
        val peliculas = getCategory("$mainUrl/category.php?cat=peliculas-audio-espanol-latino").distinctBy { it.url }
        val doramas = getCategory("$mainUrl/category.php?cat=Doramas-Sub-Espanol").distinctBy { it.url }

        home.add(HomePageList("Nuevos Episodios", nuevos))
        home.add(HomePageList("Top Videos", top))
        home.add(HomePageList("Películas Latino", peliculas))
        home.add(HomePageList("Doramas Sub Español", doramas))

        return newHomePageResponse(home)
    }

    // ================= CATEGORY / LISTAS =================

    private suspend fun getCategory(url: String): List<SearchResponse> {

        val doc = app.get(url).document

        // Selector principal para categorías con posters (ul#pm-grid li)
        val pmGridItems = doc.select("ul#pm-grid li").mapNotNull { li ->
            li.toSearchResult()
        }

        // Si no hay pm-grid, fallback a enlaces directos
        if (pmGridItems.isEmpty()) {
            return doc.select("a[href*=watch.php?vid=]").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        }

        return pmGridItems.distinctBy { it.url }
    }

    // ================= SEARCH =================

    override suspend fun search(query: String): List<SearchResponse> {

        if (query.isBlank()) return emptyList()

        val doc = app.get("$mainUrl/search.php?keywords=$query").document

        val pmGridItems = doc.select("ul#pm-grid li").mapNotNull { li ->
            li.toSearchResult()
        }

        if (pmGridItems.isEmpty()) {
            return doc.select("a[href*=watch.php?vid=]").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        }

        return pmGridItems.distinctBy { it.url }
    }

    // ================= LOAD =================

    override suspend fun load(url: String): LoadResponse {

        val doc = app.get(url).document

        // Título crudo
        val titleRaw =
            doc.selectFirst("h1")?.text()
                ?: doc.selectFirst("h2")?.text()
                ?: doc.selectFirst(".pm-series-brief h1")?.text()
                ?: "Drama"

        // Limpieza fuerte para nombre de serie/película (sin capítulo ni basura)
        val cleanTitle = titleRaw
            .replace(Regex("(?i)(capitulo|episodio|online|sub español|HD|completo|ver|pelicula|audio latino|en español|\\d+:\\d+:\\d+|\\(\\d+\\)|Temporada \\d+).*"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .removeSurrounding("[", "]")

        // Poster preferido de serie o og:image
        val poster =
            doc.selectFirst(".pm-series-brief img")?.attr("abs:src")
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst(".pm-video-thumb img")?.attr("abs:src")

        val plot = doc.selectFirst(".pm-series-description p, .pm-video-description p, meta[name=description]")?.text()?.trim()

        // Episodios: desktop (ul.s) o mobile (select)
        val episodesDesktop = doc.select("ul.s a[href*=watch.php?vid=]")
        val episodesMobile = doc.select("select.episodeoption option[value*=watch.php?vid=]")
        val episodeElements = if (episodesDesktop.isNotEmpty()) episodesDesktop else episodesMobile

        if (episodeElements.isNotEmpty()) {

            val epList = episodeElements.mapIndexed { index, el ->

                val epUrlRaw = el.attr("href") ?: el.attr("value") ?: ""
                val epUrl = if (epUrlRaw.startsWith("http")) epUrlRaw else "$mainUrl/$epUrlRaw"

                val epText = el.text().trim() ?: el.attr("title") ?: ""
                val epNum = Regex("(?i)capitulo\\s*(\\d+)").find(epText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: (index + 1)

                newEpisode(epUrl) {
                    name = "Capítulo $epNum"
                    episode = epNum
                    season = 1
                }
            }

            return newTvSeriesLoadResponse(
                cleanTitle,  // ← nombre limpio de la serie
                url,
                TvType.TvSeries,
                epList
            ) {
                posterUrl = poster
                this.plot = plot
            }
        }

        // Fallback movie/episodio suelto
        return newMovieLoadResponse(
            cleanTitle,  // ← limpio
            url,
            TvType.Movie,
            url
        ) {
            posterUrl = poster
            this.plot = plot
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

        val enfun = doc.selectFirst("a.xtgo[href*=enfun.php?post=]")?.attr("href")
            ?: doc.select("a[href*=enfun.php?post=]").firstOrNull()?.attr("href")
            ?: return false

        val post = enfun.substringAfter("post=")

        try {
            val decoded = String(Base64.decode(post, Base64.DEFAULT))
            val json = JSONObject(decoded)

            if (json.has("servers")) {
                val servers = json.getJSONObject("servers")
                val keys = servers.keys()

                while (keys.hasNext()) {
                    val key = keys.next()
                    val server = servers.getString(key)
                    loadExtractor(server, data, subtitleCallback, callback)
                }
            }
        } catch (e: Exception) {
            loadExtractor(enfun, data, subtitleCallback, callback)
        }

        return true
    }

    // ================= PARSER =================

    private fun Element.toSearchResult(): SearchResponse? {

        val linkRaw = selectFirst("a")?.attr("href") ?: attr("href") ?: return null
        val link = if (linkRaw.startsWith("http")) linkRaw else "$mainUrl/$linkRaw"

        // Título: preferir .caption h3 a para pm-grid
        val titleRaw = selectFirst(".caption h3 a")?.text()
            ?: selectFirst("h3")?.text()
            ?: ownText().trim().ifEmpty { attr("title") }
            ?: return null

        // Limpieza para home y categorías
        val cleanTitle = titleRaw
            .replace(Regex("(?i)(capitulo|episodio|online|sub español|HD|completo|ver|pelicula|audio latino|en español|\\d+:\\d+:\\d+|\\(\\d+\\))"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .removeSurrounding("[", "]")

        if (cleanTitle.isBlank()) return null

        // Poster: data-echo primero (lazy load en pm-grid)
        val posterRaw = selectFirst("img[data-echo]")?.attr("data-echo")
            ?: selectFirst("img")?.attr("src")
        val poster = if (posterRaw?.startsWith("http") == true) posterRaw else posterRaw?.let { "$mainUrl/$it" }

        return newTvSeriesSearchResponse(
            cleanTitle,
            link
        ) {
            this.posterUrl = poster
        }
    }
}
