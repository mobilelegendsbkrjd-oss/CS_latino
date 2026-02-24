package com.verpeliculasonline

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class VerPeliculasOnline : MainAPI() {
    override var mainUrl = "https://verpeliculasonline.org"
    override var name = "VerPeliculasOnline"
    override var lang = "es"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasDownloadSupport = false

    // Agregamos más categorías
    override val mainPage = mainPageOf(
        "/" to "Inicio",
        "/categoria/peliculas/" to "Películas",
        "/categoria/series/" to "Series",
        "/genero/accion/" to "Acción",
        "/genero/aventura/" to "Aventura",
        "/genero/drama/" to "Drama"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (request.data == "/") {
            mainUrl + if (page > 1) "/page/$page/" else "/"
        } else {
            fixUrl(request.data) + if (page > 1) "page/$page/" else ""
        }
        
        val document = app.get(url).document
        
        val items = document.select("article, .item, .post").mapNotNull { element ->
            parseHomeItem(element)
        }

        return newHomePageResponse(
            list = listOf(HomePageList(request.name, items)),
            hasNext = items.isNotEmpty()
        )
    }

    private fun parseHomeItem(element: Element): SearchResponse? {
        val anchor = element.selectFirst("a") ?: return null
        val href = anchor.attr("href") ?: return null
        val title = element.selectFirst("h2, h3, h4, .title, .entry-title")?.text() ?: return null
        
        // Buscar imagen de múltiples formas
        val img = element.selectFirst("img")
        val poster = when {
            img?.hasAttr("data-src") == true -> img.attr("data-src")
            img?.hasAttr("src") == true -> img.attr("src")
            img?.hasAttr("data-lazy-src") == true -> img.attr("data-lazy-src")
            else -> null
        }

        // Determinar si es serie o película por URL o contenido
        val type = if (href.contains("/serie/") || element.selectFirst(".tvshows") != null) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }

        return if (type == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, href) {
                this.posterUrl = fixUrlNull(poster)
            }
        } else {
            newMovieSearchResponse(title, href, type) {
                this.posterUrl = fixUrlNull(poster)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        
        return document.select("article, .item, .post").mapNotNull { element ->
            parseHomeItem(element)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1, .entry-title")?.text()?.trim() ?: "Sin título"
        
        // Obtener poster de múltiples fuentes
        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: document.selectFirst("img[src*='poster'], .poster img")?.attr("src")
        
        // Obtener año
        val year = Regex("(\\d{4})").find(
            document.selectFirst(".year, .date")?.text() ?: ""
        )?.groupValues?.getOrNull(1)?.toIntOrNull()
        
        // Obtener descripción
        val description = document.selectFirst(".entry-content, .description, .sinopsis")?.text()
            ?: document.selectFirst("meta[name='description']")?.attr("content")
        
        // Verificar si es serie
        val isSeries = url.contains("/serie/") || document.selectFirst(".tvshows, .seasons") != null
        
        return if (isSeries) {
            // Para series, necesitaríamos parsear temporadas y episodios
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, emptyList()) {
                this.posterUrl = fixUrlNull(poster)
                this.year = year
                this.plot = description
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = fixUrlNull(poster)
                this.year = year
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        // Método 1: Buscar iframes directos
        val iframes = document.select("iframe[src]")
        iframes.forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank() && !src.contains("facebook") && !src.contains("twitter")) {
                loadExtractor(fixUrl(src), data, subtitleCallback, callback)
            }
        }
        
        // Método 2: Buscar enlaces en scripts
        document.select("script").forEach { script ->
            val scriptText = script.html()
            // Patrones comunes de reproductores
            val patterns = listOf(
                Regex("""src\s*[:=]\s*['"]([^'"]+)['"]"""),
                Regex("""iframe.*?src\s*=\s*['"]([^'"]+)['"]"""),
                Regex("""(https?://[^\s"']+\.(?:mp4|m3u8))""")
            )
            
            patterns.forEach { pattern ->
                pattern.findAll(scriptText).forEach { match ->
                    val url = match.groupValues[1]
                    if (url.isNotBlank() && url.contains("http")) {
                        loadExtractor(fixUrl(url), data, subtitleCallback, callback)
                    }
                }
            }
        }
        
        // Método 3: Intentar con AJAX (método original)
        try {
            val postId = findPostId(document)
            if (postId != null) {
                val json = app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "doo_player_ajax",
                        "post" to postId,
                        "nume" to "1",
                        "type" to "movie"
                    ),
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                ).text
                
                val embedUrl = Regex("\"embed_url\"\\s*:\\s*\"([^\"]+)\"")
                    .find(json)
                    ?.groupValues?.get(1)
                    ?.replace("\\/", "/")
                
                if (embedUrl != null) {
                    loadExtractor(fixUrl(embedUrl), data, subtitleCallback, callback)
                }
            }
        } catch (e: Exception) {
            // Ignorar error y continuar con otros métodos
        }
        
        return true
    }

    private fun findPostId(doc: org.jsoup.nodes.Document): String? {
        // Buscar ID en múltiples lugares
        doc.select("script").forEach {
            val patterns = listOf(
                Regex("post\\s*:\\s*['\"]?(\\d+)"),
                Regex("post_id\\s*:\\s*['\"]?(\\d+)"),
                Regex("id\\s*:\\s*['\"]?(\\d+)")
            )
            
            patterns.forEach { pattern ->
                val match = pattern.find(it.html())
                if (match != null) return match.groupValues[1]
            }
        }
        
        // Intentar obtener ID de la URL
        Regex("""/(\d+)/""").find(doc.location())?.groupValues?.getOrNull(1)?.let {
            return it
        }
        
        return null
    }
}