package com.EntrePeliculasYSeries

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

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

    private fun fixUrl(url: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> mainUrl + url
            else -> "$mainUrl/$url"
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val items = document.select("article.post, ul.post-lst li article, li article.post").mapNotNull { item ->
            try {
                val link = fixUrl(item.selectFirst("a")?.attr("href") ?: return@mapNotNull null)
                val title = item.selectFirst("h3, h2, h4.title")?.text()?.trim()
                    ?: return@mapNotNull null
                val poster = getImage(item.selectFirst("img"))
                val tvType = when {
                    link.contains("/pelicula") -> TvType.Movie
                    link.contains("/anime") -> TvType.Anime
                    else -> TvType.TvSeries
                }
                newTvSeriesSearchResponse(title, link, tvType) {
                    this.posterUrl = poster
                }
            } catch (_: Exception) {
                null
            }
        }
        return newHomePageResponse(listOf(HomePageList(request.name, items)))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/search?s=${java.net.URLEncoder.encode(query, "UTF-8")}").document
        return doc.select("article.post, .item, ul.post-lst li article").mapNotNull {
            try {
                val link = fixUrl(it.selectFirst("a")?.attr("href") ?: return@mapNotNull null)
                val title = it.selectFirst("h3, h2, h4.title")?.text()?.trim()
                    ?: return@mapNotNull null
                val poster = getImage(it.selectFirst("img"))
                val tvType = when {
                    link.contains("/pelicula") -> TvType.Movie
                    link.contains("/anime") -> TvType.Anime
                    else -> TvType.TvSeries
                }
                newTvSeriesSearchResponse(title, link, tvType) {
                    this.posterUrl = poster
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1.movie-title, h1")?.text()?.trim()
            ?: doc.selectFirst("title")?.text()?.substringBefore("|")?.substringBefore("-")?.trim()
            ?: return null

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: getImage(doc.selectFirst(".movie-poster img, img"))

        val desc = doc.selectFirst(".movie-description, meta[property=og:description]")?.let {
            it.attr("content").ifBlank { it.text() }
        }?.trim()

        val isMovie = url.contains("/pelicula")

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = desc
            }
        }

        // ===== SERIES / ANIMES (estructura nueva) =====
        // Episodios vienen como:
        // <a href="...#s1-e1" data-episode-link data-season="1" data-episode="1">
        // o páginas reales: /serie/slug/temporada/1/capitulo/1
        val slug = url.trimEnd('/').substringAfterLast("/")
        val baseSerie = when {
            url.contains("/temporada/") -> url.substringBefore("/temporada/")
            else -> url.substringBefore("#").trimEnd('/')
        }

        val episodes = mutableListOf<Episode>()

        // 1) Cards de la ficha de serie
        doc.select("a[data-episode-link], .episode-card a").forEach { a ->
            val season = a.attr("data-season").toIntOrNull()
                ?: Regex("""#s(\d+)-e(\d+)""").find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
                ?: 1
            val episode = a.attr("data-episode").toIntOrNull()
                ?: Regex("""#s(\d+)-e(\d+)""").find(a.attr("href"))?.groupValues?.get(2)?.toIntOrNull()
                ?: return@forEach

            val epUrl = "$baseSerie/temporada/$season/capitulo/$episode"
            episodes += newEpisode(epUrl) {
                this.name = "Episodio $episode"
                this.season = season
                this.episode = episode
            }
        }

        // 2) Fallback: links directos a /temporada/X/capitulo/Y
        if (episodes.isEmpty()) {
            doc.select("a[href*=/temporada/][href*=/capitulo/]").forEach { a ->
                val href = a.attr("href")
                val m = Regex("""/temporada/(\d+)/capitulo/(\d+)""").find(href) ?: return@forEach
                val season = m.groupValues[1].toInt()
                val episode = m.groupValues[2].toInt()
                episodes += newEpisode(fixUrl(href)) {
                    this.name = a.text().ifBlank { "Episodio $episode" }
                    this.season = season
                    this.episode = episode
                }
            }
        }

        // 3) Último recurso: API bootstrap no trae eps, pero /api/episodio sí confirma total.
        // Si no hay nada, inventamos 1..N no es ideal; mejor devolver lo que haya.
        val unique = episodes.distinctBy { it.data }.sortedWith(
            compareBy({ it.season ?: 1 }, { it.episode ?: 0 })
        )

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, unique) {
            this.posterUrl = poster
            this.plot = desc
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[Main] loadLinks -> $data")
        val document = app.get(data).document
        val html = document.html()
        val links = linkedSetOf<String>()

        fun cleanUrl(url: String): String {
            return url.replace("\\/", "/")
                .replace("&amp;", "&")
                .trim()
        }

        suspend fun process(url: String) {
            if (url.isBlank()) return
            var finalUrl = cleanUrl(
                if (url.startsWith("//")) "https:$url"
                else if (url.startsWith("/")) fixUrl(url)
                else url
            )
            println("[loadLinks] -> $finalUrl")

            try {
                when {
                    // Página intermedia del propio sitio -> Embed69 (links cifrados AES + PoW)
                    finalUrl.contains("/vidurl/", true) ||
                            finalUrl.contains("embed69", true) -> {
                        // Preferimos el extractor dedicado si lo tienes
                        try {
                            Embed69Extractor.load(finalUrl, data, subtitleCallback, callback)
                        } catch (_: Exception) {
                            // Fallback: cargar la página vidurl y pasar hosts que salgan
                            val vidDoc = app.get(finalUrl).document
                            val vidHtml = vidDoc.html()
                            Regex("""https?://[^\s"'<>]+(?:vidhide|streamwish|voe|filemoon|dood)[^\s"'<>]*""", RegexOption.IGNORE_CASE)
                                .findAll(vidHtml)
                                .forEach { loadExtractor(it.value, data, subtitleCallback, callback) }
                            // También intentar loadExtractor directo por si el extractor genérico lo entiende
                            loadExtractor(finalUrl, data, subtitleCallback, callback)
                        }
                    }

                    finalUrl.contains("dood", true) ||
                            finalUrl.contains("ds2play", true) ||
                            finalUrl.contains("dsvplay", true) -> {
                        DoodExtractor().getUrl(finalUrl, data, subtitleCallback, callback)
                    }

                    finalUrl.contains("xupalace", true) -> {
                        XupalaceExtractor().getUrl(finalUrl, data, subtitleCallback, callback)
                    }

                    finalUrl.contains("vidhide", true) ||
                            finalUrl.contains("filemoon", true) ||
                            finalUrl.contains("voe", true) ||
                            finalUrl.contains("wish", true) ||
                            finalUrl.contains("streamwish", true) ||
                            finalUrl.contains("uqload", true) -> {
                        loadExtractor(finalUrl, data, subtitleCallback, callback)
                    }

                    else -> {
                        loadExtractor(finalUrl, data, subtitleCallback, callback)
                    }
                }
            } catch (e: Exception) {
                println("[loadLinks] error: ${e.message}")
            }
        }

        // iframes (ahora suelen ser relativos: /vidurl/ttXXXX-1x01/)
        document.select("iframe").forEach {
            val src = it.attr("src").ifBlank { it.attr("data-src") }
            if (src.isNotBlank() && !src.contains("about:blank") && !src.contains("youtube")) {
                links.add(cleanUrl(src))
            }
        }

        // botones de servidor (data-url="/vidurl/...")
        document.select(".player-tab[data-url], .server-btn[data-url], [data-url*=vidurl]").forEach {
            val u = it.attr("data-url")
            if (u.isNotBlank()) links.add(cleanUrl(u))
        }

        // go_to_playerVast (por si alguna página vieja sigue usándolo)
        Regex("""go_to_playerVast\(['"]([^'"]+)""")
            .findAll(html)
            .forEach { links.add(cleanUrl(it.groupValues[1])) }

        // m3u8 / mp4 directos
        Regex("""https?://[^\s'"]+\.(m3u8|mp4)""")
            .findAll(html)
            .forEach { links.add(cleanUrl(it.value)) }

        // hosts conocidos en el HTML
        Regex("""https?://[^\s"'<>]+(?:xupalace|vidhide|filemoon|embed69|dood|voe|wish|streamwish|uqload)[^\s"'<>]*""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .forEach { links.add(cleanUrl(it.value)) }

        // /vidurl/ relativos en el HTML
        Regex("""["'](/vidurl/[^"']+)["']""")
            .findAll(html)
            .forEach { links.add(cleanUrl(it.groupValues[1])) }

        println("[loadLinks] encontrados: ${links.size} -> $links")

        val sorted = links.sortedBy {
            when {
                it.contains("vidurl", true) || it.contains("embed69", true) -> 0
                it.contains("vidhide", true) -> 1
                it.contains("filemoon", true) -> 2
                it.contains("streamwish", true) || it.contains("wish", true) -> 3
                it.contains("voe", true) -> 4
                else -> 5
            }
        }

        for (link in sorted) {
            process(link)
        }

        // Fallback final
        try {
            loadExtractor(data, data, subtitleCallback, callback)
        } catch (_: Exception) {
        }

        return true
    }
}
