package com.cinezo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.net.URLEncoder

class Cinezo : MainAPI() {
    override var mainUrl = "https://www.cinezo.net"
    override var name = "Cinezo"
    override var lang = "es"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val tmdbApiKey = "e8b0fb59314ad85cb0b08fb5df61875d"
    private val tmdbImg = "https://image.tmdb.org/t/p/w500"

    override val mainPage = mainPageOf(
        "movie|trending|hi|" to "🔥 Trending Movies",
        "movie|now_playing|hi|" to "🔟 Top 10 Today",
        "movie|popular|hi|" to "🎬 Popular Movies",
        "movie|top_rated|hi|" to "⭐ Top Rated Movies",

        "tv|popular|ja|" to "📺 Popular TV",
        "tv|top_rated|ja|" to "⭐ Top Rated TV",
        "tv|provider||8" to "📺 Series on Netflix",
        "tv|provider||9" to "📺 Series on Prime",
        "tv|provider||1899" to "📺 Series on Max",
        "tv|provider||337" to "📺 Series on Disney+",
        "tv|provider||350" to "📺 Series on AppleTV",
        "tv|provider||531" to "📺 Series on Paramount",

        "anime|popular||" to "🍥 Anime"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val parts = request.data.split("|")
        val category = parts.getOrNull(0) ?: "movie"
        val type = parts.getOrNull(1) ?: "popular"
        val withoutLang = parts.getOrNull(2).orEmpty()
        val providerId = parts.getOrNull(3).orEmpty()

        val path = when {
            type == "trending" -> {
                "trending/$category/week?language=it&page=$page"
            }

            type == "now_playing" && category == "movie" -> {
                "movie/now_playing?language=it&page=$page"
            }

            type == "provider" && providerId.isNotBlank() -> {
                "discover/tv?language=it&page=$page&with_watch_providers=$providerId&watch_region=US"
            }

            category == "anime" -> {
                "discover/tv?language=it&page=$page&with_keywords=210024|287501"
            }

            else -> {
                "$category/$type?language=it&page=$page"
            }
        }

        val json = tmdbGet(path)
        val arr = json?.optJSONArray("results")
        val items = mutableListOf<SearchResponse>()

        if (arr != null) {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue

                if (withoutLang.isNotBlank() && obj.optString("original_language") == withoutLang) {
                    continue
                }

                val isMovie = category == "movie"
                val result = obj.toSearchResult(isMovie)
                if (result != null) items.add(result)
            }
        }

        return newHomePageResponse(
            request.name,
            items,
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val out = mutableListOf<SearchResponse>()

        tmdbGet("search/movie?query=$q&language=it")?.optJSONArray("results")?.let { arr ->
            for (i in 0 until minOf(arr.length(), 10)) {
                arr.optJSONObject(i)?.toSearchResult(true)?.let { out.add(it) }
            }
        }

        tmdbGet("search/tv?query=$q&language=it")?.optJSONArray("results")?.let { arr ->
            for (i in 0 until minOf(arr.length(), 10)) {
                arr.optJSONObject(i)?.toSearchResult(false)?.let { out.add(it) }
            }
        }

        return out
    }

    override suspend fun load(url: String): LoadResponse {
        val clean = url.substringBefore("?")
        val isMovie = clean.contains("/movie/")
        val id = clean.substringAfterLast("/").toIntOrNull()
            ?: throw ErrorLoadingException("TMDB ID inválido")

        if (isMovie) {
            val obj = tmdbGet("movie/$id?language=it")
            val title = obj?.optString("title")?.takeIf { it.isNotBlank() } ?: "Película $id"
            val poster = obj?.optString("poster_path")?.takeIf { it.isNotBlank() }?.let { tmdbImg + it }
            val plot = obj?.optString("overview")
            val year = obj?.optString("release_date")?.take(4)?.toIntOrNull()

            return newMovieLoadResponse(title, url, TvType.Movie, "movie|$id") {
                posterUrl = poster
                backgroundPosterUrl = poster
                this.plot = plot
                this.year = year
            }
        }

        val obj = tmdbGet("tv/$id?language=it")
        val title = obj?.optString("name")?.takeIf { it.isNotBlank() } ?: "Serie $id"
        val poster = obj?.optString("poster_path")?.takeIf { it.isNotBlank() }?.let { tmdbImg + it }
        val plot = obj?.optString("overview")
        val year = obj?.optString("first_air_date")?.take(4)?.toIntOrNull()

        val episodes = mutableListOf<Episode>()
        val seasons = obj?.optJSONArray("seasons")

        if (seasons != null) {
            for (s in 0 until seasons.length()) {
                val season = seasons.optJSONObject(s) ?: continue
                val sn = season.optInt("season_number")
                if (sn == 0) continue

                val epCount = season.optInt("episode_count")
                for (ep in 1..epCount) {
                    episodes.add(
                        newEpisode("tv|$id|$sn|$ep") {
                            name = "Episodio $ep"
                            this.season = sn
                            episode = ep
                        }
                    )
                }
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster
            backgroundPosterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        val type = parts.getOrNull(0) ?: return false
        val id = parts.getOrNull(1)?.toIntOrNull() ?: return false
        val season = parts.getOrNull(2)?.toIntOrNull() ?: 1
        val episode = parts.getOrNull(3)?.toIntOrNull() ?: 1

        var found = false

        val resolved = if (type == "movie") {
            CinezoResolver.getStream(id, "movie")
        } else {
            CinezoResolver.getStream(id, "tv", season, episode)
        }

        if (resolved != null) {
            resolved.subtitles.forEach {
                subtitleCallback(newSubtitleFile(it.name, it.url))
            }

            callback(
                newExtractorLink(
                    "Cinezo",
                    "Cinezo",
                    resolved.url,
                    INFER_TYPE
                ) {
                    headers = resolved.headers
                    referer = resolved.headers["referer"]
                        ?: resolved.headers["Referer"]
                                ?: "https://player.cinezo.live/embed/"
                    quality = getQualityFromName(resolved.url)
                }
            )

            found = true
        }

        val iframeServers = if (type == "movie") {
            listOf(
                "https://111movies.net/movie/$id?autoplay=1",
                "https://player.cinezo.live/embed/movie/$id",
                "https://player.videasy.net/movie/$id",
                "https://vidfast.pro/movie/$id?autoPlay=true"
            )
        } else {
            listOf(
                "https://111movies.net/tv/$id/$season/$episode?autoplay=1&autoNext=1",
                "https://player.cinezo.live/embed/tv/$id/$season/$episode",
                "https://player.videasy.net/tv/$id/$season/$episode",
                "https://vidfast.pro/tv/$id/$season/$episode?autoPlay=true"
            )
        }

        for (url in iframeServers) {
            try {
                println("CINEZO TRY IFRAME => $url")

                loadExtractor(url, mainUrl, subtitleCallback) {
                    found = true
                    callback(it)
                }
            } catch (e: Exception) {
                println("CINEZO IFRAME FAIL => $url => ${e.message}")
            }
        }

        return found
    }

    private fun JSONObject.toSearchResult(isMovie: Boolean): SearchResponse? {
        val id = optInt("id")
        if (id <= 0) return null

        val title = if (isMovie) optString("title") else optString("name")
        if (title.isBlank()) return null

        val year = if (isMovie) {
            optString("release_date").take(4).toIntOrNull()
        } else {
            optString("first_air_date").take(4).toIntOrNull()
        }

        val poster = optString("poster_path").takeIf { it.isNotBlank() }?.let { tmdbImg + it }

        return if (isMovie) {
            newMovieSearchResponse(title, "$mainUrl/movie/$id", TvType.Movie) {
                posterUrl = poster
                this.year = year
            }
        } else {
            newTvSeriesSearchResponse(title, "$mainUrl/tv/$id", TvType.TvSeries) {
                posterUrl = poster
                this.year = year
            }
        }
    }

    private suspend fun tmdbGet(path: String): JSONObject? {
        if (tmdbApiKey == "PON_AQUI_TU_TMDB_API_KEY") return null
        val sep = if (path.contains("?")) "&" else "?"
        return runCatching {
            JSONObject(app.get("https://api.themoviedb.org/3/$path${sep}api_key=$tmdbApiKey").text)
        }.getOrNull()
    }
}