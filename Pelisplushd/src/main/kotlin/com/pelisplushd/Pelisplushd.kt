package com.pelisplushd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URLEncoder

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
class Pelisplushd : MainAPI() {
    override var mainUrl = "https://www.pelisplushd.la"
    override var name = "Pelisplus HD"
    override var lang = "es"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie, 
        TvType.TvSeries, 
        TvType.Anime, 
        TvType.AsianDrama
    )

    private val requestHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to mainUrl,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
    )

    override val mainPage = mainPageOf(
        "$mainUrl" to "Inicio",
        "$mainUrl/peliculas" to "Películas",
        "$mainUrl/series" to "Series",
        "$mainUrl/animes" to "Animes",
        "$mainUrl/generos/dorama" to "Doramas"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = buildString {
            append(request.data.trimEnd('/'))
            if (request.data != mainUrl) {
                append("?page=$page")
            }
        }
        
        val doc = try {
            app.get(url, headers = requestHeaders).document
        } catch (e: Exception) {
            app.get(request.data, headers = requestHeaders).document
        }

        val items = mutableListOf<SearchResponse>()
        
        if (request.data == mainUrl) {
            items.addAll(
                doc.select("div#default-tab-1 div.Posters a.Posters-link, " +
                           "div#default-tab-2 div.Posters a.Posters-link, " +
                           "div#default-tab-3 div.Posters a.Posters-link, " +
                           "div#default-tab-4 div.Posters a.Posters-link")
                    .mapNotNull { it.toSearchResult() }
            )
        } else {
            items.addAll(
                doc.select("div.Posters a.Posters-link")
                    .mapNotNull { it.toSearchResult() }
            )
        }

        val hasNext = doc.select("ul.pagination a.page-link[rel='next']").isNotEmpty()
        return newHomePageResponse(request.name, items.distinctBy { it.url.trimEnd('/').substringAfterLast("/") }, hasNext)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = when {
            hasAttr("data-title") -> {
                attr("data-title")
                    ?.substringBefore(" Online Gratis HD")
                    ?.trim()
                    ?.removePrefix("VER ")
                    ?.trim()
            }
            else -> selectFirst(".listing-content p")?.text() 
                ?: selectFirst("p")?.text()
        } ?: return null
        
        val href = attr("href") ?: return null
        
        val type = when {
            selectFirst(".movies.centrado") != null -> TvType.Movie
            selectFirst(".series.centrado") != null -> TvType.TvSeries
            selectFirst(".animes.centrado") != null -> TvType.Anime
            href.contains("/serie/") -> TvType.TvSeries
            href.contains("/anime/") || href.contains("/animes/") -> TvType.Anime
            href.contains("/generos/dorama") || href.contains("/dorama") -> TvType.AsianDrama
            else -> TvType.Movie
        }
        
        val poster = selectFirst("img")?.let { extractPoster(it) }

        return when (type) {
            TvType.Movie -> newMovieSearchResponse(title, fixUrl(href), TvType.Movie) { 
                this.posterUrl = poster 
            }
            TvType.TvSeries -> newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) { 
                this.posterUrl = poster 
            }
            TvType.Anime -> newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) { 
                this.posterUrl = poster 
            }
            TvType.AsianDrama -> newTvSeriesSearchResponse(title, fixUrl(href), TvType.AsianDrama) { 
                this.posterUrl = poster 
            }
            else -> null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.trim().isEmpty()) return emptyList()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/search?s=$encoded"
        
        val doc = app.get(searchUrl, headers = requestHeaders).document

        return doc.select("div.Posters a.Posters-link")
            .mapNotNull { element ->
                element.toSearchResult()
            }
            .distinctBy { it.url.trimEnd('/').substringAfterLast("/") }
    }

    override suspend fun load(url: String): LoadResponse {
        val currentUrl = url
        val doc = app.get(currentUrl, headers = requestHeaders).document

        val title = doc.selectFirst("h1")?.text()
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")?.substringBefore(" - ")
            ?: "Sin título"

        val poster = doc.selectFirst("img[src*='/poster/'], .card-body img.img-fluid")?.let { extractPoster(it) }
            ?: doc.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) }

        val description: String = doc.selectFirst("meta[property='og:description']")?.attr("content")
            ?: doc.selectFirst(".text-large")?.text()
            ?: ""

        val year = doc.selectFirst("a[href*='/year/']")?.text()?.toIntOrNull()

        val ratingText = doc.selectFirst(".rating .ion-md-star span, .font-size-18.ion-md-star")?.text()
        val rating = ratingText?.replace("/10", "")?.toFloatOrNull()?.let { 
            (it * 5 / 10).toInt() 
        }

        val isSeries = currentUrl.contains("/serie/") || 
                       currentUrl.contains("/anime/") ||
                       currentUrl.contains("/dorama") ||
                       doc.select(".VideoPlayer ul.TbVideoNv li a[href*='pills-vertical']").isNotEmpty()

        val episodesList = mutableListOf<Episode>()
        val recommendations = mutableListOf<SearchResponse>()

        if (isSeries) {
            val seasonTabs = doc.select(".VideoPlayer ul.TbVideoNv li a[href*='pills-vertical']")
            
            if (seasonTabs.isEmpty()) {
                doc.select("a.btn-block[href*='/capitulo/']").forEach { button ->
                    val episodeUrl = button.attr("href")
                    val episodeText = button.text()
                    
                    val seasonMatch = Regex("""temporada/(\d+)/""").find(episodeUrl)
                    val episodeMatch = Regex("""capitulo/(\d+)""").find(episodeUrl)
                    
                    val seasonNumber = seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val episodeNumber = episodeMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    
                    episodesList.add(newEpisode(fixUrl(episodeUrl)) {
                        this.name = episodeText
                        this.season = seasonNumber
                        this.episode = episodeNumber
                    })
                }
            } else {
                seasonTabs.forEachIndexed { index, tab ->
                    val seasonNumber = index + 1
                    val tabId = tab.attr("href").replace("#", "").replace(" ", "")
                    
                    val seasonPanel = doc.selectFirst("div.tab-content div#$tabId, div.tab-content div[id*='$tabId']")
                    
                    if (seasonPanel != null) {
                        val episodeButtons = seasonPanel.select("a.btn-block[href*='/capitulo/']")
                        
                        episodeButtons.forEach { button ->
                            val episodeUrl = button.attr("href")
                            val episodeText = button.text()
                            
                            val episodeMatch = Regex("""capitulo/(\d+)""").find(episodeUrl)
                            val episodeNumber = episodeMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                            
                            episodesList.add(newEpisode(fixUrl(episodeUrl)) {
                                this.name = episodeText
                                this.season = seasonNumber
                                this.episode = episodeNumber
                            })
                        }
                    }
                }
            }
        }

        doc.select("aside .Posters a.Posters-link, .related .Posters a, .MovieList a.Posters-link")
            .forEach { el ->
                val recTitle = el.attr("data-title")
                    ?.substringBefore(" Online Gratis HD")
                    ?.trim()
                    ?.removePrefix("VER ")
                    ?.trim()
                    ?: el.selectFirst(".listing-content p")?.text()
                    ?: return@forEach
                
                val recHref = el.attr("href") ?: return@forEach
                val recPoster = extractPoster(el.selectFirst("img"))
                
                val recType = when {
                    el.selectFirst(".movies.centrado") != null -> TvType.Movie
                    el.selectFirst(".series.centrado") != null -> TvType.TvSeries
                    el.selectFirst(".animes.centrado") != null -> TvType.Anime
                    recHref.contains("/serie/") -> TvType.TvSeries
                    recHref.contains("/anime/") -> TvType.Anime
                    recHref.contains("/dorama") -> TvType.AsianDrama
                    else -> TvType.Movie
                }

                val resp = when (recType) {
                    TvType.Movie -> newMovieSearchResponse(recTitle, fixUrl(recHref), TvType.Movie) { 
                        this.posterUrl = recPoster 
                    }
                    TvType.TvSeries -> newTvSeriesSearchResponse(recTitle, fixUrl(recHref), TvType.TvSeries) { 
                        this.posterUrl = recPoster 
                    }
                    TvType.Anime -> newAnimeSearchResponse(recTitle, fixUrl(recHref), TvType.Anime) { 
                        this.posterUrl = recPoster 
                    }
                    TvType.AsianDrama -> newTvSeriesSearchResponse(recTitle, fixUrl(recHref), TvType.AsianDrama) { 
                        this.posterUrl = recPoster 
                    }
                    else -> null
                }
                if (resp != null) recommendations.add(resp)
            }

        return if (isSeries) {
            newTvSeriesLoadResponse(title, currentUrl, TvType.TvSeries, episodesList) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.rating = rating
                this.recommendations = recommendations.distinctBy { it.url }.take(12)
            }
        } else {
            newMovieLoadResponse(title, currentUrl, TvType.Movie, currentUrl) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.rating = rating
                this.recommendations = recommendations.distinctBy { it.url }.take(12)
            }
        }
    }

    private fun extractPoster(imgElement: Element?): String? {
        if (imgElement == null) return null
        
        var src = imgElement.attr("src")
        if (src.isBlank()) src = imgElement.attr("data-src")
        if (src.isBlank()) src = imgElement.attr("data-srcset")?.substringBefore(" ")
        if (src.isBlank()) src = imgElement.attr("srcset")?.substringBefore(" ")
        
        return if (src.isNotBlank()) {
            if (src.startsWith("http")) src else fixUrl(src)
        } else null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val doc = app.get(data, headers = requestHeaders).document

        fun normalizeUrl(url: String): String {
            return url.replace("\\/", "/")
                .replace("mivalyo.com", "vidhidepro.com")
                .replace("dinisglows.com", "vidhidepro.com")
                .replace("dhtpre.com", "vidhidepro.com")
                .replace("swdyu.com", "streamwish.to")
                .replace("hglink.to", "streamwish.to")
                .replace("callistanise.com", "streamwish.to")
                .replace("filemoon.sx", "filemoon.to")
                .replace("embtaku.pro", "embtaku.com")
        }

        // Para series (enlaces en div#link_url)
        val linkSpans = doc.select("div#link_url span[url]")
        linkSpans.forEach { span ->
            var embedUrl = span.attr("url")
            if (embedUrl.isNotBlank()) {
                embedUrl = normalizeUrl(embedUrl)
                if (loadExtractor(embedUrl, data, subtitleCallback, callback)) {
                    found = true
                }
            }
        }

        // Para películas (enlaces en li.playurl)
        if (!found) {
            val videoItems = doc.select("li.playurl[data-url]")
            videoItems.forEach { item ->
                var embedUrl = item.attr("data-url")
                if (embedUrl.isNotBlank()) {
                    embedUrl = normalizeUrl(embedUrl)
                    if (loadExtractor(embedUrl, data, subtitleCallback, callback)) {
                        found = true
                    }
                }
            }
        }

        // Fallback: iframes directos
        if (!found) {
            doc.select("iframe[src], video source[src], div.video-html iframe[src]").forEach { element ->
                var src = element.attr("src") ?: element.attr("data-src") ?: return@forEach
                if (src.isNotBlank() && src.contains("http")) {
                    src = normalizeUrl(src)
                    if (loadExtractor(src, data, subtitleCallback, callback)) {
                        found = true
                    }
                }
            }
        }

        return found
    }
}