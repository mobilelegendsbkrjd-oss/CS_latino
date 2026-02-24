package com.megadede

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class megadede : MainAPI() {

    override var mainUrl = "https://megadede.mobi"
    override var name = "Megadede"
    override var lang = "es"
    override val hasMainPage = true
    override val supportedTypes =
        setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Cartoon)

    /* ===================== HELPERS ===================== */

    private fun getImage(el: Element?): String? {
        if (el == null) return null
        val attrs = listOf("data-src", "data-lazy", "data-original", "src")
        for (a in attrs) {
            val v = el.attr(a)
            if (v.isNotBlank() && !v.startsWith("data:image"))
                return fixUrl(v)
        }
        return null
    }

    /* ===================== HOME ===================== */

    override val mainPage = mainPageOf(
        "/" to "Episodios Recientes",
        "/peliculas/populares" to "Películas Populares",
        "/peliculas" to "Todas las Películas",
        "/series/populares" to "Series Populares",
        "/series" to "Todas las Series",
        "/animes" to "Animes"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = when {
            page > 1 -> "${fixUrl(request.data)}?page=$page"
            else -> fixUrl(request.data)
        }

        val doc = app.get(url).document

        val items = doc.select("article.mv, article.mv.v.por").mapNotNull {
            val title = it.selectFirst("h2, h4")?.text()?.trim() ?: return@mapNotNull null

            val link = it.selectFirst("a.lnk-blk")?.attr("href")
                ?: it.selectFirst("a")?.attr("href")
                ?: return@mapNotNull null

            val poster = getImage(it.selectFirst("img"))

            val type = when {
                link.contains("/pelicula/") -> TvType.Movie
                link.contains("/anime/") -> TvType.Anime
                link.contains("/serie/") -> TvType.TvSeries
                else -> TvType.TvSeries
            }

            newTvSeriesSearchResponse(title, fixUrl(link), type) {
                posterUrl = poster
                this.year = it.selectFirst("span.op6.db.fz6")?.text()?.toIntOrNull()
            }
        }

        return HomePageResponse(
            listOf(HomePageList(request.name, items))
        )
    }

    /* ===================== SEARCH ===================== */

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/search?s=$query").document

        return doc.select("article.mv, article.mv.v.por").mapNotNull {
            val title = it.selectFirst("h2, h4")?.text()?.trim() ?: return@mapNotNull null

            val link = it.selectFirst("a.lnk-blk")?.attr("href")
                ?: it.selectFirst("a")?.attr("href")
                ?: return@mapNotNull null

            val poster = getImage(it.selectFirst("img"))

            val type = when {
                link.contains("/pelicula/") -> TvType.Movie
                link.contains("/anime/") -> TvType.Anime
                link.contains("/serie/") -> TvType.TvSeries
                else -> TvType.TvSeries
            }

            newTvSeriesSearchResponse(title, fixUrl(link), type) {
                posterUrl = poster
                this.year = it.selectFirst("span.op6.db.fz6")?.text()?.toIntOrNull()
            }
        }
    }

    /* ===================== LOAD ===================== */

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        // Si es un episodio individual
        if (url.contains("/capitulo/")) {
            val title = doc.selectFirst("title")
                ?.text()
                ?.substringBefore(" -")
                ?: "Episodio"

            val poster = getImage(doc.selectFirst("figure.im img"))

            return newMovieLoadResponse(title, url, TvType.TvSeries, url) {
                posterUrl = poster
                backgroundPosterUrl = poster
            }
        }

        val title = doc.selectFirst("h1, h2")?.text() ?: return null
        val poster = getImage(
            doc.selectFirst(".movie-poster img")
                ?: doc.selectFirst("figure.im img")
                ?: doc.selectFirst("div.poster img")
        )

        // Extraer año
        val year = doc.selectFirst(".movie-meta span:containsOwn(20)")?.text()?.toIntOrNull()
            ?: doc.selectFirst("span.op6.db.fz6")?.text()?.toIntOrNull()

        // Extraer descripción
        val description = doc.selectFirst("h2.description")?.text()?.trim()
            ?: doc.selectFirst("meta[property='og:description']")?.attr("content")?.trim()

        // Extraer episodios por temporadas
        val episodes = mutableListOf<Episode>()

        doc.select(".season-container").forEach { seasonContainer ->

            val seasonNumber =
                seasonContainer.attr("data-season")?.toIntOrNull() ?: return@forEach

            seasonContainer.select(".episode-card").forEach { episodeElement ->

                val episodeUrl = episodeElement.attr("href")

                val episodeNumber =
                    episodeElement.selectFirst(".fz5")
                        ?.text()
                        ?.replace("Ep", "")
                        ?.trim()
                        ?.toIntOrNull()

                val episodeTitle =
                    episodeElement.selectFirst("p")
                        ?.text()
                        ?.trim()
                        ?: "Episodio $episodeNumber"

                episodes.add(
                    newEpisode(fixUrl(episodeUrl)) {
                        this.season = seasonNumber       // ✅ TEMPORADA REAL
                        this.episode = episodeNumber    // ✅ EP REAL
                        this.name = episodeTitle
                    }
                )
            }
        } 

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                backgroundPosterUrl = poster
                this.year = year
                plot = description
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                backgroundPosterUrl = poster
                this.year = year
                plot = description
            }
        }
    }

    /* ===================== LINKS ===================== */

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        var found = false

        suspend fun handle(url: String) {
            val finalUrl = if (url.startsWith("//")) "https:$url" else url

            when {
                finalUrl.contains("embed69", true) ->
                    Embed69Extractor.load(finalUrl, mainUrl, subtitleCallback, callback)

                else -> {
                    // Especificar preferencias de idioma de forma segura
                    try {
                        // Intento con preferencias LAT
                        loadExtractor(
                            url = finalUrl,
                            referer = mainUrl,
                            subtitleCallback = subtitleCallback,
                            callback = callback,
                            subtitleAllowed = true,
                            qualifiers = mapOf("preferAudio" to "es")
                        )
                    } catch (e: Exception) {
                        // Si falla, intentamos sin qualifiers
                        loadExtractor(finalUrl, mainUrl, subtitleCallback, callback)
                    }
                }
            }
            found = true
        }

        // Buscar iframes en la página del episodio
        doc.select("iframe").forEach {
            val src = it.attr("src")
            if (src.isNotBlank()) handle(src)
        }

        // También buscar enlaces de video directos si hay
        doc.select("video source").forEach {
            val src = it.attr("src")
            if (src.isNotBlank()) handle(src)
        }

        return found
    }
}