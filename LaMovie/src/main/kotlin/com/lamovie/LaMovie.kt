package com.lamovie

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class LaMovie : MainAPI() {
    override var mainUrl = "https://la.movie"
    override var name = "La.Movie"
    override val hasMainPage = true
    override var lang = "es"
    override val hasDownloadSupport = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val api = "$mainUrl/wp-json/wpf/v1"
    private val fastApi = "$mainUrl/wp-api/v1"

    override val mainPage = mainPageOf(
        "peliculas" to "🎬 Películas",
        "series" to "📺 Series",
        "animes" to "🍥 Animes"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val json = app.get("$api/home?type=${request.data}&page=$page")
            .parsedSafe<ApiListResponse>() ?: return HomePageResponse(emptyList(), false)

        val list = json.items.map { item ->
            if (item.contentType == "movie") {
                newMovieSearchResponse(item.title, item.url, TvType.Movie) {
                    this.posterUrl = item.poster
                }
            } else {
                newTvSeriesSearchResponse(item.title, item.url, TvType.TvSeries) {
                    this.posterUrl = item.poster
                }
            }
        }

        return HomePageResponse(listOf(HomePageList(request.name, list)), json.hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.replace(" ", "%20")
        val json = app.get("$api/search?query=$q")
            .parsedSafe<ApiListResponse>() ?: return emptyList()

        return json.items.map { item ->
            if (item.contentType == "movie") {
                newMovieSearchResponse(item.title, item.url, TvType.Movie) {
                    this.posterUrl = item.poster
                }
            } else {
                newTvSeriesSearchResponse(item.title, item.url, TvType.TvSeries) {
                    this.posterUrl = item.poster
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val id = doc.select("link[rel=shortlink]")
            .attr("href")
            .substringAfter("?p=")

        val data = app.get("$api/post?id=$id").parsedSafe<ApiPost>()
            ?: throw ErrorLoadingException("No API data")

        return if (data.type == "movie") {
            newMovieLoadResponse(data.title, url, TvType.Movie, id) {
                posterUrl = data.poster
                plot = data.description
                year = data.year
                tags = data.genres
            }
        } else {
            val episodes = data.episodes.mapIndexed { index, ep ->
                newEpisode(ep.id.toString()) {
                    name = ep.title
                    episode = index + 1
                    posterUrl = ep.poster ?: data.poster
                }
            }

            newTvSeriesLoadResponse(data.title, url, TvType.TvSeries, episodes) {
                posterUrl = data.poster
                plot = data.description
                year = data.year
                tags = data.genres
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val json = app.get("$fastApi/player?id=$data")
            .parsedSafe<ApiPlayer>() ?: return false

        json.servers.forEach {
            loadExtractor(it.url, "", subtitleCallback, callback)
        }
        return true
    }

    // -------- MODELOS --------

    data class ApiListResponse(
        val items: List<ApiItem>,
        val hasNext: Boolean
    )

    data class ApiItem(
        val title: String,
        val url: String,
        val poster: String?,
        val contentType: String
    )

    data class ApiPost(
        val title: String,
        val poster: String?,
        val description: String?,
        val year: Int?,
        val genres: List<String>,
        val type: String,
        val episodes: List<ApiEpisode>
    )

    data class ApiEpisode(
        val id: Int,
        val title: String,
        val poster: String?
    )

    data class ApiPlayer(
        val servers: List<ApiServer>
    )

    data class ApiServer(
        val name: String,
        val url: String
    )
}
