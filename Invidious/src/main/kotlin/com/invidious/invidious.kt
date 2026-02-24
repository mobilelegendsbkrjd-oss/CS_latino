package com.invidious

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

class InvidiousProvider : MainAPI() {

    override var name = "Invidious PRO+"
    override var mainUrl = "https://inv.nadeko.net"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "en"
    override val hasMainPage = true

    // =========================
    // INSTANCIAS
    // =========================

    private val instances = listOf(
        "https://inv.nadeko.net",
        "https://inv.vern.cc",
        "https://invidious.jing.rocks"
    )

    private val index = AtomicInteger(0)

    private suspend fun getWorkingInstance(): String {
        repeat(instances.size) {
            val i = index.getAndIncrement() % instances.size
            val base = instances[i]
            try {
                val res = app.get("$base/api/v1/trending?fields=videoId", timeout = 5)
                if (res.isSuccessful) {
                    mainUrl = base
                    return base
                }
            } catch (_: Exception) {}
        }
        return instances.first()
    }

    // =========================
    // SUSCRIPCIONES
    // =========================

    private fun getPrefs(): Context =
        AcraApplication.context

    private fun getSubscriptions(): MutableSet<String> {
        val prefs = getPrefs().getSharedPreferences("invidious_subs", Context.MODE_PRIVATE)
        return prefs.getStringSet("channels", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
    }

    private fun addSubscription(channelId: String) {
        val prefs = getPrefs().getSharedPreferences("invidious_subs", Context.MODE_PRIVATE)
        val set = getSubscriptions()
        set.add(channelId)
        prefs.edit().putStringSet("channels", set).apply()
    }

    // =========================
    // HOME
    // =========================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {

        val base = getWorkingInstance()

        val subs = getSubscriptions()

        val subscriptionVideos = subs.flatMap { channelId ->
            try {
                val res = tryParseJson<List<SearchEntry>>(
                    app.get("$base/api/v1/channels/$channelId/videos?fields=videoId,title").text
                )
                res ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }

        val trending = tryParseJson<List<SearchEntry>>(
            app.get("$base/api/v1/trending?fields=videoId,title").text
        )

        return newHomePageResponse(
            listOf(
                HomePageList(
                    "Suscripciones",
                    subscriptionVideos.map { it.toSearchResponse(this) },
                    true
                ),
                HomePageList(
                    "Trending",
                    trending?.map { it.toSearchResponse(this) } ?: emptyList(),
                    true
                )
            ),
            false
        )
    }

    // =========================
    // SEARCH
    // =========================

    override suspend fun search(query: String, page: Int): SearchResponseList? {

        val base = getWorkingInstance()
        val encoded = URLEncoder.encode(query, "UTF-8")

        val videos = tryParseJson<List<SearchEntry>>(
            app.get("$base/api/v1/search?q=$encoded&page=$page&type=video&fields=videoId,title").text
        )

        val playlists = tryParseJson<List<PlaylistEntry>>(
            app.get("$base/api/v1/search?q=$encoded&page=$page&type=playlist").text
        )

        val channels = tryParseJson<List<ChannelEntry>>(
            app.get("$base/api/v1/search?q=$encoded&page=$page&type=channel").text
        )

        val results = mutableListOf<SearchResponse>()

        videos?.forEach { results.add(it.toSearchResponse(this)) }
        playlists?.forEach { results.add(it.toSearchResponse(this)) }
        channels?.forEach { results.add(it.toSearchResponse(this)) }

        return results.toNewSearchResponseList()
    }

    // =========================
    // LOAD
    // =========================

    override suspend fun load(url: String): LoadResponse? {

        val base = getWorkingInstance()

        if (url.contains("/playlist?list=")) {
            val id = url.substringAfter("list=")
            return loadPlaylist(base, id)
        }

        if (url.contains("/channel/")) {
            val id = url.substringAfter("/channel/")
            return loadChannel(base, id)
        }

        val videoId = Regex("watch\\?v=([a-zA-Z0-9_-]+)")
            .find(url)?.groups?.get(1)?.value ?: return null

        val video = tryParseJson<VideoEntry>(
            app.get("$base/api/v1/videos/$videoId").text
        ) ?: return null

        return newMovieLoadResponse(
            video.title,
            "$base/watch?v=$videoId",
            TvType.Movie,
            videoId
        ) {
            plot = video.description
            posterUrl = "$base/vi/$videoId/hqdefault.jpg"
        }
    }

    private suspend fun loadPlaylist(base: String, id: String): LoadResponse? {
        val playlist = tryParseJson<PlaylistResponse>(
            app.get("$base/api/v1/playlists/$id").text
        ) ?: return null

        return newMovieLoadResponse(
            playlist.title,
            "$base/playlist?list=$id",
            TvType.Movie,
            id
        ) {
            recommendations = playlist.videos.map {
                it.toSearchResponse(this@InvidiousProvider)
            }
        }
    }

    private suspend fun loadChannel(base: String, id: String): LoadResponse? {

        addSubscription(id)

        val videos = tryParseJson<List<SearchEntry>>(
            app.get("$base/api/v1/channels/$id/videos?fields=videoId,title").text
        ) ?: return null

        return newMovieLoadResponse(
            "Canal $id",
            "$base/channel/$id",
            TvType.Movie,
            id
        ) {
            recommendations = videos.map {
                it.toSearchResponse(this@InvidiousProvider)
            }
        }
    }

    // =========================
    // LINKS
    // =========================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val base = getWorkingInstance()

        val video = tryParseJson<VideoEntry>(
            app.get("$base/api/v1/videos/$data").text
        ) ?: return false

        video.formatStreams?.forEach { stream ->
            val q = stream.qualityLabel?.replace("p", "")?.toIntOrNull()
                ?: Qualities.Unknown.value

            callback(
                newExtractorLink(
                    name,
                    stream.qualityLabel ?: "Unknown",
                    stream.url
                ) {
                    quality = q
                    type = ExtractorLinkType.VIDEO
                    referer = base
                }
            )
        }

        return true
    }

    // =========================
    // DATA
    // =========================

    private data class SearchEntry(
        val title: String,
        val videoId: String
    ) {
        fun toSearchResponse(provider: InvidiousProvider): SearchResponse {
            return provider.newMovieSearchResponse(
                title,
                "${provider.mainUrl}/watch?v=$videoId",
                TvType.Movie
            )
        }
    }

    private data class PlaylistEntry(
        val title: String,
        val playlistId: String
    )

    private data class ChannelEntry(
        val author: String,
        val authorId: String
    )

    private data class PlaylistResponse(
        val title: String,
        val videos: List<SearchEntry>
    )

    private data class VideoEntry(
        val title: String,
        val description: String?,
        val videoId: String,
        val formatStreams: List<FormatStream>?
    )

    private data class FormatStream(
        val url: String,
        val qualityLabel: String?
    )
}
