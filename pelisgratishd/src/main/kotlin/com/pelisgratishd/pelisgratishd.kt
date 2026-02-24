package com.pelisgratishd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class PelisGratisHd : MainAPI() {
    override var mainUrl = "https://www.pelisgratishd.net"
    override var name = "PelisGratisHD"
    override var lang = "es"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    
    override val mainPage = mainPageOf(
        "$mainUrl/peliculas" to "🎬 Películas Populares",
        "$mainUrl/series" to "📺 Series Populares",
        "$mainUrl/peliculas/genero/accion" to "🔥 Acción",
        "$mainUrl/peliculas/genero/comedia" to "😂 Comedia",
        "$mainUrl/peliculas/genero/drama" to "🎭 Drama",
        "$mainUrl/peliculas/genero/terror" to "👻 Terror",
        "$mainUrl/peliculas/genero/ciencia-ficcion" to "🚀 Ciencia Ficción",
        "$mainUrl/peliculas/genero/animacion" to "🐱 Animación",
        "$mainUrl/series/ano/2025" to "🆕 Series 2025"
    )

    private fun getImage(el: Element?): String? {
        if (el == null) return null
        val attrs = listOf("data-src", "src", "data-original", "srcset")
        for (attr in attrs) {
            val v = el.attr(attr)
            if (v.isNotBlank() && !v.startsWith("data:image")) {
                return v.split(",").first().trim().split(" ").first()
            }
        }
        return null
    }

    private fun extractPoster(document: Document): String? {
        val og = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
        if (!og.isNullOrEmpty()) return fixUrl(og)

        val posterImg = document.selectFirst(".full-poster img")?.attr("src")?.trim()
        if (!posterImg.isNullOrEmpty()) return fixUrl(posterImg)

        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) {
            "${request.data}/page/$page"
        } else {
            request.data
        }
        
        val document = app.get(url).document
        val items = mutableListOf<SearchResponse>()
        
        // LOGICA MEJORADA: Separar claramente películas vs series
        
        // 1. PARA PELÍCULAS - USAR SELECTORES ESPECÍFICOS
        if (request.data.contains("/peliculas")) {
            // Método 1: Buscar en movie-item2 (igual que series pero filtrando)
            document.select(".movie-item2").forEach { element ->
                try {
                    val link = element.selectFirst("a.mi2-in-link") ?: return@forEach
                    val href = link.attr("href").trim()
                    if (href.isBlank() || !href.contains("/ver-")) return@forEach
                    
                    // FILTRAR SOLO PELÍCULAS (no series)
                    if (href.contains("/series/")) return@forEach
                    
                    val title = element.selectFirst(".mi2-title")?.text()?.trim()
                        ?: link.attr("title")?.trim()
                        ?: return@forEach
                    
                    // Filtrar "próximamente"
                    if (title.contains("próximamente", ignoreCase = true)) return@forEach
                    
                    val poster = getImage(element.selectFirst("img"))?.let { fixUrl(it) }
                    
                    items.add(newMovieSearchResponse(title, fixUrl(href)) {
                        posterUrl = poster
                    })
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            
            // Método 2: Buscar en enlaces directos de películas (para páginas de categorías)
            if (items.isEmpty()) {
                document.select("a[href*='/peliculas/ver-']").forEach { link ->
                    try {
                        val href = link.attr("href").trim()
                        if (href.isBlank()) return@forEach
                        
                        val title = link.attr("title")?.trim()
                            ?: link.selectFirst(".mi2-title, .title")?.text()?.trim()
                            ?: link.text().trim()
                        
                        if (title.isBlank() || title.contains("próximamente", ignoreCase = true)) return@forEach
                        
                        val poster = getImage(link.selectFirst("img"))?.let { fixUrl(it) }
                        
                        items.add(newMovieSearchResponse(title, fixUrl(href)) {
                            posterUrl = poster
                        })
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            
            // Método 3: Buscar en grid de películas (para la página principal)
            if (items.isEmpty() && request.data == "$mainUrl/peliculas") {
                document.select(".movie-grid-item, .pelicula-item").forEach { element ->
                    try {
                        val link = element.selectFirst("a") ?: return@forEach
                        val href = link.attr("href").trim()
                        if (href.isBlank() || !href.contains("/ver-")) return@forEach
                        
                        // Solo películas
                        if (href.contains("/series/")) return@forEach
                        
                        val title = element.selectFirst(".movie-title, .title")?.text()?.trim()
                            ?: link.attr("title")?.trim()
                            ?: return@forEach
                        
                        if (title.contains("próximamente", ignoreCase = true)) return@forEach
                        
                        val poster = getImage(element.selectFirst("img"))?.let { fixUrl(it) }
                        
                        items.add(newMovieSearchResponse(title, fixUrl(href)) {
                            posterUrl = poster
                        })
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
        // 2. PARA SERIES - MANTENER LA LÓGICA QUE YA FUNCIONA
        else if (request.data.contains("/series")) {
            document.select(".movie-item2").forEach { element ->
                try {
                    val link = element.selectFirst("a.mi2-in-link") ?: return@forEach
                    val href = link.attr("href").trim()
                    if (href.isBlank() || !href.contains("/ver-")) return@forEach
                    
                    // SOLO SERIES
                    if (!href.contains("/series/")) return@forEach
                    
                    val title = element.selectFirst(".mi2-title")?.text()?.trim()
                        ?: link.attr("title")?.trim()
                        ?: return@forEach
                    
                    if (title.contains("próximamente", ignoreCase = true)) return@forEach
                    
                    val poster = getImage(element.selectFirst("img"))?.let { fixUrl(it) }
                    
                    items.add(newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
                        posterUrl = poster
                    })
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        // 3. PARA LA PÁGINA PRINCIPAL (/) - MOSTRAR AMBOS
        else if (request.data == mainUrl || request.data == "$mainUrl/") {
            // Mostrar tanto películas como series de la página principal
            document.select(".movie-item2, a[href*='/ver-']").forEach { element ->
                try {
                    val link = element.selectFirst("a") ?: element
                    val href = link.attr("href").trim()
                    if (href.isBlank() || !href.contains("/ver-")) return@forEach
                    
                    // Evitar episodios individuales
                    if (href.contains("/ver-episodio-")) return@forEach
                    
                    val title = element.selectFirst(".mi2-title, .title")?.text()?.trim()
                        ?: link.attr("title")?.trim()
                        ?: element.text().trim()
                    
                    if (title.isBlank() || title.contains("próximamente", ignoreCase = true)) return@forEach
                    
                    val poster = getImage(element.selectFirst("img"))?.let { fixUrl(it) }
                    
                    val isSeries = href.contains("/series/")
                    
                    if (isSeries) {
                        items.add(newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
                            posterUrl = poster
                        })
                    } else {
                        items.add(newMovieSearchResponse(title, fixUrl(href)) {
                            posterUrl = poster
                        })
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        
        // Verificar si hay más páginas
        val hasNext = document.select("a[href*='/page/']:contains(SIGUIENTE), .pnext a").isNotEmpty() ||
                     document.select("a[href*='/page/']:contains(2)").isNotEmpty()
        
        return newHomePageResponse(
            list = HomePageList(request.name, items.distinctBy { it.url }, false),
            hasNext = hasNext
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = query.replace(" ", "+")
        val document = app.get("$mainUrl/buscar?q=$encodedQuery").document
        
        val items = mutableListOf<SearchResponse>()
        
        // Buscar en los resultados de búsqueda
        document.select(".movie-item2, a[href*='/ver-']").forEach { element ->
            try {
                val link = element.selectFirst("a[href*='/ver-']") ?: element
                val href = link.attr("href").trim()
                if (href.isBlank()) return@forEach
                
                val title = element.selectFirst(".mi2-title, .side-title")?.text()?.trim()
                    ?: link.attr("title")?.trim()
                    ?: element.text().trim()
                
                if (title.isBlank() || title.contains("Búsqueda", ignoreCase = true)) return@forEach
                
                val poster = getImage(element.selectFirst("img"))?.let { fixUrl(it) }
                
                // Determinar tipo por URL
                val isSeries = href.contains("/series/")
                
                if (isSeries) {
                    items.add(newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
                        posterUrl = poster
                    })
                } else {
                    items.add(newMovieSearchResponse(title, fixUrl(href)) {
                        posterUrl = poster
                    })
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        return items.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        // Obtener título
        val title = document.selectFirst("h1.kino-h, h1.title")?.text()?.trim()
            ?: document.selectFirst("h2.h2-f")?.text()?.trim()
            ?: "Desconocido"
        
        // Limpiar "próximamente" del título si existe
        val cleanTitle = title.replace("próximamente", "", ignoreCase = true).trim()
        
        val poster = extractPoster(document)
        
        // Obtener descripción
        val description = document.selectFirst(".full-desc, .kino-desc p")?.text()?.trim()
            ?: document.selectFirst("p[style*='text-align']")?.text()?.trim()
        
        // Obtener año
        val year = document.selectFirst(".details-f:contains(lanzamiento) a")?.text()?.toIntOrNull()
            ?: document.selectFirst(".details-f:contains(Año) a")?.text()?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""").find(cleanTitle)?.value?.toIntOrNull()
        
        // Obtener géneros
        val genres = document.select(".details-f:contains(Género) a").map { it.text().trim() }
        
        // Verificar si es una serie (temporadas o episodios)
        val hasSeasons = document.select(".seasons").isNotEmpty()
        val hasTemporadaLinks = document.select("a[href*='temporada-']").isNotEmpty()
        val isSeriesPage = url.contains("/series/") && (hasSeasons || hasTemporadaLinks)
        
        if (isSeriesPage) {
            // Es una serie - obtener episodios
            val episodes = mutableListOf<Episode>()
            
            // Buscar temporadas primero
            document.select("a[href*='temporada-']").forEach { seasonLink ->
                try {
                    val seasonUrl = seasonLink.attr("href").trim()
                    if (seasonUrl.isBlank()) return@forEach
                    
                    val seasonNumber = Regex("""temporada-(\d+)""").find(seasonUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    
                    // Cargar la página de la temporada
                    val seasonDoc = app.get(fixUrl(seasonUrl)).document
                    
                    // Buscar episodios en la página de temporada
                    seasonDoc.select("a[href*='ver-episodio-']").forEach { epLink ->
                        try {
                            val epUrl = epLink.attr("href").trim()
                            if (epUrl.isBlank()) return@forEach
                            
                            // Extraer número de episodio de la URL
                            val epMatch = Regex("""episodio-(\d+)""").find(epUrl)
                            val epNumber = epMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                            
                            val epTitle = "Episodio $epNumber"
                            
                            episodes.add(newEpisode(fixUrl(epUrl)) {
                                name = epTitle
                                episode = epNumber
                                season = seasonNumber
                                posterUrl = poster
                            })
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            
            return newTvSeriesLoadResponse(cleanTitle, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                backgroundPosterUrl = poster
                plot = description
                tags = genres
                this.year = year
            }
        } else {
            // Es una película
            return newMovieLoadResponse(cleanTitle, url, TvType.Movie, url) {
                posterUrl = poster
                backgroundPosterUrl = poster
                plot = description
                tags = genres
                this.year = year
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
        var found = false
        
        // MÉTODO PRINCIPAL: Desencriptar hashes (funciona para películas y series)
        document.select(".lien[data-hash]").forEach { element ->
            try {
                val hash = element.attr("data-hash").trim()
                if (hash.isBlank()) return@forEach
                
                // Intentar obtener el enlace usando el hash
                val videoUrl = getVideoUrlFromHash(hash)
                if (!videoUrl.isNullOrBlank()) {
                    loadExtractor(videoUrl, mainUrl, subtitleCallback, callback)
                    found = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        // MÉTODO ALTERNATIVO: Buscar iframes en scripts
        if (!found) {
            document.select("script").forEach { script ->
                val scriptText = script.html()
                
                // Buscar la función playframe que maneja los hashes
                if (scriptText.contains("playframe")) {
                    val hashMatch = Regex("""var hash = \$\(["']\.player-list li div["']\)\.data\(["']hash["']\);""").find(scriptText)
                    if (hashMatch != null) {
                        // Buscar el primer hash en la página
                        document.selectFirst(".lien[data-hash]")?.attr("data-hash")?.let { hash ->
                            val videoUrl = getVideoUrlFromHash(hash)
                            if (!videoUrl.isNullOrBlank()) {
                                loadExtractor(videoUrl, mainUrl, subtitleCallback, callback)
                                found = true
                            }
                        }
                    }
                }
                
                // Buscar enlaces directos en scripts
                val urlPatterns = listOf(
                    """src\s*=\s*["']([^"']+)["']""",
                    """iframe.*?src\s*:\s*["']([^"']+)["']""",
                    """file\s*:\s*["']([^"']+)["']""",
                    """link\s*:\s*["']([^"']+)["']"""
                )
                
                for (pattern in urlPatterns) {
                    try {
                        Regex(pattern, RegexOption.DOT_MATCHES_ALL).findAll(scriptText).forEach { match ->
                            var videoUrl = match.groupValues.getOrNull(1) ?: return@forEach
                            videoUrl = videoUrl.replace("\\/", "/").trim()
                            
                            if (videoUrl.isNotBlank() && 
                                !videoUrl.contains("ads") && 
                                !videoUrl.contains("google")) {
                                
                                if (!videoUrl.startsWith("http")) {
                                    if (videoUrl.startsWith("//")) {
                                        videoUrl = "https:$videoUrl"
                                    } else {
                                        videoUrl = "$mainUrl/$videoUrl"
                                    }
                                }
                                
                                loadExtractor(videoUrl, mainUrl, subtitleCallback, callback)
                                found = true
                            }
                        }
                    } catch (e: Exception) {
                        continue
                    }
                }
            }
        }
        
        // MÉTODO FINAL: Buscar iframes directos
        if (!found) {
            document.select("iframe").forEach { iframe ->
                try {
                    var src = iframe.attr("src").takeIf { it.isNotBlank() }
                        ?: iframe.attr("data-src").takeIf { it.isNotBlank() }
                        ?: return@forEach
                    
                    src = src.trim()
                    
                    if (src.isNotBlank() && !src.contains("ads")) {
                        if (src.startsWith("//")) {
                            src = "https:$src"
                        } else if (!src.startsWith("http")) {
                            src = "$mainUrl/$src"
                        }
                        
                        loadExtractor(src, mainUrl, subtitleCallback, callback)
                        found = true
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        
        return found
    }
    
    private suspend fun getVideoUrlFromHash(hash: String): String? {
        return try {
            // Hacer la petición POST como se ve en el HTML
            val response = app.post(
                "$mainUrl/hashembedlink",
                data = mapOf(
                    "hash" to hash,
                    "_token" to "Ej9BURxPWeVe9LfWohfz3XYKIM92y5NXYF5c7w7D"
                ),
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to mainUrl,
                    "Origin" to mainUrl,
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
                )
            )
            
            if (response.isSuccessful) {
                val text = response.text
                // Buscar el enlace en la respuesta JSON
                // Formato esperado: {"link":"https://...","status":"success"}
                val linkMatch = Regex(""""link"\s*:\s*"([^"]+)"""").find(text)
                linkMatch?.groupValues?.get(1)?.let { url ->
                    return url.replace("\\/", "/")
                }
                
                // Si no encuentra el formato JSON, buscar cualquier URL
                val urlMatch = Regex("""https?://[^\s"']+""").find(text)
                urlMatch?.value
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}