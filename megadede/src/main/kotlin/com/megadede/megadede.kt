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

    /** Quita el hash #sN-eM y normaliza la URL de ficha. */
    private fun cleanContentUrl(raw: String): String {
        val noHash = raw.substringBefore("#")
        return fixUrl(noHash)
    }

    /** Detecta tipo según la ruta. */
    private fun typeFromUrl(url: String): TvType = when {
        url.contains("/pelicula/") -> TvType.Movie
        url.contains("/anime/") -> TvType.Anime
        url.contains("/serie/") -> TvType.TvSeries
        else -> TvType.TvSeries
    }

    /** Extrae slug y tipo desde una URL de ficha. */
    private fun parseSlugAndType(url: String): Pair<String, String>? {
        val path = url.substringAfter(mainUrl).trim('/')
        val parts = path.split('/')
        if (parts.size < 2) return null
        val type = parts[0] // serie | anime | pelicula
        val slug = parts[1].substringBefore("#")
        if (slug.isBlank()) return null
        return type to slug
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

            val rawLink = it.selectFirst("a.lnk-blk")?.attr("href")
                ?: it.selectFirst("a")?.attr("href")
                ?: return@mapNotNull null

            val poster = getImage(it.selectFirst("img"))

            // En "Episodios Recientes" el href suele ser /serie/slug#s1-e8
            // Apuntamos a la ficha de la serie/anime (sin hash) para que load() arme temporadas.
            val link = cleanContentUrl(rawLink)
            val type = typeFromUrl(link)

            // Año real o etiqueta tipo T1E8 — solo usamos números de 4 dígitos
            val yearText = it.selectFirst("span.op6.db.fz6")?.text()?.trim()
            val year = yearText?.takeIf { y -> y.length == 4 }?.toIntOrNull()

            newTvSeriesSearchResponse(title, link, type) {
                posterUrl = poster
                this.year = year
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

            val rawLink = it.selectFirst("a.lnk-blk")?.attr("href")
                ?: it.selectFirst("a")?.attr("href")
                ?: return@mapNotNull null

            val link = cleanContentUrl(rawLink)
            val poster = getImage(it.selectFirst("img"))
            val type = typeFromUrl(link)

            val yearText = it.selectFirst("span.op6.db.fz6")?.text()?.trim()
            val year = yearText?.takeIf { y -> y.length == 4 }?.toIntOrNull()

            newTvSeriesSearchResponse(title, link, type) {
                posterUrl = poster
                this.year = year
            }
        }
    }

    /* ===================== LOAD ===================== */

    override suspend fun load(url: String): LoadResponse? {
        val cleanUrl = cleanContentUrl(url)
        val doc = app.get(cleanUrl).document

        // Capítulo individual: /serie|anime/slug/temporada/N/capitulo/M
        if (cleanUrl.contains("/capitulo/")) {
            val title = doc.selectFirst("h1, h2, title")
                ?.text()
                ?.substringBefore(" -")
                ?.substringBefore(" |")
                ?.trim()
                ?: "Episodio"

            val poster = getImage(
                doc.selectFirst(".movie-poster img")
                    ?: doc.selectFirst("figure.im img")
            )

            return newMovieLoadResponse(title, cleanUrl, TvType.TvSeries, cleanUrl) {
                posterUrl = poster
                backgroundPosterUrl = poster
            }
        }

        val title = doc.selectFirst("h1, h2")?.text()?.trim() ?: return null
        val poster = getImage(
            doc.selectFirst(".movie-poster img")
                ?: doc.selectFirst("figure.im img")
                ?: doc.selectFirst("div.poster img")
        )

        val year = doc.selectFirst(".movie-meta span:containsOwn(20)")?.text()?.toIntOrNull()
            ?: doc.selectFirst("span.op6.db.fz6")?.text()?.toIntOrNull()

        val description = doc.selectFirst("h2.description")?.text()?.trim()
            ?: doc.selectFirst("meta[property='og:description']")?.attr("content")?.trim()

        // Contexto de la ficha (serie / anime)
        val ctx = doc.selectFirst("#item-context")
        val contentType = ctx?.attr("data-type")?.ifBlank { null }
            ?: when {
                cleanUrl.contains("/anime/") -> "anime"
                cleanUrl.contains("/serie/") -> "serie"
                else -> null
            }
        val slug = ctx?.attr("data-slug")?.ifBlank { null }
            ?: parseSlugAndType(cleanUrl)?.second

        val episodes = mutableListOf<Episode>()

        if (contentType != null && slug != null && (contentType == "serie" || contentType == "anime")) {
            // Episodios vienen como <a class="episode-card" data-season="1" data-episode="3" href="...#s1-e3">
            // No tienen URL real de stream: hay que construir /tipo/slug/temporada/S/capitulo/E
            doc.select(".season-container").forEach { seasonContainer ->
                val seasonFromContainer =
                    seasonContainer.attr("data-season").toIntOrNull()

                seasonContainer.select("a.episode-card, .episode-card").forEach { epEl ->
                    val season = epEl.attr("data-season").toIntOrNull()
                        ?: seasonFromContainer
                        ?: return@forEach

                    val episode = epEl.attr("data-episode").toIntOrNull()
                        ?: epEl.selectFirst(".fz5")
                            ?.text()
                            ?.replace(Regex("""(?i)ep\.?\s*"""), "")
                            ?.trim()
                            ?.toIntOrNull()
                        ?: return@forEach

                    val episodeTitle = epEl.selectFirst("p")?.text()?.trim()
                        ?: "Episodio $episode"

                    // URL real del capítulo (página con el player)
                    val episodeUrl =
                        "$mainUrl/$contentType/$slug/temporada/$season/capitulo/$episode"

                    episodes.add(
                        newEpisode(episodeUrl) {
                            this.season = season
                            this.episode = episode
                            this.name = episodeTitle
                        }
                    )
                }
            }

            // Fallback: si no hay .season-container, intentar botones de temporada + API no hace falta
            // porque sin cards no hay lista visible.
        }

        val tvType = when (contentType) {
            "anime" -> TvType.Anime
            "serie" -> TvType.TvSeries
            else -> if (episodes.isNotEmpty()) TvType.TvSeries else TvType.Movie
        }

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, cleanUrl, tvType, episodes) {
                posterUrl = poster
                backgroundPosterUrl = poster
                this.year = year
                plot = description
            }
        } else {
            newMovieLoadResponse(title, cleanUrl, TvType.Movie, cleanUrl) {
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

        // iframe principal (#player o #player-iframe)
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
                .ifBlank { iframe.attr("data-src") }
                .trim()

            if (src.isNotBlank()
                && !src.startsWith("blob:", true)
                && !src.contains("youtube", true)
                && !src.contains("about:blank", true)
            ) {
                handle(src)
            }
        }

        // Botones clásicos: changeServer('/vidurl/...')
        doc.select("button.server-btn, button.ep-player__btn, .player-tab").forEach { btn ->
            val onclick = btn.attr("onclick")
            val dataUrl = btn.attr("data-url")

            val serverUrl = when {
                dataUrl.isNotBlank() -> dataUrl
                else -> Regex("""changeServer\(['"]([^'"]+)['"]""")
                    .find(onclick)
                    ?.groupValues
                    ?.getOrNull(1)
            }

            if (!serverUrl.isNullOrBlank()) {
                handle(serverUrl)
            }
        }

        // <video source>
        doc.select("video source").forEach { source ->
            val src = source.attr("src").trim()
            if (src.isNotBlank() && !src.startsWith("blob:", true)) {
                handle(src)
            }
        }

        return found
    }
}