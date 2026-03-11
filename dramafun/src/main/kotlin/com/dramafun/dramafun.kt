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

        val nuevos = cleanAndDeduplicate(getCategory("$mainUrl/newvideos.php"))
        val top = cleanAndDeduplicate(getCategory("$mainUrl/topvideos.php"))
        val peliculas = getCategory("$mainUrl/category.php?cat=peliculas-audio-espanol-latino") // películas no deduplicar
        val doramas = cleanAndDeduplicate(getCategory("$mainUrl/category.php?cat=Doramas-Sub-Espanol"))

        home.add(HomePageList("Nuevos Episodios", nuevos))
        home.add(HomePageList("Top Videos", top))
        home.add(HomePageList("Películas Latino", peliculas))
        home.add(HomePageList("Doramas Sub Español", doramas))

        return newHomePageResponse(home)
    }

    // ================= CATEGORY / LISTAS =================

    private suspend fun getCategory(url: String): List<SearchResponse> {

        val doc = app.get(url).document

        return doc.select("a[href*=watch.php?vid=]").mapNotNull { it.toSearchResult() }
    }

    // ================= Función para limpiar y deduplicar por nombre base =================
    private fun cleanAndDeduplicate(items: List<SearchResponse>): List<SearchResponse> {
        val seen = mutableMapOf<String, SearchResponse>() // clave: nombre base limpio

        items.forEach { item ->
            // Nombre base: quitamos capítulo/episodio y basura
            val baseName = item.name
                .replace(Regex("(?i)(capitulo|episodio|online|sub español|HD|completo|ver|pelicula|audio latino|en español|\\d+:\\d+:\\d+|\\(\\d+\\)|\\d+).*"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
                .lowercase()

            if (baseName.isNotBlank() && !seen.containsKey(baseName)) {
                seen[baseName] = item.copy(name = item.name.replace(Regex("(?i)(capitulo|episodio).*"), "").trim()) // título más limpio
            }
        }

        return seen.values.toList()
    }

    // ================= SEARCH =================

    override suspend fun search(query: String): List<SearchResponse> {

        val doc = app.get("$mainUrl/search.php?keywords=$query").document

        return doc.select("a[href*=watch.php?vid=]").mapNotNull { it.toSearchResult() }
    }

    // ================= LOAD =================

    override suspend fun load(url: String): LoadResponse {

        val doc = app.get(url).document

        val title =
            doc.selectFirst("h1")?.text()
                ?: doc.selectFirst("h2")?.text()
                ?: "Drama"

        val cleanTitle = title.replace(Regex("(?i)Capitulo.*|online sub español HD|en Español"), "").trim()

        val poster =
            doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst(".pm-video-thumb img, .pm-series-brief img")?.attr("abs:src")

        val plot = doc.selectFirst(".pm-series-description p, .pm-video-description p")?.text()?.trim()

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
                    season = 1  // solo 1 por ahora
                }
            }

            return newTvSeriesLoadResponse(
                cleanTitle,
                url,
                TvType.TvSeries,
                epList
            ) {
                posterUrl = poster
                this.plot = plot
            }
        }

        return newMovieLoadResponse(
            cleanTitle,
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

        val linkRaw = selectFirst("a")?.attr("href") ?: attr("href") ?: return null
        val link = if (linkRaw.startsWith("http")) linkRaw else "$mainUrl/$linkRaw"

        val titleRaw = selectFirst("h3")?.text()
            ?: ownText().trim().ifEmpty { attr("title") }
            ?: return null

        val cleanTitle = titleRaw.replace(Regex("(?i)\\[|\\]|\\(en\\s*Español\\)|Sub\\s*Español|HD|online"), "").trim()

        val posterRaw = selectFirst("img[data-echo]")?.attr("data-echo")
            ?: selectFirst("img")?.attr("src")
        val poster = if (posterRaw?.startsWith("http") == true) posterRaw
        else posterRaw?.let { "$mainUrl/$it" }

        return newTvSeriesSearchResponse(
            cleanTitle,
            link
        ) {
            this.posterUrl = poster
        }
    }
}
