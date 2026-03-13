package com.lamovie

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty

class LaMovie : MainAPI() {

    override var mainUrl = "https://la.movie"
    override var name = "LaMovie"
    override var lang = "es"

    override val hasMainPage = true
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    private val api = "$mainUrl/wp-api/v1"

    // ================= FIX IMAGES =================

    private fun fixImg(url: String?): String? {
        if (url.isNullOrBlank()) return null

        val clean = url.replace("\"", "").trim()

        return when {
            clean.startsWith("http") -> clean
            clean.startsWith("/thumbs") -> "$mainUrl/wp-content/uploads$clean"
            clean.startsWith("/backdrops") -> "$mainUrl/wp-content/uploads$clean"
            clean.startsWith("/") -> "$mainUrl$clean"
            else -> "$mainUrl/$clean"
        }
    }

    // ================= MAIN PAGE =================

    override val mainPage = mainPageOf(
        "$api/listing/movies?postType=movies" to "Películas",
        "$api/listing/tvshows?postType=tvshows" to "Series",
        "$api/listing/animes?postType=animes" to "Anime",
        "$mainUrl/colecciones" to "Sugerencias de la comunidad"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        // ===== COLECCIONES =====

        if (request.data.contains("/colecciones")) {

            val doc = app.get(request.data).document

            val items = doc.select("a[href*=/coleccion/]").mapNotNull {

                val title = it.text().trim()
                val link = it.attr("href")

                if (title.isBlank()) return@mapNotNull null

                newTvSeriesSearchResponse(
                    title,
                    "lamovie_collection://$link"
                ) {
                    posterUrl = it.select("img").attr("src")
                }
            }

            return newHomePageResponse(request.name, items)
        }

        // ===== API NORMAL =====

        val url =
            "${request.data}&page=$page&postsPerPage=30&orderBy=latest&order=DESC"

        val json =
            app.get(url).parsedSafe<ApiListingResponse>()
                ?: throw ErrorLoadingException()

        val items = json.data.posts.map { post ->

            val poster = fixImg(post.images?.poster)

            val pageUrl = when (post.type) {
                "movies" -> "$mainUrl/peliculas/${post.slug}"
                "tvshows" -> "$mainUrl/series/${post.slug}"
                "animes" -> "$mainUrl/animes/${post.slug}"
                else -> "$mainUrl/peliculas/${post.slug}"
            }

            when (post.type) {

                "tvshows" -> newTvSeriesSearchResponse(post.title, pageUrl) {
                    posterUrl = poster
                }

                "animes" -> newAnimeSearchResponse(post.title, pageUrl) {
                    posterUrl = poster
                }

                else -> newMovieSearchResponse(post.title, pageUrl) {
                    posterUrl = poster
                }
            }
        }

        return newHomePageResponse(request.name, items)
    }

    // ================= SEARCH =================

    override suspend fun search(query: String): List<SearchResponse> {

        val url =
            "$api/search?postType=any&q=$query&postsPerPage=26"

        val json =
            app.get(url).parsedSafe<ApiListingResponse>()
                ?: return emptyList()

        return json.data.posts.map {

            val poster = fixImg(it.images?.poster)

            val pageUrl = when (it.type) {
                "movies" -> "$mainUrl/peliculas/${it.slug}"
                "tvshows" -> "$mainUrl/series/${it.slug}"
                "animes" -> "$mainUrl/animes/${it.slug}"
                else -> "$mainUrl/peliculas/${it.slug}"
            }

            when (it.type) {

                "tvshows" -> newTvSeriesSearchResponse(it.title, pageUrl) {
                    posterUrl = poster
                }

                "animes" -> newAnimeSearchResponse(it.title, pageUrl) {
                    posterUrl = poster
                }

                else -> newMovieSearchResponse(it.title, pageUrl) {
                    posterUrl = poster
                }
            }
        }
    }

    // ================= LOAD =================

    override suspend fun load(url: String): LoadResponse {

        // ===== COLECCION =====

        if (url.startsWith("lamovie_collection://")) {

            val realUrl =
                url.removePrefix("lamovie_collection://")

            val doc = app.get(realUrl).document

            val title =
                doc.selectFirst("h1")?.text()
                    ?: "Colección"

            val poster =
                doc.selectFirst("img")?.attr("src")

            val episodes =
                doc.select("a[href*=/peliculas/]")
                    .mapIndexed { index, el ->

                        val movieUrl = el.attr("href")

                        val movieTitle =
                            el.text().ifBlank {
                                "Película ${index + 1}"
                            }

                        newEpisode(movieUrl) {
                            name = movieTitle
                            episode = index + 1
                        }
                    }

            return newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes
            ) {

                posterUrl = poster
                backgroundPosterUrl = poster
            }
        }

        // ===== NORMAL =====

        val slug =
            url.trimEnd('/').substringAfterLast("/")

        val type = when {
            url.contains("/series/") -> "tvshows"
            url.contains("/animes/") -> "animes"
            else -> "movies"
        }

        val apiUrl =
            "$api/single/$type?slug=$slug&postType=$type"

        val json =
            app.get(apiUrl).parsedSafe<ApiSingleResponse>()
                ?: throw ErrorLoadingException()

        val data = json.data

        val poster = fixImg(data.images?.poster)
        val backdrop = fixImg(data.images?.backdrop)

        // ===== SERIES =====

        if (type == "tvshows" || type == "animes") {

            val episodes = mutableListOf<Episode>()

            try {

                val firstSeasonUrl =
                    "$api/single/episodes/list?_id=${data._id}&season=1&postsPerPage=50"

                val firstSeason =
                    app.get(firstSeasonUrl)
                        .parsedSafe<ApiEpisodeResponse>()

                val seasons =
                    firstSeason?.data?.seasons ?: listOf("1")

                seasons.forEach { seasonStr ->

                    val season =
                        seasonStr.toIntOrNull()
                            ?: return@forEach

                    val epUrl =
                        "$api/single/episodes/list?_id=${data._id}&season=$season&postsPerPage=50"

                    val epJson =
                        app.get(epUrl)
                            .parsedSafe<ApiEpisodeResponse>()

                    epJson?.data?.posts?.forEach { ep ->

                        val cleanId =
                            Regex("""\d+""")
                                .find(ep._id.toString())
                                ?.value ?: ep._id.toString()

                        episodes.add(
                            newEpisode(cleanId) {

                                name =
                                    ep.title
                                        ?: "Episodio ${ep.episode_number}"

                                this.season = season
                                this.episode = ep.episode_number

                                posterUrl =
                                    fixImg(ep.still_path)
                            }
                        )
                    }
                }

            } catch (_: Exception) {
            }

            val tvType =
                if (type == "animes")
                    TvType.Anime
                else
                    TvType.TvSeries

            return newTvSeriesLoadResponse(
                data.title,
                url,
                tvType,
                episodes
            ) {

                posterUrl = poster
                backgroundPosterUrl = backdrop
                plot = data.overview
                year = data.release_date?.take(4)?.toIntOrNull()
            }
        }

        // ===== MOVIE =====

        return newMovieLoadResponse(
            data.title,
            url,
            TvType.Movie,
            data._id.toString()
        ) {

            posterUrl = poster
            backgroundPosterUrl = backdrop
            plot = data.overview
            year = data.release_date?.take(4)?.toIntOrNull()
        }
    }

    // ================= LINKS =================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val cleanId =
            Regex("""\d+""").find(data)?.value ?: data

        val json =
            app.get("$api/player?postId=$cleanId&demo=0")
                .parsedSafe<ApiPlayerResponse>()
                ?: return false

        json.data.embeds.forEach { embed ->

            val url = embed.url ?: return@forEach

            if (url.contains("la.movie/embed")) {

                val doc = app.get(url).document
                val iframe = doc.select("iframe").attr("src")

                if (iframe.isNotBlank()) {

                    loadExtractor(
                        iframe,
                        url,
                        subtitleCallback,
                        callback
                    )
                }

            } else {

                loadExtractor(
                    url,
                    mainUrl,
                    subtitleCallback,
                    callback
                )
            }
        }

        return true
    }
}

// ================= DATA =================

data class ApiListingResponse(
    val data: ApiListingData
)

data class ApiListingData(
    val posts: List<ApiPost>
)

data class ApiPost(
    val _id: Int,
    val title: String,
    val slug: String,
    val type: String,
    val images: ApiImages?
)

data class ApiSingleResponse(
    val data: ApiSingleData
)

data class ApiSingleData(
    val _id: Int,
    val title: String,
    val overview: String?,
    val release_date: String?,
    val images: ApiImages?
)

data class ApiImages(
    val poster: String?,
    val backdrop: String?
)

data class ApiEpisodeResponse(
    val data: ApiEpisodeData
)

data class ApiEpisodeData(
    val posts: List<ApiEpisode>,
    val seasons: List<String>?
)

data class ApiEpisode(
    @JsonProperty("_id") val _id: Int,
    val title: String?,
    val episode_number: Int?,
    val still_path: String?
)

data class ApiPlayerResponse(
    val data: ApiPlayerData
)

data class ApiPlayerData(
    val embeds: List<ApiEmbed>
)

data class ApiEmbed(
    val url: String?
)