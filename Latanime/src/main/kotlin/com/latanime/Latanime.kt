package com.latanime

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Latanime : MainAPI() {
    override var mainUrl = "https://latanime.org"
    override var name = "Latanime"
    override val hasMainPage = true
    override var lang = "mx"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    override val instantLinkLoading = true

    override val mainPage = mainPageOf(
        "animes?fecha=false&genero=false&letra=false&categoria=latino" to "Anime Latino",
        "animes?fecha=false&genero=false&letra=false&categoria=anime" to "Anime",
        "animes?fecha=false&genero=false&letra=false&categoria=Cartoon" to "Cartoons",
        "animes?fecha=false&genero=false&letra=false&categoria=Película%20Latino" to "Película Latino",
        "animes?fecha=false&genero=false&letra=false&categoria=Película" to "Película Subtitulado",
        "emision?p=1" to "En Emisión",
        "animes?fecha=false&genero=false&letra=false&categoria=ova-latino" to "OVA Latino",
        "animes?fecha=false&genero=false&letra=false&categoria=ova" to "OVA",
        "animes?fecha=false&genero=false&letra=false&categoria=especial" to "Especial"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(buildPageUrl(request.data, page)).document

        val items = document.getAnimeCards()
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(
            HomePageList(request.name, items),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/buscar?q=${query.trim()}").document

        return document.getAnimeCards()
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val rawTitle = document.selectFirst("h2, div.col-lg-9 h2")?.text()?.trim() ?: "Sin título"
        val baseTitle = cleanBaseTitle(rawTitle)

        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("div.serieimgficha img, img")?.getImageAttr()
        )

        val plot = document.selectFirst("h2 ~ p.my-2, div.col-lg-9 p.opacity-75")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val tags = document.select("a div.btn, div.col-lg-9 a div.btn")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val year = Regex("""(19|20)\d{2}""").find(document.text())?.value?.toIntOrNull()
        val background = poster

        val recommendations = document.getAnimeCards()
            .mapNotNull { it.toSearchResult() }
            .filter { it.url != url }
            .distinctBy { it.url }
            .take(12)

        val isCartoon = tags.any { it.contains("cartoon", true) }
        val currentSlug = url.substringAfter("/anime/").trimEnd('/')
        val currentLanguage = detectLanguage(currentSlug)
        val normalizedSlug = normalizeSlug(currentSlug)
        val searchQuery = normalizedSlug.replace("-", "+")

        val searchDocument = app.get("$mainUrl/buscar?q=$searchQuery").document

        val relatedUrlsFromSearch = searchDocument.getAnimeCards()
            .mapNotNull { it.getNormalizedAnimeUrl() }
            .filter { it.contains("/anime/") }
            .distinct()
            .filter { animeUrl ->
                val otherSlug = animeUrl.substringAfter("/anime/").trimEnd('/')
                val otherNormalized = normalizeSlug(otherSlug)
                val otherLanguage = detectLanguage(otherSlug)

                (otherNormalized == normalizedSlug ||
                        otherNormalized.contains(normalizedSlug) ||
                        normalizedSlug.contains(otherNormalized)) &&
                        (isCartoon || otherLanguage == currentLanguage || otherLanguage == "latino")
            }

        val relatedUrls = (listOf(url) + relatedUrlsFromSearch)
            .distinct()
            .sortedBy { extractSeasonNumber(it.substringAfter("/anime/").trimEnd('/')) }

        val allEpisodes = mutableListOf<Episode>()

        relatedUrls.forEach { animeUrl ->
            try {
                val animeDocument = app.get(animeUrl).document
                val seasonTitle = animeDocument.selectFirst("h2, div.col-lg-9 h2")?.text()?.trim() ?: rawTitle
                val slug = animeUrl.substringAfter("/anime/").trimEnd('/')
                val seasonNumber = extractSeasonNumber(slug)

                val episodesRaw = animeDocument.getEpisodeAnchors()

                val episodes = episodesRaw.mapIndexed { index, element ->
                    val epUrl = fixUrl(element.attr("href"))
                    val epName = element.text().trim().ifBlank { "Episodio ${index + 1}" }
                    val episodeNumber = extractEpisodeNumber(epName) ?: index + 1

                    newEpisode(epUrl) {
                        this.name = epName
                        this.episode = episodeNumber
                        this.season = seasonNumber
                        this.posterUrl = fixUrlNull(element.selectFirst("img")?.getImageAttr())
                        this.description = seasonTitle
                    }
                }

                allEpisodes.addAll(episodes)
            } catch (_: Exception) {
            }
        }

        val finalEpisodes = allEpisodes.distinctBy { it.data }
        val declaredEpisodeCount = extractDeclaredEpisodeCount(document)
        val hasEpisodeSection = document.hasEpisodeSection()
        val isExplicitMovie = rawTitle.contains("pelicula", true) ||
                rawTitle.contains("película", true) ||
                rawTitle.contains("movie", true) ||
                tags.any {
                    it.contains("pelicula", true) ||
                            it.contains("película", true) ||
                            it.contains("movie", true)
                }

        // No lo mandes a película solo porque falló la búsqueda de relacionados.
        // Si la ficha declara varios episodios o trae sección de capítulos, siempre debe cargar como anime.
        val isMovie = isExplicitMovie ||
                (finalEpisodes.size <= 1 && declaredEpisodeCount <= 1 && !hasEpisodeSection)

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

        val dataKey = document
            .selectFirst(".player")
            ?.attr("data-key")
            ?.trim()
            ?: ""

        var found = false

        val servers = document.select("a[data-player], ul.cap_repro li a")

        servers.forEach { server ->
            try {
                val encoded = server
                    .attr("data-player")
                    .trim()

                if (encoded.isBlank()) {
                    return@forEach
                }

                val serverName = server.text()
                    .trim()
                    .lowercase()

                // ===================================================
                // NUEVO MÉTODO LATANIME
                // /reproductor?url=
                // ===================================================
                var resolvedUrl: String? = null

                runCatching {
                    val repUrl = "$mainUrl/reproductor?url=$encoded"

                    resolvedUrl = app.get(
                        repUrl,
                        headers = mapOf(
                            "Referer" to data,
                            "User-Agent" to USER_AGENT
                        )
                    ).document
                        .selectFirst("iframe, embed")
                        ?.attr("src")
                }

                // ===================================================
                // MÉTODO VIEJO DATA-KEY + BASE64
                // ===================================================
                if (resolvedUrl.isNullOrBlank()) {
                    resolvedUrl = runCatching {
                        String(Base64.decode(encoded, Base64.DEFAULT)).trim()
                    }.getOrNull()

                    if (resolvedUrl.isNullOrBlank() && dataKey.isNotBlank()) {
                        resolvedUrl = runCatching {
                            String(Base64.decode(dataKey + encoded, Base64.DEFAULT)).trim()
                        }.getOrNull()
                    }
                }

                if (resolvedUrl.isNullOrBlank()) {
                    return@forEach
                }

                resolvedUrl = fixUrl(resolvedUrl!!)

                // ===================================================
                // MP4UPLOAD
                // ===================================================
                if (resolvedUrl!!.contains("mp4upload", true)) {
                    val id = Regex("""(?:embed-|/)([A-Za-z0-9]+)""")
                        .find(resolvedUrl!!)
                        ?.groupValues
                        ?.getOrNull(1)

                    if (id != null) {
                        resolvedUrl = "https://www.mp4upload.com/embed-$id.html"
                    }
                }

                // ===================================================
                // PIXELDRAIN
                // ===================================================
                if (resolvedUrl!!.contains("pixeldrain.com", true)) {
                    val id = resolvedUrl!!
                        .substringAfterLast("/")
                        .trim()

                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "Pixeldrain",
                            url = "https://pixeldrain.com/api/file/$id?download",
                            type = ExtractorLinkType.VIDEO
                        ) {
                            referer = data
                            quality = Qualities.Unknown.value
                        }
                    )

                    found = true
                    return@forEach
                }

                val externalFound = LatanimeExternalExtractor.resolve(
                    resolvedUrl!!,
                    data,
                    subtitleCallback,
                    callback
                )

                if (externalFound) {
                    found = true
                } else {
                    loadExtractor(
                        resolvedUrl!!,
                        data,
                        subtitleCallback
                    ) { link ->
                        found = true
                        callback.invoke(link)
                    }
                }
            } catch (e: Exception) {
                println("LATANIME SERVER ERROR -> ${e.message}")
            }
        }

        // ===================================================
        // LINKS DE DESCARGA
        // ===================================================
        document.select("div.descarga2 div a, a[href*='pixeldrain.com'], a[href*='mp4upload.com']")
            .forEach { dl ->
                try {
                    val url = dl
                        .attr("href")
                        .trim()

                    if (url.isBlank()) {
                        return@forEach
                    }

                    val fixedUrl = fixUrl(url)

                    if (fixedUrl.contains("pixeldrain.com", true)) {
                        val id = fixedUrl
                            .substringAfterLast("/")
                            .trim()

                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "Pixeldrain",
                                url = "https://pixeldrain.com/api/file/$id?download",
                                type = ExtractorLinkType.VIDEO
                            ) {
                                referer = data
                                quality = Qualities.Unknown.value
                            }
                        )

                        found = true
                    } else {
                        loadExtractor(
                            fixedUrl,
                            data,
                            subtitleCallback
                        ) { link ->
                            found = true
                            callback.invoke(link)
                        }
                    }
                } catch (_: Exception) {
                }
            }

        return found
    }

    // ==================== HELPERS ====================

    private fun buildPageUrl(path: String, page: Int): String {
        val cleanPath = path.trimStart('/')
        val separator = if (cleanPath.contains("?")) "&" else "?"
        return "$mainUrl/$cleanPath${separator}p=$page"
    }

    private fun cleanBaseTitle(title: String): String {
        return title
            .replace(
                Regex(
                    """\s*S\d+\s*Latino|\s*Temporada\s*\d+|\s*Season\s*\d+|\s*Latino$""",
                    RegexOption.IGNORE_CASE
                ),
                ""
            )
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun Document.getAnimeCards() = select(
        """
        div.row a[href],
        div.col-6 a[href],
        div.col-md-6 a[href],
        div.col-md-4 a[href],
        div.item a[href],
        article a[href]
        """.trimIndent()
    )

    private fun Document.getEpisodeAnchors() = select(
        """
        a[href*='/ver/'],
        div[style*='overflow-y: auto'] > a[href*='/ver/'],
        ul.capitulos a[href*='/ver/'],
        div.episodios a[href*='/ver/']
        """.trimIndent()
    ).distinctBy { it.attr("href") }

    private fun Element.toSearchResult(): SearchResponse? {
        val fixedHref = getNormalizedAnimeUrl() ?: return null

        val title = selectFirst("h2, h3, span.title, div.text-2xs")
            ?.text()
            ?.trim()
            ?: text().trim()

        if (title.isBlank()) return null

        val poster = fixUrlNull(selectFirst("img")?.getImageAttr())

        val infoText = select("div.info_cap span, span.opacity-75, div.bg-line, div.btn")
            .text()
            .trim()

        val fullText = "$title $infoText"

        val isMovie = fullText.contains("pelicula", true) ||
                fullText.contains("película", true) ||
                fullText.contains("movie", true)

        val type = if (isMovie) TvType.AnimeMovie else TvType.Anime

        val isDub = fullText.contains("latino", true) ||
                fullText.contains("castellano", true)

        return newAnimeSearchResponse(title, fixedHref, type) {
            this.posterUrl = poster

            if (isDub) {
                addDubStatus(DubStatus.Dubbed)
            } else {
                addDubStatus(DubStatus.Subbed)
            }
        }
    }

    private fun Element.getNormalizedAnimeUrl(): String? {
        val anchor = if (tagName() == "a") this else selectFirst("a") ?: return null
        var href = anchor.attr("href").trim()

        if (href.isBlank()) return null

        if (href.contains("/ver/")) {
            href = href
                .replace("/ver/", "/anime/")
                .substringBefore("-episodio")
                .substringBefore("-capitulo")
        } else if (href.contains("/media/")) {
            val slug = href.substringAfter("/media/").substringBefore("/").trim()
            if (slug.isBlank()) return null
            href = "/anime/$slug"
        }

        val fixedHref = fixUrl(href)

        if (!fixedHref.contains("/anime/")) return null

        return fixedHref
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

    private fun extractEpisodeNumber(text: String): Int? {
        Regex("""(?:episodio|capitulo|capítulo)\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.let { return it }

        Regex("""\b(\d+)\b""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.let { return it }

        return null
    }

    private fun extractDeclaredEpisodeCount(document: Document): Int {
        Regex("""Episodios:\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(document.text())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.let { return it }

        return 0
    }

    private fun Document.hasEpisodeSection(): Boolean {
        if (selectFirst("h3.caph3, div[style*='overflow-y: auto'], ul.capitulos, div.episodios") != null) {
            return true
        }

        return getEpisodeAnchors().isNotEmpty()
    }

    private fun Element.getImageAttr(): String? {
        listOf(
            "data-src",
            "data-lazy-src",
            "data-original",
            "data-image",
            "src"
        ).forEach { key ->
            val value = attr(key).trim()
            if (value.startsWith("http")) return value
        }

        val srcset = attr("srcset").trim()
        if (srcset.isNotBlank()) return srcset.substringBefore(" ").trim()

        return null
    }
}
