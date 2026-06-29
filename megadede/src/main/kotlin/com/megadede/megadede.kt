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

        return newHomePageResponse(
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
        val doc = app.get(
            data,
            referer = mainUrl,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36",
                "Referer" to mainUrl
            )
        ).document

        var found = false

        fun fixUrlLocal(raw: String): String {
            val clean = raw.trim()
                .replace("\\/", "/")
                .replace("&amp;", "&")

            return when {
                clean.startsWith("//") -> "https:$clean"
                clean.startsWith("http") -> clean
                clean.startsWith("/") -> mainUrl.removeSuffix("/") + clean
                else -> data.substringBeforeLast("/") + "/" + clean
            }
        }

        suspend fun handle(rawUrl: String) {
            if (rawUrl.isBlank()) return

            val finalUrl = fixUrlLocal(rawUrl)

            when {
                finalUrl.contains("xupalace.org", true) -> {
                    XupalaceExtractor().getUrl(
                        url = finalUrl,
                        referer = data,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                    found = true
                }

                finalUrl.contains("/vidurl/", true) ||
                        finalUrl.contains("embed69", true) -> {
                    Embed69Extractor.load(
                        url = finalUrl,
                        referer = data,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                    found = true
                }

                else -> {
                    val ok = loadExtractor(
                        finalUrl,
                        data,
                        subtitleCallback,
                        callback
                    )

                    if (ok) found = true
                }
            }
        }

        // iframe principal:
        // <iframe id="player" src="https://xupalace.org/video/tt0898266-1x01/">
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
                .ifBlank { iframe.attr("data-src") }
                .trim()

            if (src.isNotBlank() && !src.startsWith("blob:", true)) {
                handle(src)
            }
        }

        // botones Megadede:
        // changeServer('/vidurl/...')
        doc.select("button.server-btn").forEach { btn ->
            val onclick = btn.attr("onclick")

            val serverUrl = Regex("""changeServer\(['"]([^'"]+)['"]""")
                .find(onclick)
                ?.groupValues
                ?.getOrNull(1)

            if (!serverUrl.isNullOrBlank()) {
                handle(serverUrl)
            }
        }

        // directos por si aparecen
        doc.select("video source").forEach { source ->
            val src = source.attr("src").trim()

            if (src.isNotBlank() && !src.startsWith("blob:", true)) {
                handle(src)
            }
        }

        return found
    }
}