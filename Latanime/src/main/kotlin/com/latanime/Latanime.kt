package com.latanime

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Latanime : MainAPI() {
    override var mainUrl = "https://latanime.org"
    override var name = "Latanime"
    override val hasMainPage = true
    override var lang = "es"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    override val instantLinkLoading = true
    override val mainPage = mainPageOf(
        "animes?fecha=false&genero=false&letra=false&categoria=latino" to "Anime Latino",
        "animes?fecha=false&genero=false&letra=false&categoria=anime" to "Anime",
        "animes?fecha=false&genero=false&letra=false&categoria=cartoon" to "Cartoons",
        "animes?fecha=false&genero=false&letra=false&categoria=Película%20Latino" to "Película Latino",
        "animes?fecha=false&genero=false&letra=false&categoria=Película" to "Película Subtitulado",
        "animes?fecha=false&genero=false&letra=false&categoria=ova-latino" to "OVA Latino",
        "animes?fecha=false&genero=false&letra=false&categoria=ova" to "OVA",
        "animes?fecha=false&genero=false&letra=false&categoria=especial" to "Especial"
    )
    
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}&p=$page").document
        val items = document.select("div.row a").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        return newHomePageResponse(HomePageList(request.name, items), hasNext = items.isNotEmpty())
    }
    
    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/buscar?q=${query.trim()}").document
        return document.select("div.row a").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }
    
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val rawTitle = document.selectFirst("h2")?.text()?.trim() ?: "Sin título"
        val baseTitle = cleanBaseTitle(rawTitle)
        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = document.selectFirst("h2 ~ p.my-2")?.text()?.trim()
        val tags = document.select("a div.btn").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val year = Regex("""(19|20)\d{2}""").find(document.text())?.value?.toIntOrNull()
        val background = poster
        val recommendations = document.select("div.row a").mapNotNull { it.toSearchResult() }.filter { it.url != url }.take(12)
        val isCartoon = tags.any { it.contains("cartoon", true) }
        val currentSlug = url.substringAfter("/anime/").trimEnd('/')
        val currentLanguage = detectLanguage(currentSlug)
        val normalizedSlug = normalizeSlug(currentSlug)
        val searchQuery = normalizedSlug.replace("-", "+")
        val searchDocument = app.get("$mainUrl/buscar?q=$searchQuery").document
        val relatedUrls = searchDocument
            .select("div.row a")
            .mapNotNull { it.attr("abs:href") }
            .filter { it.contains("/anime/") }
            .distinct()
            .filter { animeUrl ->
                val otherSlug = animeUrl.substringAfter("/anime/").trimEnd('/')
                val otherNormalized = normalizeSlug(otherSlug)
                val otherLanguage = detectLanguage(otherSlug)
                (otherNormalized == normalizedSlug || otherNormalized.contains(normalizedSlug) || normalizedSlug.contains(otherNormalized)) &&
                (isCartoon || otherLanguage == currentLanguage || otherLanguage == "latino")
            }
            .sortedBy { extractSeasonNumber(it.substringAfter("/anime/").trimEnd('/')) }
        
        val allEpisodes = mutableListOf<Episode>()
        relatedUrls.forEach { animeUrl ->
            try {
                val animeDocument = app.get(animeUrl).document
                val seasonTitle = animeDocument.selectFirst("h2")?.text()?.trim() ?: rawTitle
                val slug = animeUrl.substringAfter("/anime/").trimEnd('/')
                val seasonNumber = extractSeasonNumber(slug)
                val episodesRaw = animeDocument.select("a[href*='/ver/']")
                val episodes = episodesRaw.mapIndexed { index, element ->
                    val epUrl = fixUrl(element.attr("href"))
                    newEpisode(epUrl) {
                        this.name = element.text().trim().ifBlank { "Episodio ${index + 1}" }
                        this.episode = index + 1
                        this.season = seasonNumber
                        this.posterUrl = fixUrlNull(element.selectFirst("img")?.getImageAttr())
                        this.description = seasonTitle
                    }
                }
                allEpisodes.addAll(episodes)
            } catch (e: Exception) {}
        }
        
        val finalEpisodes = allEpisodes.distinctBy { it.data }
        val isMovie = finalEpisodes.size <= 1 || rawTitle.contains("pelicula", true) || rawTitle.contains("movie", true)
        
        return if (!isMovie) {
            newAnimeLoadResponse(baseTitle, url, TvType.Anime) {
                posterUrl = poster
                backgroundPosterUrl = background
                this.plot = plot
                this.tags = tags
                this.year = year
                this.recommendations = recommendations
                if (rawTitle.contains("latino", true) || rawTitle.contains("castellano", true)) {
                    addEpisodes(DubStatus.Dubbed, finalEpisodes)
                } else {
                    addEpisodes(DubStatus.Subbed, finalEpisodes)
                }
            }
        } else {
            val movieUrl = finalEpisodes.firstOrNull()?.data ?: url
            newMovieLoadResponse(baseTitle, url, TvType.AnimeMovie, movieUrl) {
                posterUrl = poster
                backgroundPosterUrl = background
                this.plot = plot
                this.tags = tags
                this.year = year
                this.recommendations = recommendations
            }
        }
    }
    
    // ==================== LOAD LINKS TOTALMENTE REPARADO ====================
            override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {

    val document = app.get(
        data,
        headers = mapOf(
            "Referer" to mainUrl,
            "User-Agent" to USER_AGENT
        )
    ).document

    // =========================
    // DATA-KEY REAL
    // =========================
    val dataKey = document
        .selectFirst(".player")
        ?.attr("data-key")
        ?.trim()
        ?: ""

    val servers = document.select(
        "a.repro-item[data-player]"
    )

    if (servers.isEmpty()) {
        return false
    }

    var found = false

    servers.forEach { server ->

        try {

            val serverName = server.text()
                .trim()
                .lowercase()

            val encoded = server
                .attr("data-player")
                .trim()

            if (encoded.isBlank()) {
                return@forEach
            }

            // =========================
            // YOURUPLOAD
            // =========================
            val finalEncoded = if (
                serverName.contains("yourupload")
            ) {
                encoded
            } else {
                dataKey + encoded
            }

            // =========================
            // BASE64
            // =========================
            val decoded = try {

                String(
                    Base64.decode(
                        finalEncoded,
                        Base64.DEFAULT
                    )
                ).trim()

            } catch (_: Exception) {

                return@forEach
            }

            if (!decoded.startsWith("http")) {
                return@forEach
            }

            println("LATANIME -> $serverName")
            println("LATANIME URL -> $decoded")

            // =========================
            // MP4UPLOAD FIX
            // =========================
            val fixedUrl = when {

                decoded.contains(
                    "mp4upload",
                    true
                ) -> {

                    val id = Regex(
                        """(?:embed-|/)([A-Za-z0-9]+)"""
                    ).find(decoded)
                        ?.groupValues
                        ?.getOrNull(1)

                    if (id != null) {
                        "https://www.mp4upload.com/embed-$id.html"
                    } else {
                        decoded
                    }
                }

                else -> decoded
            }

            // =========================
            // LOAD EXTRACTOR
            // =========================
            loadExtractor(
                fixedUrl,
                data,
                subtitleCallback,
                callback = { link ->

                    found = true

                    callback.invoke(
                        link
                    )
                }
            )

        } catch (e: Exception) {

            println(
                "LATANIME ERROR -> ${e.message}"
            )
        }
    }

    return found
}
    
    // ==================== HELPERS ====================
    private fun cleanBaseTitle(title: String): String {
        return title.replace(Regex("""\s*S\d+\s*Latino|\s*Temporada\s*\d+|\s*Season\s*\d+|\s*Latino$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
    
    private fun Element.toSearchResult(): SearchResponse? {
        val title = select("h3").text().trim()
        val href = attr("href").trim()
        if (title.isBlank() || href.isBlank()) return null
        val poster = fixUrlNull(selectFirst("img")?.getImageAttr())
        val isMovie = title.contains("pelicula", true)
        val type = if (isMovie) TvType.AnimeMovie else TvType.Anime
        val isDub = title.contains("latino", true) || title.contains("castellano", true)
        
        return newAnimeSearchResponse(title, fixUrl(href), type) {
            this.posterUrl = poster
            if (isDub) {
                addDubStatus(DubStatus.Dubbed)
            } else {
                addDubStatus(DubStatus.Subbed)
            }
        }
    }
    
    private fun detectLanguage(slug: String): String {
        val s = slug.lowercase()
        return when {
            "latino" in s -> "latino"
            "castellano" in s -> "castellano"
            "dual" in s -> "dual"
            else -> "sub"
        }
    }
    
    private fun normalizeSlug(slug: String): String {
        var result = slug.lowercase()
        result = result.replace(Regex("-s\\d+(-latino)?"), "")
        result = result.replace(Regex("-temporada-\\d+"), "")
        result = result.replace(Regex("-\\d+(nd|rd|th|st)-season"), "")
        result = result.replace(Regex("-(ii|iii|iv|v)$"), "")
        result = result.replace(Regex("-(latino|castellano|sub|dual|doblado)$"), "")
        return result.replace(Regex("--+"), "-").trim('-')
    }
    
    private fun extractSeasonNumber(slug: String): Int {
        val s = slug.lowercase()
        Regex("-s(\\d+)").find(s)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        Regex("-temporada-(\\d+)").find(s)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        Regex("s(\\d+)-latino").find(s)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return when {
            s.contains("temporada-9") || s.contains("s9") -> 9
            s.contains("-ii") || s.contains("2nd") -> 2
            s.contains("-iii") || s.contains("3rd") -> 3
            s.contains("-iv") || s.contains("4th") -> 4
            else -> 1
        }
    }
    
    private fun Element.getImageAttr(): String? {
        listOf("data-src", "src", "data-original").forEach { attr ->
            val value = attr(attr).trim()
            if (value.startsWith("http")) return value
        }
        val srcset = attr("srcset").trim()
        if (srcset.isNotBlank()) return srcset.substringBefore(" ").trim()
        return null
    }
}