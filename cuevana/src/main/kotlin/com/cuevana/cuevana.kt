package com.cuevana

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URLEncoder
import java.net.URLDecoder

// =============================
// MODELOS JSON
// =============================
@Serializable
data class ApiResponse(
    @SerialName("props") var props: Props? = null
)

@Serializable
data class Props(
    @SerialName("pageProps") var pageProps: PageProps? = null
)

@Serializable
data class PageProps(
    @SerialName("thisMovie") var thisMovie: MediaItem? = null,
    @SerialName("thisSerie") var thisSerie: MediaItem? = null,
    @SerialName("episode") var episode: EpisodeInfo? = null,
    @SerialName("relatedMovies") var relatedMovies: List<RelatedMovie>? = null
)

@Serializable
data class RelatedMovie(
    val titles: Titles? = null,
    val slug: Slug? = null,
    val images: Images? = null
)

@Serializable
data class Titles(val name: String? = null)

@Serializable
data class Slug(val name: String? = null)

@Serializable
data class Images(val poster: String? = null)

@Serializable
data class MediaItem(
    @SerialName("videos") var videos: Videos? = null,
    @SerialName("seasons") var seasons: List<SeasonInfo>? = null
)

@Serializable
data class SeasonInfo(
    @SerialName("number") val number: Int? = null,
    @SerialName("episodes") val episodes: List<JsonEpisode>? = null
)

@Serializable
data class JsonEpisode(
    @SerialName("title") val title: String? = null,
    @SerialName("number") val number: Int? = null,
    @SerialName("url") val url: JsonUrl? = null,
    @SerialName("image") val image: String? = null
)

@Serializable
data class JsonUrl(val slug: String? = null)

@Serializable
data class EpisodeInfo(
    @SerialName("videos") var videos: Videos? = null
)

@Serializable
data class Videos(
    @SerialName("latino") var latino: ArrayList<VideoInfo>? = null,
    @SerialName("spanish") var spanish: ArrayList<VideoInfo>? = null,
    @SerialName("english") var english: ArrayList<VideoInfo>? = null
)

@Serializable
data class VideoInfo(
    @SerialName("result") var result: String? = null
)

// =============================
// MAIN API
// =============================
class Cuevana : MainAPI() {
    override var mainUrl = "https://cuevana3.re/"
    override var name = "Cuevana"
    override var lang = "es"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val requestHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to mainUrl,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/peliculas/tendencias/dia/page/" to "Películas en Tendencia",
        "$mainUrl/series/tendencias/dia/page/" to "Series en Tendencia",
        "$mainUrl/peliculas/estrenos/page/" to "Estrenos Películas",
        "$mainUrl/series/estrenos/page/" to "Estrenos Series",
        "$mainUrl/peliculas/page/" to "Todas las Películas",
        "$mainUrl/series/page/" to "Todas las Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.endsWith("/")) "${request.data}$page" else "${request.data}/$page"
        val doc = app.get(url, headers = requestHeaders).document

        val items = doc.select("main .MovieList.Rows .TPostMv, main .MovieList li.TPostMv")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url.trimEnd('/').substringAfterLast("/") }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst(".Title")?.text() ?: return null
        var href = selectFirst("a")?.attr("href") ?: return null

        var slug = href.trimEnd('/').substringAfterLast("/")
        slug = slug.replace(Regex("-\\d{4}$"), "")

        val isSeries = href.contains("/serie") || href.contains("/series/") || href.contains("/ver-serie/")
        val fixedHref = if (isSeries) "/ver-serie/$slug" else "/ver-pelicula/$slug"
        val poster = extractPoster(selectFirst("img"))

        return if (isSeries) {
            newTvSeriesSearchResponse(title, fixUrl(fixedHref), TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, fixUrl(fixedHref), TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.trim().isEmpty()) return emptyList()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/search?q=$encoded"
        val doc = app.get(searchUrl, headers = requestHeaders).document

        return doc.select("main .MovieList .TPost, main .MovieList li.TPostMv, .search-results .TPost")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url.trimEnd('/').substringAfterLast("/") }
    }

    override suspend fun load(url: String): LoadResponse {
        val currentUrl = url
        val doc = app.get(currentUrl, headers = requestHeaders).document

        val title = doc.selectFirst("meta[property='og:title']")
            ?.attr("content")?.substringBefore(" - ")
            ?: doc.selectFirst("h1.Title, h1")?.text()
            ?: "Sin título"

        val poster = extractPoster(
            doc.selectFirst("meta[property='og:image']")?.let { Element("img").attr("src", it.attr("content")) }
        ) ?: extractPoster(doc.selectFirst("img[src*='tmdb.org'], img.poster, img.main-poster, img"))

        val rawDescription = doc.selectFirst("meta[property='og:description']")?.attr("content")
            ?: doc.selectFirst(".description, .sinopsis")?.text() ?: ""

        val isSeries = currentUrl.contains("/ver-serie/") ||
                doc.select(".select-season, ul.all-episodes").isNotEmpty()

        val currentSlug = currentUrl.trimEnd('/').substringAfterLast("/").lowercase()

        val episodesList = mutableListOf<Episode>()
        val recommendations = mutableListOf<SearchResponse>()
        val seenSlugs = mutableSetOf<String>()

        fun normalizeSlug(href: String): String {
            return href.trimEnd('/')
                .substringAfterLast("/")
                .replace(Regex("-\\d{4}$"), "")
                .lowercase()
        }

        try {
            val jsonData = doc.selectFirst("script#__NEXT_DATA__")?.data()
            if (jsonData != null) {
                val res = parseJson<ApiResponse>(jsonData)
                val pageProps = res.props?.pageProps

                if (isSeries) {
    val seasonsArr = pageProps?.thisSerie?.seasons
    if (!seasonsArr.isNullOrEmpty()) {
        seasonsArr.forEach { s ->
            val sNum = s.number ?: return@forEach
            s.episodes?.forEach { e ->
                val eNum = e.number ?: 1
                // ────────────────────────────────────────────────
                // NUEVA CONSTRUCCIÓN DE URL (la que sí funciona ahora)
                val episodeSlug = "$currentSlug-temporada-$sNum-episodio-$eNum"
                val episodeUrl = fixUrl("/episodio/$episodeSlug")
                // ────────────────────────────────────────────────

                episodesList.add(newEpisode(episodeUrl) {
                    this.name = e.title ?: "T$sNum Ep$eNum"
                    this.season = sNum
                    this.episode = eNum
                    this.posterUrl = e.image?.let { fixUrl(it) }  // o déjalo como estaba
                })
            }
        }
    }
}

                pageProps?.relatedMovies?.forEach { movie ->
                    val slugName = movie.slug?.name?.trim() ?: return@forEach
                    if (slugName.isEmpty()) return@forEach

                    val recType = if (slugName.contains("serie", ignoreCase = true)) TvType.TvSeries else TvType.Movie
                    val cleanSlug = slugName.replace(Regex("-\\d{4}$"), "")
                    val recHref = fixUrl(if (recType == TvType.TvSeries) "/ver-serie/$cleanSlug" else "/ver-pelicula/$cleanSlug")

                    val slugKey = normalizeSlug(recHref)
                    if (slugKey == currentSlug || seenSlugs.contains(slugKey)) return@forEach
                    seenSlugs.add(slugKey)

                    val recPoster = movie.images?.poster?.let { "https://image.tmdb.org/t/p/w500$it" } ?: ""

                    val resp = if (recType == TvType.TvSeries) {
                        newTvSeriesSearchResponse(movie.titles?.name ?: "Recomendado", recHref, TvType.TvSeries) {
                            this.posterUrl = recPoster.ifBlank { null }
                        }
                    } else {
                        newMovieSearchResponse(movie.titles?.name ?: "Recomendado", recHref, TvType.Movie) {
                            this.posterUrl = recPoster.ifBlank { null }
                        }
                    }
                    recommendations.add(resp)
                }
            }
        } catch (_: Throwable) {}

        val genreNames = mutableListOf<String>()
        doc.select(".InfoList li:has(a[href*='/genero/']) a").forEach { a ->
            val genre = a.text().trim()
            if (genre.isNotEmpty() && !genreNames.contains(genre)) {
                genreNames.add(genre)
            }
        }

        val actorNames = mutableListOf<String>()
        doc.select(".InfoList li.loadactor").forEach { li ->
            val text = li.text().replace(Regex("^Actores:\\s*"), "").trim()
            if (text.isNotEmpty()) {
                text.split(",").forEach { actor ->
                    val trimmed = actor.trim()
                    if (trimmed.isNotEmpty() && !actorNames.contains(trimmed)) {
                        actorNames.add(trimmed)
                    }
                }
            }
        }

        var finalPlot = rawDescription.trim()

        if (genreNames.isNotEmpty()) {
            finalPlot += "\n\n* Género: ${genreNames.joinToString(", ")}"
        }

        if (actorNames.isNotEmpty()) {
            finalPlot += "\n* Actores: ${actorNames.joinToString(", ")}"
        }

        if (isSeries && episodesList.isEmpty()) {
            doc.select("ul.all-episodes li.TPostMv").forEach { li ->
                val a = li.selectFirst("a[href]") ?: return@forEach
                val year = li.selectFirst("span.Year")?.text()?.trim() ?: ""
                if (year.contains("x")) {
                    val parts = year.split("x")
                    val s = parts[0].toIntOrNull() ?: 1
                    val e = parts[1].toIntOrNull() ?: 1
                    if (s <= 0) return@forEach
                    
                    val href = a.attr("href")
                    val episodeUrl = if (href.contains("/episodio/")) href else "/episodio/${href.trim('/')}"
                    episodesList.add(newEpisode(fixUrl(episodeUrl)) {
                        this.name = li.selectFirst("h2.Title")?.text()?.trim() ?: "Episodio $s x $e"
                        this.season = s
                        this.episode = e
                    })
                }
            }
        }

        if (recommendations.isEmpty()) {
            doc.select(
                "section.Others ul.MovieList li a, " +
                        "section.peli_top_estrenos ul.MovieList li a, " +
                        ".widget_languages .MovieList li a, " +
                        "aside .MovieList li a, " +
                        ".wdgt .MovieList li a"
            ).forEach { el ->
                if (el.parents().any { it.tagName() == "ul" && it.hasClass("all-episodes") }) return@forEach

                val titleElem = el.selectFirst(".Title, div.Title, span.Title.block")
                val recTitle = titleElem?.text()?.trim() ?: return@forEach
                val hrefRaw = el.attr("href").trim()
                if (hrefRaw.isBlank()) return@forEach
                val recHref = fixUrl(hrefRaw)

                val slugKey = normalizeSlug(recHref)
                if (slugKey == currentSlug || seenSlugs.contains(slugKey)) return@forEach
                seenSlugs.add(slugKey)

                val recPoster = extractPoster(el.selectFirst("img"))

                val recIsSeries = recHref.contains("/ver-serie/")
                val resp = if (recIsSeries) {
                    newTvSeriesSearchResponse(recTitle, recHref, TvType.TvSeries) {
                        this.posterUrl = recPoster
                    }
                } else {
                    newMovieSearchResponse(recTitle, recHref, TvType.Movie) {
                        this.posterUrl = recPoster
                    }
                }
                recommendations.add(resp)
            }
        }

        return if (isSeries) {
            newTvSeriesLoadResponse(title, currentUrl, TvType.TvSeries, episodesList.sortedBy { (it.season ?: 0) * 1000 + (it.episode ?: 0) }) {
                this.posterUrl = poster
                this.plot = finalPlot
                this.recommendations = recommendations.distinctBy { normalizeSlug(it.url) }.take(12)
            }
        } else {
            newMovieLoadResponse(title, currentUrl, TvType.Movie, currentUrl) {
                this.posterUrl = poster
                this.plot = finalPlot
                this.recommendations = recommendations.distinctBy { normalizeSlug(it.url) }.take(12)
            }
        }
    }

    private fun normalizeSlug(href: String): String {
        return href.trimEnd('/')
            .substringAfterLast("/")
            .replace(Regex("-\\d{4}$"), "")
            .lowercase()
    }

    private fun extractPoster(imgElement: Element?): String? {
        if (imgElement == null) return null
        var src = imgElement.attr("data-src")
        if (src.isBlank()) src = imgElement.attr("src")
        if (src.contains("_next/image?url=")) {
            val encodedUrl = src.substringAfter("url=").substringBefore("&")
            return try { URLDecoder.decode(encodedUrl, "UTF-8") } catch (_: Throwable) { fixUrl(src) }
        }
        return if (src.isNotBlank()) fixUrl(src) else null
    }

    // =============================
// LOAD LINKS (MEJORADO)
// =============================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        var found = false
        val doc = app.get(data, headers = requestHeaders).document

        fun fixESP(url: String): String = url.replace("\\/", "/")
            .replace("mivalyo.com", "vidhidepro.com")
            .replace("dinisglows.com", "vidhidepro.com")
            .replace("dhtpre.com", "vidhidepro.com")
            .replace("swdyu.com", "streamwish.to")
            .replace("hglink.to", "streamwish.to")
            .replace("callistanise.com", "streamwish.to")
            .replace("filemoon.sx", "filemoon.to")
            .replace("embtaku.pro", "embtaku.com")

        try {

            val jsonData = doc.selectFirst("script#__NEXT_DATA__")?.data() ?: return false
            val res = parseJson<ApiResponse>(jsonData)

            val videos = res.props?.pageProps?.let {
                it.thisMovie?.videos ?: it.thisSerie?.videos ?: it.episode?.videos
            } ?: return false

            suspend fun process(v: VideoInfo) {

                val embedUrl = v.result ?: return

                val finalUrl = StreamflixResolver.resolve(embedUrl, data)

                if (!finalUrl.isNullOrBlank()) {

                    val clean = fixESP(finalUrl)

                    if (loadExtractor(clean, data, subtitleCallback, callback)) {
                        found = true
                    }
                }
            }

            videos.latino?.forEach { process(it) }
            videos.spanish?.forEach { process(it) }
            videos.english?.forEach { process(it) }

        } catch (_: Throwable) {}

        return found
    }
}


