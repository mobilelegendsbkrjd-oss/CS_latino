package com.cuevana

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

// =============================
// JSON MODELS
// =============================

@Serializable
data class ApiResponse(
    @SerialName("props") val props: Props? = null
)

@Serializable
data class Props(
    @SerialName("pageProps") val pageProps: PageProps? = null
)

@Serializable
data class PageProps(
    @SerialName("thisMovie") val thisMovie: MediaItem? = null,
    @SerialName("thisSerie") val thisSerie: MediaItem? = null,
    @SerialName("episode") val episode: EpisodeInfo? = null,
)

@Serializable
data class MediaItem(
    @SerialName("videos") val videos: Videos? = null,
    @SerialName("seasons") val seasons: List<SeasonInfo>? = null
)

@Serializable
data class EpisodeInfo(
    @SerialName("videos") val videos: Videos? = null
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
)

@Serializable
data class JsonUrl(
    @SerialName("slug") val slug: String? = null
)

@Serializable
data class Videos(
    @SerialName("latino") val latino: List<VideoInfo>? = null,
    @SerialName("spanish") val spanish: List<VideoInfo>? = null,
    @SerialName("english") val english: List<VideoInfo>? = null
)

@Serializable
data class VideoInfo(
    @SerialName("result") val result: String? = null
)

// =============================
// MAIN API
// =============================

class Cuevana : MainAPI() {

    override var mainUrl = "https://cuevana3.re"
    override var name = "Cuevana"
    override var lang = "es"

    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to mainUrl
    )

    // =============================
    // MAIN PAGE
    // =============================

    override val mainPage = mainPageOf(
        "$mainUrl/peliculas/page/" to "Películas",
        "$mainUrl/series/page/" to "Series",
        "$mainUrl/peliculas/estrenos/page/" to "Estrenos Películas",
        "$mainUrl/series/estrenos/page/" to "Estrenos Series"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url = "${request.data}$page"
        val doc = app.get(url, headers = headers).document

        val items = doc.select(".TPostMv")
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            request.name,
            items,
            items.isNotEmpty()
        )
    }

    // =============================
    // SEARCH
    // =============================

    override suspend fun search(query: String): List<SearchResponse> {

        val q = URLEncoder.encode(query, "UTF-8")

        val doc = app.get(
            "$mainUrl/search?q=$q",
            headers = headers
        ).document

        return doc.select(".TPostMv")
            .mapNotNull { it.toSearchResult() }
    }

    // =============================
    // SEARCH RESULT PARSER
    // =============================

    private fun Element.toSearchResult(): SearchResponse? {

        val title = selectFirst(".Title")?.text() ?: return null
        val href = selectFirst("a")?.attr("href") ?: return null

        val poster = selectFirst("img")?.attr("src")

        return if (href.contains("/serie")) {
            newTvSeriesSearchResponse(
                title,
                fixUrl(href),
                TvType.TvSeries
            ) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(
                title,
                fixUrl(href),
                TvType.Movie
            ) {
                this.posterUrl = poster
            }
        }
    }

    // =============================
    // LOAD
    // =============================

    override suspend fun load(url: String): LoadResponse {

        val doc = app.get(url, headers = headers).document

        val title = doc.selectFirst("meta[property=og:title]")
            ?.attr("content")
            ?: "Sin título"

        val poster = doc.selectFirst("meta[property=og:image]")
            ?.attr("content")

        val description = doc.selectFirst("meta[property=og:description]")
            ?.attr("content")

        val json = doc.selectFirst("script#__NEXT_DATA__")
            ?.data()
            ?: throw ErrorLoadingException("No JSON")

        val parsed = parseJson<ApiResponse>(json)

        val videos = parsed.props
            ?.pageProps
            ?.thisMovie
            ?.videos

        return newMovieLoadResponse(
            title,
            url,
            TvType.Movie,
            url
        ) {
            this.posterUrl = poster
            this.plot = description
            this.dataUrl = url
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

        val json = doc.selectFirst("script#__NEXT_DATA__")
            ?.data() ?: return false

        val parsed = parseJson<ApiResponse>(json)

        val videos = parsed.props?.pageProps?.let {
            it.thisMovie?.videos ?: it.episode?.videos
        } ?: return false

        suspend fun extract(video: VideoInfo) {

            val embed = video.result ?: return

            loadExtractor(
                embed,
                data,
                subtitleCallback,
                callback
            )
        }

        videos.latino?.forEach { extract(it) }
        videos.spanish?.forEach { extract(it) }
        videos.english?.forEach { extract(it) }

        return true
    }
}