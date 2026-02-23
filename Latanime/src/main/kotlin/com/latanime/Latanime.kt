package com.latanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document

class Latanime : MainAPI() {

    override var mainUrl = "https://latanime.org"
    override var name = "Latanime"
    override val hasMainPage = true
    override var lang = "es-mx"
    override val hasDownloadSupport = true
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    private val categoriesJsonUrl =
        "https://raw.githubusercontent.com/mobilelegendsbkrjd-oss/lat_cs_bkrjd/main/ListaLA.json"

    private var cachedGenres: Map<String, String>? = null

    override val mainPage = mainPageOf(
        "emision?p=1" to "📺 En Emisión",
        "animes?fecha=false&genero=false&letra=false&categoria=latino" to "🎙️ Anime Latino",
        "animes?fecha=false&genero=false&letra=false&categoria=anime" to "🇯🇵 Anime Subtitulado",
        "CATEGORIAS_JSON" to "📚 Categorias"
    )

    // =========================
    // PAGINACIÓN REAL
    // =========================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {

        if (request.data == "CATEGORIAS_JSON") {
            return getCategoriesFromJson()
        }

        val document = app.get("$mainUrl/${request.data}&p=$page").document

        val results = document.select("div.row a").mapNotNull {
            it.toSearchResult()
        }

        val hasNext = document.select("a:contains(Siguiente), a[rel=next]").isNotEmpty()

        return newHomePageResponse(
            HomePageList(request.name, results, false),
            hasNext
        )
    }

    // =========================
    // JSON CATEGORIES LIMPIO
    // =========================

    private suspend fun getCategoriesFromJson(): HomePageResponse {

        val items = mutableListOf<SearchResponse>()
        val genreMap = mutableMapOf<String, String>()

        try {
            val jsonText = app.get(categoriesJsonUrl).text
            val cleanJson = jsonText.removePrefix("[").removeSuffix("]")
            val objects = cleanJson.split("},").map { it.trim() + "}" }

            objects.forEach { objStr ->

                val titleMatch = Regex(""""title"\s*:\s*"([^"]*)"""").find(objStr)
                val urlMatch = Regex(""""url"\s*:\s*"([^"]*)"""").find(objStr)

                val rawTitle = titleMatch?.groupValues?.get(1) ?: return@forEach
                val rawUrl = urlMatch?.groupValues?.get(1) ?: return@forEach

                val cleanTitle = rawTitle
                    .replace(Regex("[^A-Za-zÁÉÍÓÚáéíóúñÑ ]"), "")
                    .trim()

                val genreUrl = if (rawUrl.contains("/buscar?q=")) {
                    val genre = rawUrl.substringAfter("/buscar?q=")
                        .normalize()
                    "$mainUrl/animes?genero=$genre"
                } else rawUrl

                genreMap[cleanTitle.normalize()] = genreUrl

                items.add(
                    newAnimeSearchResponse(cleanTitle, genreUrl, TvType.Anime)
                )
            }

            cachedGenres = genreMap

        } catch (_: Exception) {
        }

        return newHomePageResponse(
            HomePageList("📚 Categorias", items, true)
        )
    }

    // =========================
    // 🔥 SEARCH INTELIGENTE
    // =========================

    override suspend fun search(query: String): List<SearchResponse> {

        val normalizedQuery = query.normalize()

        // Cargar géneros si no están en cache
        if (cachedGenres == null) {
            getCategoriesFromJson()
        }

        val genreUrl = cachedGenres?.get(normalizedQuery)

        if (genreUrl != null) {
            val document = app.get(genreUrl).document
            return document.select("div.row a").mapNotNull {
                it.toSearchResult()
            }
        }

        // Si no es género → búsqueda normal
        val document = app.get("$mainUrl/buscar?q=$query").document
        return document.select("div.row a").mapNotNull {
            it.toSearchResult()
        }
    }

    // =========================
    // LOAD
    // =========================

    override suspend fun load(url: String): LoadResponse {

        if (url.contains("/animes?genero=")) {
            return loadCategoryWithRecommendations(url)
        }

        val document = app.get(url).document

        val title = document.selectFirst("h2")?.text() ?: "Desconocido"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("h2 ~ p.my-2")?.text()

        val eps = document.select("div.row a[href*='/ver/']")

        return if (eps.size > 1) {

            val episodes = eps.map {
                newEpisode(it.attr("href"))
            }

            newAnimeLoadResponse(title, url, TvType.Anime) {
                addEpisodes(DubStatus.Subbed, episodes)
                this.posterUrl = poster
                this.plot = description
            }

        } else {

            val movieUrl = eps.firstOrNull()?.attr("href") ?: url

            newMovieLoadResponse(title, url, TvType.AnimeMovie, movieUrl) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    private suspend fun loadCategoryWithRecommendations(url: String): LoadResponse {

        val document = app.get(url).document

        val results = document.select("div.row a").mapNotNull {
            it.toSearchResult()
        }

        return newAnimeLoadResponse("Categoría", url, TvType.Anime) {
            addEpisodes(DubStatus.Subbed, emptyList())
            this.recommendations = results
        }
    }

    // =========================
    // LINKS
    // =========================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document

        document.select("#play-video a").forEach {
            val decoded = base64Decode(it.attr("data-player"))
            val href = Regex("https?://[^\"']+").find(decoded)?.value ?: return@forEach
            loadExtractor(href, "", subtitleCallback, callback)
        }

        return true
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.select("h3").text()
        if (title.isBlank()) return null
        val href = fixUrl(this.attr("href"))
        val poster = fixUrlNull(this.selectFirst("img")?.attr("data-src"))
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    private fun String.normalize(): String {
        return this.lowercase()
            .replace("á", "a")
            .replace("é", "e")
            .replace("í", "i")
            .replace("ó", "o")
            .replace("ú", "u")
            .replace(" ", "")
            .trim()
    }
}