package com.pelisgratishd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Pelisgratishd : MainAPI() {
    override var mainUrl = "https://www.pelisgratishd.net"
    override var name = "PelisGratisHD"
    override var lang = "es"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "🏠 Estrenos/Recién Agregados",
        "$mainUrl/peliculas" to "🎬 Películas",
        "$mainUrl/series" to "📺 Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}?page=$page" else request.data
        val document = app.get(url).document
        
        val items = when {
            url.contains("/peliculas") -> {
                // Para página de películas: extraer de .movie-item2
                document.select("div.movie-item2").mapNotNull {
                    val titleElement = it.selectFirst(".mi2-title")
                    val title = titleElement?.text()?.trim() ?: return@mapNotNull null
                    
                    val linkElement = it.selectFirst("a.mi2-in-link")
                    val href = linkElement?.attr("href") ?: return@mapNotNull null
                    
                    val fullUrl = fixUrl(href)
                    
                    // Extraer imagen de img dentro de .mi2-img
                    val imgElement = it.selectFirst(".mi2-img img")
                    val poster = imgElement?.attr("src")?.trim()
                    
                    newMovieSearchResponse(title, fullUrl, TvType.Movie) {
                        posterUrl = poster
                        // Extraer calidad si existe
                        val qualityElement = it.selectFirst(".mi2-version")
                        if (qualityElement?.text()?.contains("hd", true) == true) {
                            this.quality = SearchQuality.HD
                        }
                    }
                }
            }
            url.contains("/series") -> {
                // Para página de series: extraer de .side-movie
                document.select("a.side-movie").mapNotNull {
                    val titleElement = it.selectFirst(".mi2-title")
                    val title = titleElement?.text()?.trim() ?: return@mapNotNull null
                    
                    val href = it.attr("href")
                    val fullUrl = fixUrl(href)
                    
                    val imgElement = it.selectFirst(".side-movie-img img")
                    val poster = imgElement?.attr("src")?.trim()
                    
                    newTvSeriesSearchResponse(title, fullUrl, TvType.TvSeries) {
                        posterUrl = poster
                    }
                }
            }
            else -> {
                // Para página principal: combinar películas y series
                val movies = document.select("div.side-item").mapNotNull {
                    val titleElement = it.selectFirst(".side-title")
                    val title = titleElement?.text()?.trim() ?: return@mapNotNull null
                    
                    val href = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                    val fullUrl = fixUrl(href)
                    
                    val imgElement = it.selectFirst(".side-img img")
                    val poster = imgElement?.attr("src")?.trim()
                    
                    val isMovie = fullUrl.contains("/peliculas/")
                    val type = if (isMovie) TvType.Movie else TvType.TvSeries
                    
                    if (isMovie) {
                        newMovieSearchResponse(title, fullUrl, type) {
                            posterUrl = poster
                        }
                    } else {
                        newTvSeriesSearchResponse(title, fullUrl, type) {
                            posterUrl = poster
                        }
                    }
                }
                
                val series = document.select("a.side-movie").mapNotNull {
                    val titleElement = it.selectFirst(".mi2-title")
                    val title = titleElement?.text()?.trim() ?: return@mapNotNull null
                    
                    val href = it.attr("href")
                    val fullUrl = fixUrl(href)
                    
                    val imgElement = it.selectFirst(".side-movie-img img")
                    val poster = imgElement?.attr("src")?.trim()
                    
                    newTvSeriesSearchResponse(title, fullUrl, TvType.TvSeries) {
                        posterUrl = poster
                    }
                }
                
                movies + series
            }
        }.distinctBy { it.url }.take(30)

        val hasNext = items.isNotEmpty() && document.select("a:contains(SIGUIENTE)").isNotEmpty()
        
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = false
            ),
            hasNext = hasNext
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = query.replace(" ", "+")
        val document = app.get("$mainUrl/buscar?q=$encodedQuery").document
        
        // Buscar en todos los elementos posibles
        return document.select("div.movie-item2, div.side-item, a.side-movie").mapNotNull {
            when {
                it.select("div.movie-item2").isNotEmpty() -> {
                    val titleElement = it.selectFirst(".mi2-title")
                    val title = titleElement?.text()?.trim() ?: return@mapNotNull null
                    
                    val linkElement = it.selectFirst("a.mi2-in-link")
                    val href = linkElement?.attr("href") ?: return@mapNotNull null
                    
                    val fullUrl = fixUrl(href)
                    val imgElement = it.selectFirst("img")
                    val poster = imgElement?.attr("src")?.trim()
                    
                    newMovieSearchResponse(title, fullUrl, TvType.Movie) {
                        posterUrl = poster
                    }
                }
                it.select("div.side-item").isNotEmpty() -> {
                    val titleElement = it.selectFirst(".side-title")
                    val title = titleElement?.text()?.trim() ?: return@mapNotNull null
                    
                    val href = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                    val fullUrl = fixUrl(href)
                    
                    val imgElement = it.selectFirst("img")
                    val poster = imgElement?.attr("src")?.trim()
                    
                    val isMovie = fullUrl.contains("/peliculas/")
                    val type = if (isMovie) TvType.Movie else TvType.TvSeries
                    
                    if (isMovie) {
                        newMovieSearchResponse(title, fullUrl, type) {
                            posterUrl = poster
                        }
                    } else {
                        newTvSeriesSearchResponse(title, fullUrl, type) {
                            posterUrl = poster
                        }
                    }
                }
                it.attr("class").contains("side-movie") -> {
                    val titleElement = it.selectFirst(".mi2-title")
                    val title = titleElement?.text()?.trim() ?: return@mapNotNull null
                    
                    val href = it.attr("href")
                    val fullUrl = fixUrl(href)
                    
                    val imgElement = it.selectFirst("img")
                    val poster = imgElement?.attr("src")?.trim()
                    
                    newTvSeriesSearchResponse(title, fullUrl, TvType.TvSeries) {
                        posterUrl = poster
                    }
                }
                else -> null
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // Extraer título - método mejorado
        val title = document.selectFirst("h1.kino-h")?.text()
            ?: document.selectFirst("h2.h2-f")?.text()
            ?: document.selectFirst("title")?.text()
                ?.replace("Ver ", "")?.replace(" Online.*".toRegex(), "")?.trim()
            ?: "Desconocido"
        
        // Extraer póster - del HTML real
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            ?: document.selectFirst("img[src*='themoviedb']")?.attr("src")?.trim()
            ?: document.selectFirst(".full-poster img")?.attr("src")?.trim()
            ?: document.selectFirst(".th-in_ img")?.attr("src")?.trim()
            ?: document.selectFirst("img")?.attr("src")?.takeIf { it.contains("themoviedb") }?.trim()
        
        // Extraer descripción
        val description = document.selectFirst(".kino-desc")?.text()?.trim()
            ?: document.selectFirst(".full-text p")?.text()?.trim()
            ?: document.selectFirst(".smart-text p")?.text()?.trim()
        
        // Extraer año
        val year = document.selectFirst(".details-f:contains(Fecha) a")?.text()?.toIntOrNull()
            ?: document.selectFirst(".details-f:contains(estreno) a")?.text()?.toIntOrNull()
            ?: Regex("""(\d{4})""").find(title)?.value?.toIntOrNull()
        
        // Extraer géneros
        val genres = document.select(".details-f:contains(Género) a").map { it.text().trim() }
        
        // Verificar si es serie por los episodios
        val episodes = document.select(".seasons-box-items a[href*='/ver-episodio-']").mapNotNull { epElement ->
            val epUrl = epElement.attr("href")
            val epTitle = epElement.select(".side-seas-title").text().takeIf { it.isNotBlank() }
                ?: epElement.select(".side-seas-over_2").text().takeIf { it.isNotBlank() }
                ?: "Episodio"
            
            val epNumber = Regex("""episodio-(\d+)""").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""e(\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                ?: 1
            
            val seasonNumber = Regex("""temporada-(\d+)""").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
                ?: 1
            
            newEpisode(fixUrl(epUrl)) {
                name = epTitle
                episode = epNumber
                season = seasonNumber
            }
        }.reversed()
        
        // Determinar tipo de contenido
        val isSeries = episodes.isNotEmpty() || 
                      (url.contains("/series/") && url.contains("/temporada-")) ||
                      document.select("select[name=season]").isNotEmpty()
        
        return if (isSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                backgroundPosterUrl = poster
                plot = description
                this.tags = genres
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                backgroundPosterUrl = poster
                plot = description
                this.tags = genres
                this.year = year
            }
        }
    }

    private suspend fun getVideoUrlFromHash(hash: String): String? {
        return try {
            val response = app.post(
                "$mainUrl/hashembedlink",
                data = mapOf(
                    "hash" to hash,
                    "_token" to "Ej9BURxPWeVe9LfWohfz3XYKIM92y5NXYF5c7w7D"
                ),
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            )
            
            // Parsear respuesta JSON
            val jsonText = response.text
            val linkMatch = Regex("\"link\"\\s*:\\s*\"([^\"]+)\"").find(jsonText)
            linkMatch?.groupValues?.get(1)
        } catch (e: Exception) {
            null
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
        
        // Procesar servidores con hash
        document.select(".player-list li div.lien").forEach { serverElement ->
            val hash = serverElement.attr("data-hash")
            if (hash.isNotBlank()) {
                try {
                    val videoUrl = getVideoUrlFromHash(hash)
                    if (videoUrl != null && videoUrl.isNotBlank()) {
                        found = true
                        loadExtractor(videoUrl, mainUrl, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    // Ignorar error
                }
            }
        }
        
        // También intentar con iframes por si acaso
        if (!found) {
            document.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank()) {
                    val finalSrc = if (src.startsWith("//")) "https:$src" else src
                    loadExtractor(finalSrc, mainUrl, subtitleCallback, callback)
                    found = true
                }
            }
        }
        
        return found
    }
}