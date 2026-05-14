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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document

        val items = document.select("article.post, ul.post-lst li article, li article.post").mapNotNull { item ->
            try {
                val link = fixUrl(item.selectFirst("a")?.attr("href") ?: return@mapNotNull null)
                val title = item.selectFirst("h3, h2")?.text()?.trim() ?: return@mapNotNull null
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

        return HomePageResponse(listOf(HomePageList(request.name, items)))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=${java.net.URLEncoder.encode(query, "UTF-8")}").document

        return doc.select("article.post, .item").mapNotNull {
            try {
                val link = fixUrl(it.selectFirst("a")?.attr("href") ?: return@mapNotNull null)
                val title = it.selectFirst("h3, h2")?.text()?.trim() ?: return@mapNotNull null
                val poster = getImage(it.selectFirst("img"))

                newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            } catch (_: Exception) { null }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1, title")?.text()?.substringBefore("|") ?: return null
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val desc = doc.selectFirst("p")?.text()

        val isMovie = url.contains("/pelicula")

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                plot = desc
            }
        }

        val episodes = doc.select("a[href*=/capitulo/]").map {
            val epUrl = fixUrl(it.attr("href"))
            newEpisode(epUrl) {
                name = it.text()
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            plot = desc
        }
    }

    // =========================
    // 🔥 FIX REAL AQUÍ
    // =========================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        println("[Main] loadLinks -> $data")

        val document = app.get(data).document
        val html = document.html()

        val links = mutableSetOf<String>()


        fun cleanUrl(url: String): String {
            return url.replace("\\/", "/")
                .replace("&amp;", "&")
                .trim()
        }

        suspend fun process(url: String) {
            if (url.isBlank()) return

            val finalUrl = cleanUrl(
                if (url.startsWith("//")) "https:$url" else url
            )

            println("[loadLinks] -> $finalUrl")

            try {
                when {

                    // 🔥 DOOD (NUEVO)
                    finalUrl.contains("dood", true) ||
                            finalUrl.contains("ds2play", true) ||
                            finalUrl.contains("dsvplay", true) -> {

                        DoodExtractor().getUrl(finalUrl, data, subtitleCallback, callback)

                    }

                    // 🔥 XUPALACE
                    finalUrl.contains("xupalace", true) -> {
                        XupalaceExtractor().getUrl(finalUrl, data, subtitleCallback, callback)

                    }

                    // 🔥 EMBED69
                    finalUrl.contains("embed69", true) -> {
                        Embed69Extractor.load(finalUrl, data, subtitleCallback, callback)

                    }

                    // 🔥 HOSTS NATIVOS
                    finalUrl.contains("vidhide", true) ||
                            finalUrl.contains("filemoon", true) ||
                            finalUrl.contains("voe", true) ||
                            finalUrl.contains("wish", true) ||
                            finalUrl.contains("streamwish", true) -> {

                        loadExtractor(finalUrl, data, subtitleCallback) {

                            callback(it)
                        }
                    }

                    // 🔥 DEFAULT
                    else -> {
                        loadExtractor(finalUrl, data, subtitleCallback) {

                            callback(it)
                        }
                    }
                }
            } catch (e: Exception) {
                println("[loadLinks] error: ${e.message}")
            }
        }

        // iframes
        document.select("iframe").forEach {
            val src = it.attr("src")
            if (src.isNotBlank()) links.add(cleanUrl(src))
        }

        // go_to_playerVast
        Regex("""go_to_playerVast\(['"]([^'"]+)""")
            .findAll(html)
            .forEach {
                links.add(cleanUrl(it.groupValues[1]))
            }

        // direct video
        Regex("""https?://[^\s'"]+\.(m3u8|mp4)""")
            .findAll(html)
            .forEach {
                links.add(cleanUrl(it.value))
            }

        // known hosts
        Regex("""https?://[^\s'"]+(xupalace|vidhide|filemoon|embed69|dood|voe|wish|streamwish)[^\s'"]*""")
            .findAll(html)
            .forEach {
                links.add(cleanUrl(it.value))
            }

        println("[loadLinks] encontrados: ${links.size}")

        val sorted = links.sortedBy {
            when {
                it.contains("vidhide") -> 0
                it.contains("filemoon") -> 1
                it.contains("embed69") -> 2
                it.contains("xupalace") -> 3
                else -> 4
            }
        }

        for (link in sorted) {
            process(link)
        }

        println("[loadLinks] fallback")

        try {
            loadExtractor(data, data, subtitleCallback, callback)
        } catch (_: Exception) {}

        return true
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