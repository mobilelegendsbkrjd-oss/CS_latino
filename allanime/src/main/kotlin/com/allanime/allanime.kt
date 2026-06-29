package com.allanime

import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.DoodLaExtractor
import com.lagradost.cloudstream3.extractors.FileMoon
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.utils.*

class AllAnime : MainAPI() {

    override var mainUrl = "https://www.all-anime.net"
    override var name = "AllAnime"
    override val hasMainPage = true
    override var lang = "es"

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.Movie
    )

    companion object {
        private const val DATABASE_URL =
            "https://raw.githubusercontent.com/all-anime01/all-animelat/1231a840a485b342d9503ddec9af94980977e998/js/database.js"
    }

    // =========================
    // DATA CLASSES
    // =========================

    data class AnimeEpisode(
        val season: String? = null,
        val number: Double? = null,
        val title: String? = null,
        val duration: String? = null,
        val description: String? = null,
        val img: String? = null,
        val releaseDate: String? = null,
        val releaseTime: String? = null,
        val language: String? = null,
        val videoUrl: String? = null
    )

    data class AnimeData(
        val id: String? = null,
        val title: String? = null,
        val img: String? = null,
        val heroImg: String? = null,
        val logoImg: String? = null,
        val imgMobile: String? = null,
        val trailerUrl: String? = null,
        val description: String? = null,
        val genres: List<String>? = null,
        val rating: Double? = null,
        val seasons: Int? = null,
        val episodesTotal: Int? = null,
        val status: String? = null,
        val year: Int? = null,
        val type: String? = null,
        val quality: String? = null,
        val tags: List<String>? = null,
        val audio: String? = null,
        val creator: String? = null,
        val contentWarning: String? = null,
        val episodes: List<AnimeEpisode>? = null
    )

    // =========================
    // CACHE
    // =========================

    private var cachedAnimeList: List<AnimeData>? = null

    // =========================
    // GET DATABASE
    // =========================

    private suspend fun getAnimeList(): List<AnimeData> {

        cachedAnimeList?.let {
            return it
        }

        val raw = app.get(DATABASE_URL).text

        val cleaned = raw
            .replace("export const animeData =", "")
            .replace(Regex("""^\s*//.*$""", RegexOption.MULTILINE), "")
            .trim()
            .removeSuffix(";")

        val parsed = try {
            mapper.readValue<List<AnimeData>>(cleaned)
        } catch (e: Exception) {
            emptyList()
        }

        cachedAnimeList = parsed

        return parsed
    }

    // =========================
    // MAIN PAGE
    // =========================

    override val mainPage = mainPageOf(
        "all" to "All Anime"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val animeList = getAnimeList()

        val items = animeList
            .shuffled()
            .take(80)
            .mapNotNull { anime ->

                val id = anime.id ?: return@mapNotNull null

                newAnimeSearchResponse(
                    anime.title ?: return@mapNotNull null,
                    "$mainUrl/anime-details.html?id=$id",
                    if (anime.type == "Película") TvType.Movie else TvType.Anime
                ) {
                    posterUrl = anime.img
                }
            }

        return newHomePageResponse(
            listOf(
                HomePageList(
                    "All Anime",
                    items
                )
            ),
            hasNext = false
        )
    }

    // =========================
    // SEARCH
    // =========================

    override suspend fun search(query: String): List<SearchResponse> {

        val animeList = getAnimeList()

        return animeList.filter {

            val title = it.title ?: ""

            title.contains(query, true)

        }.mapNotNull { anime ->

            val id = anime.id ?: return@mapNotNull null

            newAnimeSearchResponse(
                anime.title ?: return@mapNotNull null,
                "$mainUrl/anime-details.html?id=$id",
                if (anime.type == "Película") TvType.Movie else TvType.Anime
            ) {
                posterUrl = anime.img
            }
        }
    }

    // =========================
    // LOAD DETAILS
    // =========================

    override suspend fun load(url: String): LoadResponse? {

        val id = url.substringAfter("id=")

        val anime = getAnimeList().find {
            it.id == id
        } ?: return null

        val episodes = anime.episodes?.mapNotNull { ep ->

            val video = ep.videoUrl ?: return@mapNotNull null

            newEpisode(video) {

                name = buildString {
                    append(ep.number?.toInt() ?: "?")
                    append(". ")
                    append(ep.title ?: "Episodio")
                }

                episode = ep.number?.toInt()

                season = ep.season
                    ?.replace("Temporada", "")
                    ?.trim()
                    ?.toIntOrNull()

                posterUrl = ep.img

                description = ep.description
            }

        } ?: emptyList()

        return newAnimeLoadResponse(
            anime.title ?: "",
            url,
            if (anime.type == "Película") TvType.Movie else TvType.Anime
        ) {

            posterUrl = anime.img

            backgroundPosterUrl = anime.heroImg

            plot = anime.description

            year = anime.year

            tags = anime.genres

            this.episodes = mutableMapOf(
                DubStatus.Subbed to episodes
            )

            showStatus = when (anime.status?.lowercase()) {
                "finalizado" -> ShowStatus.Completed
                else -> ShowStatus.Ongoing
            }
        }
    }

    // =========================
    // LOAD LINKS
    // =========================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val frameUrl = if (data.startsWith("http")) {
            data
        } else {
            fixUrl(data)
        }

        val document = app.get(
            frameUrl,
            referer = "$mainUrl/"
        ).document

        val html = document.html()

        val regex = Regex(
            """go_to_player\('([^']+)"""
        )

        val links = regex.findAll(html)
            .map { it.groupValues[1] }
            .distinct()
            .toList()

        links.forEach { link ->

            when {

                // =========================
                // STREAMWISH
                // =========================

                link.contains("streamwish") -> {

                    StreamWishExtractor().getSafeUrl(
                        link,
                        referer = frameUrl,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                }

                // =========================
                // FILEMOON
                // =========================

                link.contains("filemoon") -> {

                    FileMoon().getSafeUrl(
                        link,
                        referer = frameUrl,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                }

                // =========================
                // DOODSTREAM
                // =========================

                link.contains("dood") -> {

                    DoodLaExtractor().getSafeUrl(
                        link,
                        referer = frameUrl,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                }

                // =========================
                // DIRECT LINKS
                // =========================

                else -> {

                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = link
                        ) {
                            this.referer = frameUrl
                            quality = Qualities.Unknown.value
                            isM3u8 = link.contains(".m3u8")
                        }
                    )
                }
            }
        }

        return links.isNotEmpty()
    }
}