package com.lamovie

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class LaMovie : MainAPI() {

    override var mainUrl = "https://la.movie"
    override var name = "LaMovie"
    override var lang = "es"

    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    private val api = "$mainUrl/wp-api/v1"

    // ================= MAIN PAGE =================

    override val mainPage = mainPageOf(
        "$api/search?keyword=a&page=%d" to "Catálogo"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {

        val doc = app.get(mainUrl).document

        val items = doc.select(
            "article.n-movs-1, article.post, div.movie-item, article.item, .film-poster"
        ).mapNotNull { article ->

            val a = article.selectFirst("a[href]") ?: return@mapNotNull null
            val url = fixUrl(a.attr("href"))

            val titleElement = article.selectFirst(
                ".n-movs-15, h3, .title, .film-name, h2"
            )

            val title = titleElement?.text()?.trim() ?: return@mapNotNull null

            val poster = article.selectFirst("img[data-src], img[src]")?.let {
                val src = it.attr("data-src").ifBlank { it.attr("src") }
                fixUrl(src)
            }

            newMovieSearchResponse(title, url) {
                this.posterUrl = poster
            }
        }

        return newHomePageResponse(
            "Películas y series recientes",
            items
        )
    }

    // ================= SEARCH =================

    override suspend fun search(query: String): List<SearchResponse>? {

        val searchUrl = "$mainUrl/?s=$query"

        val doc = app.get(searchUrl).document

        return doc.select(
            "article.n-movs-1, .result-item, .film-item"
        ).mapNotNull {

            val a = it.selectFirst("a[href]") ?: return@mapNotNull null
            val url = fixUrl(a.attr("href"))

            val title = it.selectFirst("h3, .title, .name, .n-movs-15")
                ?.text()
                ?.trim()
                ?: return@mapNotNull null

            val poster = it.selectFirst("img")?.let { img ->
                val data = img.attr("data-src")
                val src = img.attr("src")
                fixUrl(if (data.isNotBlank()) data else src)
            }

            val type = when {
                url.contains("/series/") -> TvType.TvSeries
                url.contains("/anime/") -> TvType.Anime
                else -> TvType.Movie
            }

            when (type) {

                TvType.TvSeries -> newTvSeriesSearchResponse(title, url) {
                    posterUrl = poster
                }

                TvType.Anime -> newAnimeSearchResponse(title, url) {
                    posterUrl = poster
                }

                else -> newMovieSearchResponse(title, url) {
                    posterUrl = poster
                }
            }
        }
    }

    // ================= LOAD =================

    override suspend fun load(url: String): LoadResponse {

        val slug = url.substringAfterLast("/")

        val type = when {
            url.contains("/peliculas/") -> "movies"
            url.contains("/series/") -> "tvshows"
            url.contains("/animes/") -> "animes"
            else -> "movies"
        }

        val apiUrl = "$api/single/$type?slug=$slug&postType=$type"

        val json = app.get(apiUrl)
            .parsedSafe<ApiSingleResponse>() ?: throw ErrorLoadingException()

        val data = json.data

        val poster = mainUrl + data.images.poster
        val backdrop = mainUrl + data.images.backdrop

        if (type == "movies") {

            return newMovieLoadResponse(
                data.title,
                url,
                TvType.Movie,
                data._id.toString()
            ) {
                posterUrl = poster
                backgroundPosterUrl = backdrop
                plot = data.overview
                year = data.release_date.take(4).toIntOrNull()
            }

        } else {

            val episodesApi = "$api/episodes?_id=${data._id}&type=tvshows"

            val epJson = app.get(episodesApi)
                .parsedSafe<ApiEpisodesResponse>() ?: throw ErrorLoadingException()

            val episodes = epJson.data.posts.map {

                newEpisode(it._id.toString()) {
                    name = it.title
                    season = it.season_number
                    episode = it.episode_number
                }
            }

            return newTvSeriesLoadResponse(
                data.title,
                url,
                TvType.TvSeries,
                episodes
            ) {
                posterUrl = poster
                backgroundPosterUrl = backdrop
                plot = data.overview
                year = data.release_date.take(4).toIntOrNull()
            }
        }
    }

    // ================= LINKS =================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val json = app.get("$api/player?postId=$data")
            .parsedSafe<ApiPlayerResponse>() ?: return false

        json.data.embeds.forEach {

            loadExtractor(
                it.url,
                mainUrl,
                subtitleCallback,
                callback
            )
        }

        return true
    }
}

// ================= API MODELS =================

data class ApiSearchResponse(
    val data: ApiSearchData
)

data class ApiSearchData(
    val posts: List<ApiSearchItem>
)

data class ApiSearchItem(
    val _id: Int,
    val title: String,
    val slug: String,
    val poster: String,
    val type: String
)

data class ApiSingleResponse(
    val data: ApiSingleData
)

data class ApiSingleData(
    val _id: Int,
    val title: String,
    val overview: String?,
    val release_date: String,
    val images: ApiImages
)

data class ApiImages(
    val poster: String,
    val backdrop: String
)

data class ApiEpisodesResponse(
    val data: ApiEpisodesData
)

data class ApiEpisodesData(
    val posts: List<ApiEpisodeItem>
)

data class ApiEpisodeItem(
    val _id: Int,
    val title: String,
    val season_number: Int,
    val episode_number: Int
)

data class ApiPlayerResponse(
    val data: ApiPlayerData
)

data class ApiPlayerData(
    val embeds: List<ApiEmbed>
)

data class ApiEmbed(
    val url: String
)