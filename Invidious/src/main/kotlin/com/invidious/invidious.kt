package com.invidious

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.StringUtils.encodeUri
import com.lagradost.cloudstream3.utils.loadExtractor

@Suppress("DEPRECATION")
class YouTube : MainAPI() {

    override var mainUrl = "https://yewtu.be"
    override var name = "YouTube"
    override val hasMainPage = true
    override var lang = "MX"
    override val supportedTypes = setOf(TvType.Others)

    // ================= HOME =================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {

        suspend fun searchSection(query: String): List<SearchEntry> {
            return tryParseJson(
                app.get(
                    "$mainUrl/api/v1/search?q=${query.encodeUri()}&type=video&page=1",
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0",
                        "Accept" to "application/json"
                    )
                ).text
            ) ?: emptyList()
        }

        val test = searchSection("mrbeast")

        return newHomePageResponse(
            listOf(
                HomePageList("🔥 YouTube", test.mapNotNull { it.toSearchResponse(this) })
            ),
            false
        )
    }

    // ================= SEARCH =================
    override suspend fun search(query: String): List<SearchResponse> {
        val res = tryParseJson<List<SearchEntry>>(
            app.get(
                "$mainUrl/api/v1/search?q=${query.encodeUri()}&type=video&page=1",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0",
                    "Accept" to "application/json"
                )
            ).text
        )
        return res?.mapNotNull { it.toSearchResponse(this) } ?: emptyList()
    }

    // ================= LOAD =================
    override suspend fun load(url: String): LoadResponse? {

        val videoId = Regex("watch\\?v=([a-zA-Z0-9_-]+)")
            .find(url)?.groupValues?.get(1) ?: return null

        val res = tryParseJson<VideoEntry>(
            app.get(
                "$mainUrl/api/v1/videos/$videoId",
                headers = mapOf("User-Agent" to "Mozilla/5.0")
            ).text
        ) ?: return null

        return res.toLoadResponse(this)
    }

    // ================= DATA =================

    private data class SearchEntry(
        val title: String? = null,
        val videoId: String? = null
    ) {
        fun toSearchResponse(provider: YouTube): SearchResponse? {
            val id = videoId ?: return null
            val t = title ?: "Sin título"

            return provider.newMovieSearchResponse(
                t,
                "${provider.mainUrl}/watch?v=$id",
                TvType.Others
            ) {
                posterUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg"
            }
        }
    }

    private data class Thumbnail(
        val url: String
    )

    private data class FormatStream(
        val url: String,
        val qualityLabel: String? = null
    )

    private data class VideoEntry(
        val title: String?,
        val description: String?,
        val videoId: String,
        val recommendedVideos: List<SearchEntry>? = emptyList(),
        val author: String?,
        val authorThumbnails: List<Thumbnail>? = emptyList(),
        val formatStreams: List<FormatStream>? = emptyList()
    ) {
        suspend fun toLoadResponse(provider: YouTube): LoadResponse {
            return provider.newMovieLoadResponse(
                title ?: "YouTube",
                "${provider.mainUrl}/watch?v=$videoId",
                TvType.Others,
                videoId
            ) {
                plot = description ?: ""
                posterUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

                recommendations = recommendedVideos
                    ?.mapNotNull { it.toSearchResponse(provider) } ?: emptyList()

                actors = listOf(
                    ActorData(
                        Actor(
                            author ?: "YouTube",
                            authorThumbnails?.lastOrNull()?.url ?: ""
                        ),
                        roleString = "Canal"
                    )
                )
            }
        }
    }

    // ================= LINKS =================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (com.lagradost.cloudstream3.utils.ExtractorLink) -> Unit
    ): Boolean {

        val res = tryParseJson<VideoEntry>(
            app.get(
                "$mainUrl/api/v1/videos/$data",
                headers = mapOf("User-Agent" to "Mozilla/5.0")
            ).text
        )

        // 🔥 Direct streams
        res?.formatStreams?.forEach { stream ->

            val quality = when {
                stream.qualityLabel?.contains("1080") == true -> 1080
                stream.qualityLabel?.contains("720") == true -> 720
                stream.qualityLabel?.contains("480") == true -> 480
                else -> 0
            }

            callback(
                newExtractorLink(
                    source = "Invidious",
                    name = "Direct ${stream.qualityLabel ?: "Auto"}",
                    url = stream.url,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = quality
                }
            )
        }

        // 🔥 DASH
        callback(
            newExtractorLink(
                source = "Invidious",
                name = "DASH",
                url = "$mainUrl/api/manifest/dash/id/$data",
                type = ExtractorLinkType.DASH
            )
        )

        // 🔥 fallback
        loadExtractor(
            "https://youtube.com/watch?v=$data",
            subtitleCallback,
            callback
        )

        return true
    }
}