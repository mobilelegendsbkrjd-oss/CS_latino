package com.dramafun

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.json.JSONObject

class DramaFun : MainAPI() {

    override var mainUrl = "https://dah.dramafuntv.com"
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

        val nuevos = cleanAndDeduplicate(fetchPaginated("$mainUrl/newvideos.php", maxPages = 3))
        val top = cleanAndDeduplicate(fetchPaginated("$mainUrl/topvideos.php", maxPages = 3))
        val doramasSub = cleanAndDeduplicate(fetchPaginated("$mainUrl/category.php?cat=Doramas-Sub-Espanol", maxPages = 3))
        val novelasTurcasAudio = cleanAndDeduplicate(fetchPaginated("$mainUrl/category.php?cat=series-y-novelas-turcas-en-espanol", maxPages = 3))
        val novelasTurcasSub = cleanAndDeduplicate(fetchPaginated("$mainUrl/category.php?cat=novelas-turcas-subtituladas", maxPages = 3))
        val novelasCompletas = cleanAndDeduplicate(fetchPaginated("$mainUrl/category.php?cat=Novelas-y-Telenovelas-Completas", maxPages = 3))
        val anime = cleanAndDeduplicate(fetchPaginated("$mainUrl/category.php?cat=Anime", maxPages = 3))
        val peliculasLatino = cleanAndDeduplicate(fetchPaginated("$mainUrl/category.php?cat=peliculas-audio-espanol-latino", maxPages = 2))
        val peliculasSub = cleanAndDeduplicate(fetchPaginated("$mainUrl/category.php?cat=peliculas-subtituladas", maxPages = 2))

        home.add(HomePageList("Nuevos Episodios", nuevos))
        home.add(HomePageList("Top Videos", top))
        home.add(HomePageList("Doramas Sub Español", doramasSub))
        home.add(HomePageList("Novelas Turcas Audio", novelasTurcasAudio))
        home.add(HomePageList("Novelas Turcas Sub", novelasTurcasSub))
        home.add(HomePageList("Novelas y Telenovelas Completas", novelasCompletas))
        home.add(HomePageList("Anime", anime))
        home.add(HomePageList("Películas Latino", peliculasLatino))
        home.add(HomePageList("Películas Subtituladas", peliculasSub))

        return newHomePageResponse(home)
    }

    // ================= Fetch con paginación =================
    private suspend fun fetchPaginated(baseUrl: String, maxPages: Int = 3): List<SearchResponse> {
        val allItems = mutableListOf<SearchResponse>()

        for (p in 1..maxPages) {
            val url = if (p == 1) baseUrl else "$baseUrl&page=$p"
            val doc = app.get(url, timeout = 20).document
            val pageItems = doc.select("a[href*=watch.php?vid=]").mapNotNull { it.toSearchResult() }

            if (pageItems.isEmpty()) break
            allItems.addAll(pageItems)
        }

        return allItems
    }

    // ================= Limpieza y deduplicación (mejorada para películas y episodios) =================
    private fun cleanAndDeduplicate(items: List<SearchResponse>): List<SearchResponse> {
        val seenByUrl = mutableSetOf<String>()
        val seenByName = mutableMapOf<String, SearchResponse>()

        items.forEach { item ->
            if (item.url in seenByUrl) return@forEach
            seenByUrl.add(item.url)

            // Regex mejorado para limpiar títulos sucios
            val baseName = item.name
                .replace(Regex("(?i)(ver|online|sub español|latino|HD|completo|audio|en español|capitulo|episodio|final|\\.\\(\\)|\\(\\)|\\d+:\\d+:\\d+|\\(\\d+\\)|\\d+).*"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
                .lowercase()

            if (baseName.isNotBlank()) {
                if (!seenByName.containsKey(baseName)) {
                    // Título limpio para mostrar
                    val cleanDisplayName = item.name
                        .replace(Regex("(?i)(ver|online|sub español|latino|HD|completo|audio|en español|capitulo|episodio|final|\\.\\(\\)|\\(\\)).*"), "")
                        .replace(Regex("\\s+"), " ")
                        .trim()

                    val newItem = newTvSeriesSearchResponse(
                        name = cleanDisplayName.ifBlank { item.name },
                        url = item.url
                    ) {
                        posterUrl = item.posterUrl
                    }

                    seenByName[baseName] = newItem
                }
            } else {
                seenByName[item.url] = item
            }
        }

        return seenByName.values.toList()
    }

    // ================= CATEGORY / LISTAS =================

    private suspend fun getCategory(url: String): List<SearchResponse> {
        val doc = app.get(url).document
        return doc.select("a[href*=watch.php?vid=]").mapNotNull { it.toSearchResult() }
    }

    // ================= SEARCH (deduplicada) =================

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val doc = app.get("$mainUrl/search.php?keywords=$query").document
        val results = doc.select("a[href*=watch.php?vid=]").mapNotNull { it.toSearchResult() }
        return cleanAndDeduplicate(results)
    }

    // ================= LOAD =================

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        // El título del EPISODIO está en h1[itemprop=name]
        val episodeTitle = doc.selectFirst("h1[itemprop=name]")?.text() ?: "Drama"

        // El título de la SERIE está en .pm-series-meta h1.title
        val seriesTitle = doc.selectFirst(".pm-series-meta h1.title")?.text()
            ?: episodeTitle.replace(Regex("(?i)Capitulo.*|Capítulo.*"), "").trim()

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst(".pm-series-brief img")?.attr("abs:src")

        val plot = doc.selectFirst(".pm-series-description .description p")?.text()?.trim()
            ?: doc.selectFirst(".pm-video-description p")?.text()?.trim()

        // Episodios: buscar en el ul con clase 's' (escritorio) o en el select (móvil)
        val episodesDesktop = doc.select("ul.s a[href*=watch.php?vid=]")
        val episodesMobile = doc.select("select.episodeoption option[value*=watch.php?vid=]")
        val episodeElements = if (episodesDesktop.isNotEmpty()) episodesDesktop else episodesMobile

        if (episodeElements.isNotEmpty()) {
            val epList = episodeElements.mapIndexed { index, el ->
                val epUrlRaw = el.attr("href").ifBlank { el.attr("value") }
                val epUrl = if (epUrlRaw.startsWith("http")) epUrlRaw else "$mainUrl/$epUrlRaw"

                // Intentar extraer el número de episodio del título o del texto
                val epText = el.text().trim().ifBlank { el.attr("title") }
                val epNum = Regex("(?i)capitulo\\s*(\\d+)").find(epText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: (index + 1)

                newEpisode(epUrl) {
                    name = "Capítulo $epNum"
                    episode = epNum
                    season = 1
                }
            }

            return newTvSeriesLoadResponse(
                seriesTitle, // Usar el título de la serie, no del episodio
                url,
                TvType.TvSeries,
                epList
            ) {
                posterUrl = poster
                this.plot = plot
            }
        }

        // Si no hay episodios, es una película
        return newMovieLoadResponse(
            seriesTitle,
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
        try {
            val doc = app.get(data, timeout = 20).document

            // === 1. Buscar embeds directamente en watch.php (Tipo B) ===
            var embedUrls = doc.select("ul.WatchList li[data-embed-url]").mapNotNull {
                it.attr("data-embed-url").takeIf { url -> url.isNotBlank() }
            }.toMutableList()

            // === 2. Si no hay, ir a play.php (Tipo A) ===
            if (embedUrls.isEmpty()) {
                val playUrlRaw = doc.selectFirst("a.xtgo[href*=play.php]")?.attr("href")
                    ?: doc.selectFirst("a#play-video[href*=play.php]")?.attr("href")
                    ?: doc.selectFirst("a[href*=play.php]")?.attr("href")

                if (!playUrlRaw.isNullOrBlank()) {
                    val playUrl = if (playUrlRaw.startsWith("http")) playUrlRaw else "$mainUrl/$playUrlRaw"
                    val playDoc = app.get(playUrl, timeout = 20).document

                    embedUrls = playDoc.select("ul.WatchList li[data-embed-url]").mapNotNull {
                        it.attr("data-embed-url").takeIf { url -> url.isNotBlank() }
                    }.toMutableList()
                }
            }

            if (embedUrls.isEmpty()) return false

            // === 3. Cargar extractors ===
            embedUrls.forEach { embedUrl ->
                loadExtractor(embedUrl, data, subtitleCallback, callback)
            }

            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    // ================= PARSER =================

    private fun Element.toSearchResult(): SearchResponse? {

        val linkRaw = selectFirst("a")?.attr("href") ?: attr("href") ?: return null
        val link = if (linkRaw.startsWith("http")) linkRaw else "$mainUrl/$linkRaw"

        val titleRaw = selectFirst("h3")?.text()
            ?: ownText().trim().ifEmpty { attr("title") }
            ?: return null

        // Limpieza básica aquí también
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