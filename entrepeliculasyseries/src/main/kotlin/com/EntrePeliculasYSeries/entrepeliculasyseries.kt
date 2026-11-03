package com.EntrePeliculasYSeries

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import org.json.JSONObject

class EntrePeliculasYSeries : MainAPI() {
    override var mainUrl = "https://entrepeliculasyseries.nz"
    override var name = "Entre Películas y Series"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Cartoon
    )

    override val mainPage = mainPageOf(
        "$mainUrl" to "Últimos Episodios",
        "$mainUrl/peliculas" to "Películas Recientes",
        "$mainUrl/series" to "Series Recientes",
        "$mainUrl/animes" to "Animes Recientes",
        "$mainUrl/peliculas/populares" to "Películas Populares",
        "$mainUrl/series/populares" to "Series Populares",
        "$mainUrl/animes/populares" to "Animes Populares"
    )

    private fun getImage(el: Element?): String? {
        el ?: return null
        val attrs = listOf("src", "data-src", "data-lazy-src", "srcset")
        for (attr in attrs) {
            val v = el.attr(attr)
            if (v.isNotBlank() && !v.startsWith("data:image")) {
                return v.split(",").first().trim().split(" ").first()
            }
        }
        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val items = document.select("article.post, ul.post-lst li article, li article.post").mapNotNull { item ->
            try {
                val link = fixUrl(item.selectFirst("a")?.attr("href") ?: return@mapNotNull null)
                val title = item.selectFirst("h3, h2, h4")?.text()?.trim() ?: return@mapNotNull null
                val poster = getImage(item.selectFirst("img"))
                val tvType = when {
                    link.contains("/pelicula") -> TvType.Movie
                    link.contains("/anime") -> TvType.Anime
                    else -> TvType.TvSeries
                }
                newTvSeriesSearchResponse(title, link, tvType) {
                    this.posterUrl = poster
                }
            } catch (_: Exception) { null }
        }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=${java.net.URLEncoder.encode(query, "UTF-8")}").document
        return doc.select("article.post, .item").mapNotNull {
            try {
                val link = fixUrl(it.selectFirst("a")?.attr("href") ?: return@mapNotNull null)
                val title = it.selectFirst("h3, h2, h4")?.text()?.trim() ?: return@mapNotNull null
                val poster = getImage(it.selectFirst("img"))
                newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            } catch (_: Exception) { null }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1.movie-title")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: return null
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val desc = doc.selectFirst("meta[name=description]")?.attr("content")
            ?: doc.selectFirst("h2.movie-description")?.text()

        val isMovie = url.contains("/pelicula")
        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                plot = desc
            }
        }

        // --- CAMBIO CLAVE: Extraer episodios desde los enlaces con hash ---
        val episodeElements = doc.select("a[data-episode-link]")
        val episodes = episodeElements.mapNotNull { el ->
            val season = el.attr("data-season").toIntOrNull() ?: return@mapNotNull null
            val episode = el.attr("data-episode").toIntOrNull() ?: return@mapNotNull null
            val epName = el.selectFirst("div")?.text()?.trim() ?: "Episodio $episode"
            // Construir URL con hash para que loadLinks sepa qué episodio es
            val epUrl = "$url#s$season-e$episode"
            newEpisode(epUrl) {
                name = epName
                this.season = season
                this.episode = episode
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            plot = desc
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[Main] loadLinks -> $data")

        // data puede ser: https://entrepeliculasyseries.nz/serie/slug#s1-e1
        val baseUrl = data.substringBefore("#")
        val hash = data.substringAfter("#", "")

        val document = app.get(baseUrl).document
        val context = document.selectFirst("#item-context")
        val type = context?.attr("data-type") ?: "serie"
        val slug = context?.attr("data-slug") ?: return false

        // Parsear temporada/episodio del hash
        var season = 1
        var episode = 1
        if (hash.isNotEmpty()) {
            val match = Regex("""s(\d+)-e(\d+)""").find(hash)
            if (match != null) {
                season = match.groupValues[1].toIntOrNull() ?: 1
                episode = match.groupValues[2].toIntOrNull() ?: 1
            }
        } else {
            season = context?.attr("data-season")?.toIntOrNull() ?: 1
            episode = context?.attr("data-episode")?.toIntOrNull() ?: 1
        }

        println("[loadLinks] type=$type slug=$slug season=$season episode=$episode")

        val links = mutableSetOf<String>()

        // --- CAMBIO CLAVE: Consultar la API de bootstrap ---
        try {
            val apiUrl = "$mainUrl/api/bootstrap/$type/$slug"
            val response = app.get(apiUrl, headers = mapOf("Accept" to "application/json")).text
            println("[loadLinks] API response: ${response.take(500)}")

            val json = JSONObject(response)
            extractLinksFromJson(json, season, episode, links)
        } catch (e: Exception) {
            println("[loadLinks] Error API: ${e.message}")
        }

        // Fallback: buscar en el HTML de la página
        if (links.isEmpty()) {
            val html = document.html()
            document.select("iframe").forEach {
                val src = it.attr("src")
                if (src.isNotBlank()) links.add(cleanUrl(src))
            }
            Regex("""https?://[^\s'"]+\.(m3u8|mp4)""").findAll(html).forEach {
                links.add(cleanUrl(it.value))
            }
            Regex("""https?://[^\s'"]+(vidhide|filemoon|voe|wish|streamwish|dood|xupalace|embed69)[^\s'"]*""")
                .findAll(html).forEach {
                    links.add(cleanUrl(it.value))
                }
        }

        println("[loadLinks] encontrados: ${links.size}")

        for (link in links.sortedBy { linkPriority(it) }) {
            processLink(link, data, subtitleCallback, callback)
        }

        // Fallback final
        if (links.isEmpty()) {
            try {
                loadExtractor(baseUrl, data, subtitleCallback, callback)
            } catch (_: Exception) {}
        }

        return true
    }

    private fun extractLinksFromJson(json: JSONObject, season: Int, episode: Int, links: MutableSet<String>) {
        fun scan(obj: Any?) {
            when (obj) {
                is JSONObject -> {
                    for (key in obj.keys()) {
                        scan(obj.get(key))
                    }
                }
                is org.json.JSONArray -> {
                    for (i in 0 until obj.length()) {
                        scan(obj.get(i))
                    }
                }
                is String -> {
                    if (obj.startsWith("http") && (obj.contains("embed") || obj.contains("player") || obj.contains("vid") || obj.endsWith(".m3u8") || obj.endsWith(".mp4"))) {
                        val lower = obj.lowercase()
                        // Filtrar por temporada/episodio si el string los contiene
                        if ((lower.contains("s${season}e$episode") || lower.contains("t${season}e$episode") ||
                                    (lower.contains("season") && lower.contains("$season") && lower.contains("episode") && lower.contains("$episode"))) ||
                            (!lower.contains("s") && !lower.contains("e"))) {
                            links.add(obj)
                        }
                    }
                }
            }
        }
        scan(json)
    }

    private fun linkPriority(url: String): Int {
        return when {
            url.contains("vidhide") -> 0
            url.contains("filemoon") -> 1
            url.contains("embed69") -> 2
            url.contains("xupalace") -> 3
            url.contains("streamwish") -> 4
            url.contains("voe") -> 5
            url.contains("dood") -> 6
            else -> 10
        }
    }

    private suspend fun processLink(url: String, referer: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        if (url.isBlank()) return
        val finalUrl = cleanUrl(if (url.startsWith("//")) "https:$url" else url)
        println("[loadLinks] procesando -> $finalUrl")
        try {
            when {
                finalUrl.contains("dood", true) || finalUrl.contains("ds2play", true) || finalUrl.contains("dsvplay", true) -> {
                    DoodExtractor().getUrl(finalUrl, referer, subtitleCallback, callback)
                }
                finalUrl.contains("xupalace", true) -> {
                    XupalaceExtractor().getUrl(finalUrl, referer, subtitleCallback, callback)
                }
                finalUrl.contains("embed69", true) -> {
                    Embed69Extractor.load(finalUrl, referer, subtitleCallback, callback)
                }
                else -> {
                    loadExtractor(finalUrl, referer, subtitleCallback, callback)
                }
            }
        } catch (e: Exception) {
            println("[loadLinks] error en $finalUrl: ${e.message}")
        }
    }

    private fun cleanUrl(url: String): String {
        return url.replace("\\/", "/").replace("&amp;", "&").trim()
    }

    private fun fixUrl(url: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> mainUrl + url
            else -> "$mainUrl/$url"
        }
    }
}