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

        // Categorías chingonas agregadas
        val nuevos = getCategory("$mainUrl/newvideos.php")
        val top = getCategory("$mainUrl/topvideos.php")
        val doramasSub = getCategory("$mainUrl/category.php?cat=Doramas-Sub-Espanol")
        val novelasTurcasAudio = getCategory("$mainUrl/category.php?cat=series-y-novelas-turcas-en-espanol")
        val novelasTurcasSub = getCategory("$mainUrl/category.php?cat=novelas-turcas-subtituladas")
        val novelasCompletas = getCategory("$mainUrl/category.php?cat=Novelas-y-Telenovelas-Completas")
        val anime = getCategory("$mainUrl/category.php?cat=Anime")
        val peliculasLatino = getCategory("$mainUrl/category.php?cat=peliculas-audio-espanol-latino")
        val peliculasSub = getCategory("$mainUrl/category.php?cat=peliculas-subtituladas")
        val series = getCategory("$mainUrl/category.php?cat=Series")
        val reality = getCategory("$mainUrl/category.php?cat=Reality-TV")

        // Agregamos solo las que tengan contenido
        if (nuevos.isNotEmpty()) home.add(HomePageList("Nuevos Episodios", deduplicateSeries(nuevos)))
        if (top.isNotEmpty()) home.add(HomePageList("Top Videos", deduplicateSeries(top)))
        if (doramasSub.isNotEmpty()) home.add(HomePageList("Doramas Sub Español", deduplicateSeries(doramasSub)))
        if (novelasTurcasAudio.isNotEmpty()) home.add(HomePageList("Novelas Turcas Audio", deduplicateSeries(novelasTurcasAudio)))
        if (novelasTurcasSub.isNotEmpty()) home.add(HomePageList("Novelas Turcas Sub", deduplicateSeries(novelasTurcasSub)))
        if (novelasCompletas.isNotEmpty()) home.add(HomePageList("Novelas y Telenovelas Completas", deduplicateSeries(novelasCompletas)))
        if (anime.isNotEmpty()) home.add(HomePageList("Anime", deduplicateSeries(anime)))
        if (peliculasLatino.isNotEmpty()) home.add(HomePageList("Películas Latino", peliculasLatino)) // películas no necesitan deduplicar
        if (peliculasSub.isNotEmpty()) home.add(HomePageList("Películas Subtituladas", peliculasSub))
        if (series.isNotEmpty()) home.add(HomePageList("Series", deduplicateSeries(series)))
        if (reality.isNotEmpty()) home.add(HomePageList("Reality TV", deduplicateSeries(reality)))

        return newHomePageResponse(home)
    }

    // Función para mostrar solo 1 entrada por serie (la más reciente)
    private fun deduplicateSeries(list: List<SearchResponse>): List<SearchResponse> {
        val seen = mutableMapOf<String, SearchResponse>()
        list.forEach { item ->
            val key = item.name.lowercase().trim() // clave por nombre limpio
            if (!seen.containsKey(key)) {
                seen[key] = item
            }
        }
        return seen.values.toList()
    }

    // ================= CATEGORY / LISTAS =================

    private suspend fun getCategory(url: String): List<SearchResponse> {

        val doc = app.get(url).document

        return doc.select("a[href*=watch.php?vid=]").mapNotNull { it.toSearchResult() }
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
                    season = 1
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

        // Imagen: priorizamos data-echo para carátulas lazy load
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
