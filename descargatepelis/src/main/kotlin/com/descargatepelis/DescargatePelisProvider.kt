package com.descargatepelis

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element
import java.net.URLEncoder

class DescargatePelisProvider : MainAPI() {
    override var mainUrl = "https://descargatepelis.com"
    override var name = "DescargatePelis"
    override val hasMainPage = true
    override var lang = "mx"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        // Principales
        "$mainUrl/estrenos/" to "🔥 Estrenos",
        "$mainUrl/peliculas/" to "Películas",
        "$mainUrl/series/" to "Series",

    )

    // ==================== MAIN PAGE ====================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else {
            if (request.data.endsWith("/")) "${request.data}page/$page/"
            else "${request.data}/page/$page/"
        }

        val doc = app.get(url).document

        val items = doc.select(".trigger-modal, article, .m-item, .poster-card, .item, .collection-item")
            .mapNotNull { toSearchResult(it) }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // ==================== SEARCH ====================
    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/?s=$encoded").document

        return doc.select(".trigger-modal, article, .m-item, .poster-card, .item, h2 a, h3 a")
            .mapNotNull { toSearchResult(it) }
            .distinctBy { it.url }
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        // 1. URL
        val dataLink = element.attr("data-link").ifBlank {
            element.selectFirst("a[href]")?.attr("href") ?: return null
        }
        val href = fixUrl(dataLink)
        if (!href.contains(mainUrl)) return null
        if (href.contains("/category/") || href.contains("/tag/") || href.contains("/page/")) return null

        // 2. Título - prioridad absoluta a data-title
        var title = element.attr("data-title").trim()

        // Si no hay data-title, buscamos en elementos que normalmente tienen el nombre
        if (title.isBlank()) {
            title = element.selectFirst(".poster-title, .title-text, h2, h3, .entry-title, .title")
                ?.text()?.trim() ?: ""
        }

        // Último recurso
        if (title.isBlank()) {
            title = element.selectFirst("a")?.attr("title")?.trim()
                ?: element.selectFirst("a")?.text()?.trim()
                        ?: return null
        }

        // Limpiamos SOLO basura común, sin destruir el título
        title = title
            .replace(Regex("""\s*\(\d{4}\)"""), "")                     // (2026)
            .replace(Regex("""\b(Latino|Castellano|Español|Subtitulado)\b""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\b(WEB-?DL|WEBRip|BluRay|BDRip|HDRip|REMUX|H\.?265|x265|HEVC)\b""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\b(1080p|720p|480p|4K|2160p|60FPS|120FPS)\b""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\bS\d{1,2}E\d{1,2}\b""", RegexOption.IGNORE_CASE), "") // S01E02
            .replace(Regex("""\bTemporada\s*\d+\b""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()

        // Si después de limpiar quedó vacío o solo dice "SERIES", descartamos
        if (title.isBlank() || title.equals("SERIES", ignoreCase = true) || title.equals("PELÍCULAS", ignoreCase = true)) {
            // Intentamos una última vez con data-title original
            title = element.attr("data-title").trim()
                .replace(Regex("""\s*\(\d{4}\)"""), "")
                .trim()
            if (title.isBlank()) return null
        }

        // 3. Poster
        var poster: String? = null
        val dataPoster = element.attr("data-poster")
        if (dataPoster.isNotBlank()) {
            poster = fixUrl(dataPoster)
        } else {
            val img = element.selectFirst("img")
            if (img != null) {
                val src = img.attr("data-src").ifBlank { img.attr("src") }
                if (src.isNotBlank() && !src.contains("data:image")) {
                    poster = fixUrl(src)
                }
            }
        }

        // 4. ¿Es serie?
        val online = element.attr("data-online")
        val isSeries = href.contains("serie", ignoreCase = true) ||
                href.contains("coleccion", ignoreCase = true) ||
                href.contains("saga", ignoreCase = true) ||
                title.contains("Temporada", ignoreCase = true) ||
                online.contains("temporada", ignoreCase = true)

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    // ==================== LOAD ====================
    override suspend fun load(url: String): LoadResponse? {
        // Ya es un player online
        if (url.contains("/online/")) {
            return loadFromOnlinePlayer(url)
        }

        val doc = app.get(url).document
        val html = doc.html()

        // ===== COLECCIONES / SAGAS =====
        if (url.contains("/colecciones") || url.contains("/saga") || url.contains("/coleccion")) {
            return loadCollection(url, doc)
        }

        // Título
        val title = doc.selectFirst("h1, .entry-title, .serie-title")?.text()?.trim()
            ?: Regex("""title:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: return null

        // Poster
        var poster: String? = null
        val posterEl = doc.selectFirst("meta[property=og:image], img.poster, .poster img")
        if (posterEl != null) {
            val src = posterEl.attr("content").ifBlank {
                posterEl.attr("data-src").ifBlank { posterEl.attr("src") }
            }
            if (src.isNotBlank() && !src.startsWith("data:")) {
                poster = fixUrl(src)
            }
        }

        // Plot
        val plot = doc.selectFirst(".entry-content p, .description, .sinopsis, #qv-synopsis")?.text()
            ?: Regex("""synopsis:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)

        // ===== Buscar URL del player online =====
        var onlineUrl: String? = null

        // 1. Desde movieData (películas)
        Regex("""online:\s*"([^"]+)"""").find(html)?.let {
            onlineUrl = fixUrl(it.groupValues[1])
        }

        // 2. data-online
        if (onlineUrl.isNullOrBlank()) {
            doc.select("[data-online]").forEach {
                val o = it.attr("data-online")
                if (o.contains("/online/")) onlineUrl = fixUrl(o)
            }
        }

        // 3. Enlaces <a>
        if (onlineUrl.isNullOrBlank()) {
            doc.select("a[href*=\"/online/\"]").forEach {
                onlineUrl = fixUrl(it.attr("href"))
            }
        }

        val isSeries = url.contains("serie", ignoreCase = true) ||
                title.contains("Temporada", ignoreCase = true) ||
                title.contains("Serie", ignoreCase = true) ||
                (onlineUrl?.contains("temporada", ignoreCase = true) == true)

        // ===== SERIE =====
        if (isSeries && !onlineUrl.isNullOrBlank()) {
            val episodes = try {
                extractEpisodesFromPlayer(onlineUrl!!)
            } catch (_: Exception) {
                emptyList()
            }

            if (episodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            }
        }

        // ===== PELÍCULA =====
        if (!onlineUrl.isNullOrBlank()) {
            // A veces el link viene con -100485, lo limpiamos un poco si es necesario
            var finalOnline = onlineUrl!!

            return newMovieLoadResponse(title, url, TvType.Movie, finalOnline) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        // Fallback
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // ==================== COLECCIONES ====================
    private suspend fun loadCollection(url: String, doc: org.jsoup.nodes.Document): LoadResponse? {
        val title = doc.selectFirst("h1, .entry-title, .serie-title, .collection-title, .page-title")?.text()?.trim()
            ?: "Colección"

        var poster: String? = null
        val posterEl = doc.selectFirst("meta[property=og:image], img.poster, .poster img, .wp-post-image")
        if (posterEl != null) {
            val src = posterEl.attr("content").ifBlank {
                posterEl.attr("data-src").ifBlank { posterEl.attr("src") }
            }
            if (src.isNotBlank() && !src.startsWith("data:")) {
                poster = fixUrl(src)
            }
        }

        val plot = doc.selectFirst(".entry-content p, .description, .sinopsis, .collection-desc")?.text()

        // Selectores más amplios para encontrar las películas de la colección
        val movies = doc.select(
            ".trigger-modal, article, .m-item, .poster-card, .item, .collection-item, " +
                    ".post, .movie-item, .grid-item, .pelicula, a[href*='descargatepelis.com']"
        ).mapNotNull { el ->
            // Buscamos el link
            var link = el.attr("data-link")
            if (link.isBlank()) {
                link = el.selectFirst("a[href]")?.attr("href") ?: el.attr("href")
            }
            if (link.isBlank()) return@mapNotNull null

            val href = fixUrl(link)
            if (!href.contains(mainUrl)) return@mapNotNull null
            if (href.contains("/category/") || href.contains("/tag/") || href.contains("/page/")) return@mapNotNull null
            if (href == url || href.endsWith("/colecciones/") || href.endsWith("/colecciones")) return@mapNotNull null

            // Título
            var movieTitle = el.attr("data-title")
            if (movieTitle.isBlank()) {
                movieTitle = el.selectFirst("h2, h3, .title, .poster-title, .entry-title, .title-text")?.text()?.trim() ?: ""
            }
            if (movieTitle.isBlank()) {
                movieTitle = el.selectFirst("a")?.text()?.trim() ?: el.text().trim()
            }
            if (movieTitle.isBlank() || movieTitle.length < 3) return@mapNotNull null

            val online = el.attr("data-online").takeIf { it.contains("/online/") }

            Triple(movieTitle.trim(), href, online)
        }.distinctBy { it.second }

        if (movies.isEmpty()) return null

        val episodes = movies.mapIndexed { index, (movieTitle, movieUrl, online) ->
            val data = online?.let { fixUrl(it) } ?: movieUrl

            newEpisode(data) {
                this.name = movieTitle
                this.season = 1
                this.episode = index + 1
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    private suspend fun loadFromOnlinePlayer(url: String): LoadResponse? {
        val episodes = extractEpisodesFromPlayer(url)
        if (episodes.isEmpty()) {
            // Puede ser película que llegó directo al player
            return newMovieLoadResponse("Película", url, TvType.Movie, url)
        }

        val doc = app.get(url).document
        val script = doc.select("script").map { it.data() }
            .firstOrNull { it.contains("baseMovieTitle") || it.contains("seriesData") }

        val title = script?.let {
            Regex("""baseMovieTitle\s*=\s*"([^"]+)"""").find(it)?.groupValues?.get(1)
        } ?: doc.selectFirst("h2.series-title-header")?.text() ?: "Serie"

        val poster = script?.let {
            Regex("""posterFondoMaestro\s*=\s*"([^"]+)"""").find(it)?.groupValues?.get(1)
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
        }
    }

    private suspend fun extractEpisodesFromPlayer(playerUrl: String): List<Episode> {
        val doc = app.get(playerUrl).document
        val script = doc.select("script").map { it.data() }
            .firstOrNull { it.contains("seriesData") } ?: return emptyList()

        val seriesDataJson = Regex("""seriesData\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
            .find(script)?.groupValues?.get(1) ?: return emptyList()

        val seriesData = try {
            parseJson<Map<String, List<EpisodeData>>>(seriesDataJson)
        } catch (e: Exception) {
            return emptyList()
        }

        val episodes = mutableListOf<Episode>()
        seriesData.forEach { (seasonName, eps) ->
            val sNum = Regex("(\\d+)").find(seasonName)?.value?.toIntOrNull() ?: 1
            eps.filter { it.has_links }.forEach { ep ->
                val epNum = ep.codigo.split("x").getOrNull(1)?.toIntOrNull() ?: 0
                episodes.add(
                    newEpisode("$playerUrl|${ep.codigo}|${ep.json}") {
                        this.name = ep.codigo
                        this.season = sNum
                        this.episode = epNum
                    }
                )
            }
        }
        return episodes
    }

    // ==================== LOAD LINKS ====================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        // ===== PELÍCULA o EPISODIO DE COLECCIÓN (data = URL del player o de la película) =====
        if (!data.contains("|")) {
            // Si es URL de player online
            if (data.contains("/online/")) {
                return loadLinksFromMoviePlayer(data, subtitleCallback, callback)
            }
            // Si es URL normal de película → primero buscamos el online
            val doc = app.get(data).document
            val onlineMatch = Regex("""online:\s*"([^"]+)"""").find(doc.html())
            if (onlineMatch != null) {
                return loadLinksFromMoviePlayer(fixUrl(onlineMatch.groupValues[1]), subtitleCallback, callback)
            }
            return false
        }

        // ===== SERIE =====
        val parts = data.split("|")
        if (parts.size < 3) return false

        val playerUrl = parts[0]
        val epCode = parts[1]
        val serversJson = parts[2]

        val playerDoc = app.get(playerUrl).document
        val script = playerDoc.select("script").map { it.data() }
            .firstOrNull { it.contains("dpVideoNonce") } ?: return false

        val nonce = Regex("""dpVideoNonce\s*=\s*"([^"]+)"""").find(script)?.groupValues?.get(1) ?: return false
        val postId = Regex("""postIdOnline\s*=\s*"([^"]+)"""").find(script)?.groupValues?.get(1) ?: return false
        val ajaxUrl = Regex("""dpAjaxUrl\s*=\s*"([^"]+)"""").find(script)?.groupValues?.get(1)
            ?: "$mainUrl/wp-admin/admin-ajax.php"

        val servers = try {
            parseJson<Map<String, String>>(serversJson)
        } catch (e: Exception) {
            emptyMap()
        }

        var found = false

        // MTV
        servers["mtv"]?.takeIf { it.isNotBlank() }?.let { mtvUrl ->
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "MTV Premium",
                    url = mtvUrl
                ) {
                    this.referer = playerUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            found = true
        }

        // Resto de servidores
        for (i in 1..6) {
            val sname = servers["sname_$i"] ?: continue
            if (sname.isBlank()) continue

            try {
                val response = app.post(
                    ajaxUrl,
                    data = mapOf(
                        "action" to "get_ofuscated_video",
                        "security" to nonce,
                        "post_id" to postId,
                        "ep_code" to epCode,
                        "server_name" to sname
                    ),
                    referer = playerUrl
                ).parsedSafe<AjaxResponse>()

                val finalUrl = response?.data?.url ?: continue
                if (finalUrl.isBlank()) continue

                loadExtractor(finalUrl, playerUrl, subtitleCallback, callback)

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = sname,
                        url = finalUrl
                    ) {
                        this.referer = playerUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
            } catch (_: Exception) {}
        }

        return found
    }

    private suspend fun loadLinksFromMoviePlayer(
        playerUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playerDoc = app.get(playerUrl).document
        var found = false

        // ===== 1. Servidores normales (tienen data-url) =====
        playerDoc.select(".server-item-btn").forEach { btn ->
            val serverName = btn.text().trim().ifBlank { "Servidor" }
            val isMtv = btn.attr("data-mtv") == "true"
            var videoUrl = btn.attr("data-url").trim()

            if (videoUrl.isBlank()) return@forEach

            // Algunos links vienen relativos
            if (videoUrl.startsWith("/")) {
                videoUrl = fixUrl(videoUrl)
            }

            if (isMtv) {
                // MTV Premium (JW Player nativo)
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "MTV Premium",
                        url = videoUrl
                    ) {
                        this.referer = playerUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
            } else {
                // Servidores normales → primero extractors de CloudStream
                loadExtractor(videoUrl, playerUrl, subtitleCallback, callback)

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = serverName,
                        url = videoUrl
                    ) {
                        this.referer = playerUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
            }
        }

        // ===== 2. Fallback: variables JS (por si no hay botones) =====
        if (!found) {
            val script = playerDoc.select("script").map { it.data() }
                .firstOrNull { it.contains("linkMtvAcf") || it.contains("postIdOnline") }

            if (script != null) {
                // MTV
                Regex("""linkMtvAcf\s*=\s*["']([^"']+)["']""").find(script)?.groupValues?.get(1)?.let { mtv ->
                    if (mtv.isNotBlank()) {
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "MTV Premium",
                                url = mtv
                            ) {
                                this.referer = playerUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        found = true
                    }
                }

                // Intentar AJAX como en series (por si acaso)
                val nonce = Regex("""dpVideoNonce\s*=\s*["']([^"']+)["']""").find(script)?.groupValues?.get(1)
                val postId = Regex("""postIdOnline\s*=\s*["']([^"']+)["']""").find(script)?.groupValues?.get(1)
                val ajaxUrl = Regex("""dpAjaxUrl\s*=\s*["']([^"']+)["']""").find(script)?.groupValues?.get(1)
                    ?: "$mainUrl/wp-admin/admin-ajax.php"

                if (!nonce.isNullOrBlank() && !postId.isNullOrBlank()) {
                    val possibleServers = listOf("DP", "VOE", "MTV", "Streamtape", "Dood", "Filemoon", "Mixdrop")

                    for (sname in possibleServers) {
                        try {
                            val response = app.post(
                                ajaxUrl,
                                data = mapOf(
                                    "action" to "get_ofuscated_video",
                                    "security" to nonce,
                                    "post_id" to postId,
                                    "ep_code" to "movie",   // en películas a veces usan "movie"
                                    "server_name" to sname
                                ),
                                referer = playerUrl
                            ).parsedSafe<AjaxResponse>()

                            val finalUrl = response?.data?.url ?: continue
                            if (finalUrl.isBlank()) continue

                            loadExtractor(finalUrl, playerUrl, subtitleCallback, callback)

                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = sname,
                                    url = finalUrl
                                ) {
                                    this.referer = playerUrl
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            found = true
                        } catch (_: Exception) {}
                    }
                }
            }
        }

        return found
    }

    // ==================== DATA CLASSES ====================
    data class EpisodeData(
        @JsonProperty("codigo") val codigo: String,
        @JsonProperty("json") val json: String,
        @JsonProperty("img") val img: String? = null,
        @JsonProperty("has_links") val has_links: Boolean = false
    )

    data class AjaxResponse(
        @JsonProperty("success") val success: Boolean? = false,
        @JsonProperty("data") val data: AjaxData? = null
    )

    data class AjaxData(
        @JsonProperty("url") val url: String? = null
    )
}