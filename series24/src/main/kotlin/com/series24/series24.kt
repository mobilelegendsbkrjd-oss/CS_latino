package com.series24

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.delay
import org.jsoup.nodes.Element

class Series24 : MainAPI() {

    override var mainUrl = "https://series24.app"
    override var name = "Series24"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie,
        TvType.Anime
    )

    // Headers
    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "es-ES,es;q=0.8,en-US;q=0.5,en;q=0.3",
        "Connection" to "keep-alive",
        "Upgrade-Insecure-Requests" to "1"
    )

    private val ajaxHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "*/*",
        "Accept-Language" to "es-ES,es;q=0.8,en-US;q=0.5,en;q=0.3",
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
        "X-Requested-With" to "XMLHttpRequest"
    )

    // =============================
    // HELPERS
    // =============================

    private suspend fun safeGet(url: String, retries: Int = 3): String? {
        repeat(retries) {
            try {
                return app.get(url, headers = headers, timeout = 30).text
            } catch (_: Exception) {
                delay(500)
            }
        }
        return null
    }

    private fun getPoster(el: Element?): String? {
        val style = el?.attr("style") ?: return null
        return style.substringAfter("url(")
            .substringBefore(")")
            .replace("\"", "")
            .replace("'", "")
            .ifEmpty { null }
    }

    private fun getImage(el: Element?): String? {
        if (el == null) return null

        val attrs = listOf(
            "data-src",
            "data-lazy-src",
            "src"
        )

        for (attr in attrs) {
            val src = el.attr(attr)
            if (src.isNotBlank() && !src.startsWith("data:image")) {
                return src
            }
        }

        return null
    }

    // =============================
    // MAIN PAGE
    // =============================

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url = if (page == 1)
            request.data
        else
            "${request.data}/page/$page"

        val doc = app.get(url, headers = headers).document

        val items = doc.select("article.item, div.item, div.list-movie, div.post").mapNotNull { item ->

            val link = item.selectFirst("a")
                ?.attr("href") ?: return@mapNotNull null

            val title = item.selectFirst(".title, h3, h2, .entry-title, .list-title")
                ?.text() ?: return@mapNotNull null

            val poster = getImage(item.selectFirst("img")) ?: getPoster(item.selectFirst(".media-cover, .poster"))

            val type = when {
                link.contains("/pelicula/") || link.contains("/movie/") -> TvType.Movie
                link.contains("/anime/") -> TvType.Anime
                else -> TvType.TvSeries
            }

            when (type) {
                TvType.Movie -> newMovieSearchResponse(title, fixUrl(link), type) {
                    this.posterUrl = poster?.let { fixUrl(it) }
                }
                TvType.TvSeries -> newTvSeriesSearchResponse(title, fixUrl(link), type) {
                    this.posterUrl = poster?.let { fixUrl(it) }
                }
                else -> newAnimeSearchResponse(title, fixUrl(link), type) {
                    this.posterUrl = poster?.let { fixUrl(it) }
                }
            }
        }

        val hasNext = doc.select(".pagination .next, .nav-links .next, .pagination a:contains(Siguiente)").isNotEmpty()

        return HomePageResponse(
            listOf(
                HomePageList(
                    request.name,
                    items,
                    hasNext
                )
            )
        )
    }

    // =============================
    // SEARCH
    // =============================

    override suspend fun search(query: String): List<SearchResponse> {

        val doc = try {
            app.get("$mainUrl/search?s=$query", headers = headers).document
        } catch (_: Exception) {
            try {
                app.post(
                    "$mainUrl/search",
                    headers = ajaxHeaders,
                    data = mapOf(
                        "q" to query,
                        "action" to "search"
                    )
                ).document
            } catch (_: Exception) {
                return emptyList()
            }
        }

        return doc.select("article.item, div.item, div.list-movie, div.post").mapNotNull { item ->

            val link = item.selectFirst("a")
                ?.attr("href") ?: return@mapNotNull null

            val title = item.selectFirst(".title, h3, h2, .entry-title, .list-title")
                ?.text() ?: return@mapNotNull null

            val poster = getImage(item.selectFirst("img")) ?: getPoster(item.selectFirst(".media-cover, .poster"))

            val type = when {
                link.contains("/pelicula/") || link.contains("/movie/") -> TvType.Movie
                link.contains("/anime/") -> TvType.Anime
                else -> TvType.TvSeries
            }

            when (type) {
                TvType.Movie -> newMovieSearchResponse(title, fixUrl(link), type) {
                    this.posterUrl = poster?.let { fixUrl(it) }
                }
                TvType.TvSeries -> newTvSeriesSearchResponse(title, fixUrl(link), type) {
                    this.posterUrl = poster?.let { fixUrl(it) }
                }
                else -> newAnimeSearchResponse(title, fixUrl(link), type) {
                    this.posterUrl = poster?.let { fixUrl(it) }
                }
            }
        }
    }

    // =============================
    // LOAD
    // =============================

    override suspend fun load(url: String): LoadResponse? {

        val doc = app.get(url, headers = headers).document

        val title = doc.selectFirst("h1, .entry-title, .title")
            ?.text() ?: return null

        val poster = getImage(doc.selectFirst(".poster img, .wp-post-image, img"))
            ?: getPoster(doc.selectFirst(".media-cover, .poster"))

        val plot = doc.selectFirst(".description, .entry-content p, .sinopsis, .content p")
            ?.text()

        val year = doc.selectFirst(".year, .date")
            ?.text()?.let {
                Regex("\\d{4}").find(it)?.value?.toIntOrNull()
            }

        val tags = doc.select(".genres a, .category a, .tags a")
            .mapNotNull { it.text() }

        val episodes = mutableListOf<Episode>()

        // Intentar encontrar episodios de diferentes formas
        doc.select(".episode-item a, .episodes a, .temporadas a, .capitulos a").forEach { ep ->

            val link = ep.attr("href")
            val epTitle = ep.text()

            val episodeNum = Regex("\\d+").find(epTitle)?.value?.toIntOrNull()

            episodes.add(
                newEpisode(link) {
                    this.name = epTitle
                    this.episode = episodeNum ?: episodes.size + 1
                }
            )
        }

        // Si no hay episodios pero hay temporadas, intentar cargarlas
        if (episodes.isEmpty()) {
            doc.select(".seasons a, .temporadas a").forEach { season ->
                val seasonLink = season.attr("href")
                val seasonNum = Regex("\\d+").find(season.text())?.value?.toIntOrNull() ?: 1

                try {
                    val seasonDoc = app.get(fixUrl(seasonLink), headers = headers).document
                    seasonDoc.select(".episode-item a, .episodes a, .capitulos a").forEach { ep ->
                        val link = ep.attr("href")
                        val epTitle = ep.text()
                        val episodeNum = Regex("\\d+").find(epTitle)?.value?.toIntOrNull()

                        episodes.add(
                            newEpisode(link) {
                                this.name = epTitle
                                this.season = seasonNum
                                this.episode = episodeNum ?: 1
                            }
                        )
                    }
                } catch (_: Exception) {
                    // Ignorar errores en temporadas individuales
                }
            }
        }

        val type = when {
            url.contains("/pelicula/") || url.contains("/movie/") -> TvType.Movie
            url.contains("/anime/") -> TvType.Anime
            else -> {
                if (episodes.isEmpty()) TvType.Movie else TvType.TvSeries
            }
        }

        return when (type) {
            TvType.Movie -> newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster?.let { fixUrl(it) }
                this.plot = plot
                this.year = year
                this.tags = tags
            }

            TvType.TvSeries -> newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = poster?.let { fixUrl(it) }
                this.plot = plot
                this.year = year
                this.tags = tags
            }

            else -> newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = poster?.let { fixUrl(it) }
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }
    }

    // =============================
    // LOAD LINKS
    // =============================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val doc = app.get(data, headers = headers).document
        var found = false

        // 1. Buscar data-id para AJAX
        val dataId = doc.selectFirst("[data-id], [data-post], [data-embed]")
            ?.attr("data-id") ?: doc.selectFirst("input[name=post_id]")
            ?.attr("value")

        if (!dataId.isNullOrBlank()) {
            try {
                val ajaxResponse = app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    headers = ajaxHeaders,
                    referer = data,
                    data = mapOf(
                        "action" to "get_embed",
                        "id" to dataId,
                        "post_id" to dataId
                    )
                ).text

                // Buscar iframe en respuesta AJAX
                Regex("""src=["'](https?://[^"']+)["']""").findAll(ajaxResponse)
                    .forEach { match ->
                        val iframeUrl = match.groupValues[1]
                        if (iframeUrl.isNotBlank()) {
                            loadExtractor(iframeUrl, data, subtitleCallback, callback)
                            found = true
                        }
                    }

            } catch (_: Exception) {
                // Ignorar error AJAX
            }
        }

        // 2. Buscar iframes directos
        if (!found) {
            doc.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.startsWith("http")) {
                    loadExtractor(src, data, subtitleCallback, callback)
                    found = true
                }
            }
        }

        // 3. Buscar enlaces directos a videos
        if (!found) {
            doc.select("video source, source[type*='video']").forEach { source ->
                val videoUrl = source.attr("src")
                if (videoUrl.startsWith("http")) {
                    // CORREGIDO: newExtractorLink sin parámetro referer (se pasa en el bloque)
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "Video Directo",
                            url = videoUrl
                        ) {
                            this.quality = Qualities.Unknown.value
                            this.referer = data
                        }
                    )
                    found = true
                }
            }
        }

        // 4. Intentar con el método original de Series24 (AJAX específico)
        if (!found) {
            try {
                val id = doc.selectFirst("[data-id]")?.attr("data-id")
                if (!id.isNullOrBlank()) {
                    val res = app.post(
                        "$mainUrl/ajax/embed",
                        headers = ajaxHeaders,
                        referer = data,
                        data = mapOf(
                            "id" to id,
                            "self" to id
                        )
                    ).text

                    Regex("""src=["'](https?://[^"']+)["']""").findAll(res)
                        .forEach { match ->
                            val iframe = match.groupValues[1]
                            if (iframe.startsWith("http")) {
                                loadExtractor(iframe, data, subtitleCallback, callback)
                                found = true
                            }
                        }
                }
            } catch (_: Exception) {
                // Ignorar error del método original
            }
        }

        return found
    }
}