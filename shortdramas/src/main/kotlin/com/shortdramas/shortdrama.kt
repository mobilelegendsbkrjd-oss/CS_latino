package com.shortdramas

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLDecoder

class ShortDramas : MainAPI() {
    override var name = "ShortDramas"
    override var mainUrl = "https://www.youtube.com"
    override var lang = "mx"
    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "https://www.youtube.com/@DramaBoxOfficialES" to "DramaBox",
        "https://www.youtube.com/@UniversoDramas" to "UniversoDramas",
        "https://www.youtube.com/@kerineymar" to "DramaNocturno",
        "https://www.youtube.com/@Drama-nocturno" to "DramaNocturno2",
        "https://www.youtube.com/@DramaLegendarioTV" to "DramaLegendario",
        "https://www.youtube.com/@RageDrama" to "RageDrama",
        "https://www.youtube.com/@TeatroRel%C3%A1mpago-orz" to "TeatroRelampago",
        "https://www.youtube.com/@UsuarioelSistema" to "Espectador del Sistema",
        "https://www.youtube.com/@DramasChinosEnEspa%C3%B1ol" to "Dramas Chinos Español",
        "https://www.youtube.com/@DramasChinosenEspa%C3%B1ol11" to "Dramas Chinos Español 11",
        "https://www.youtube.com/@Amor-en-breve" to "Amor en Breve",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.data

        val items = if (url.contains("/playlists")) {
            getPlaylists(url)
        } else {
            getChannelVideos(url)
        }

        return newHomePageResponse(
            listOf(HomePageList(request.name, items, isHorizontalImages = true)),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/results?search_query=${query.replace(" ", "+")}"
        return getChannelVideos(url)
    }

    override suspend fun load(url: String): LoadResponse {
        return if (url.contains("playlist?list=")) {
            val episodes = getPlaylistVideos(url)

            val title = cleanTitle(
                app.get(url, headers = ytHeaders).document
                    .selectFirst("meta[property=og:title]")
                    ?.attr("content")
                    ?: "Serie"
            )

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = episodes.firstOrNull()?.posterUrl
                plot = "Serie cargada desde playlist de YouTube."
            }
        } else {
            val doc = app.get(url, headers = ytHeaders).document

            val title = cleanTitle(
                doc.selectFirst("meta[property=og:title]")?.attr("content")
                    ?: doc.title()
                    ?: "Video"
            )

            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")

            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                plot = "Video cargado desde YouTube."
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fixedUrl = when {
            data.contains("watch?v=") -> {
                val id = data.substringAfter("watch?v=").substringBefore("&")
                "$mainUrl/embed/$id"
            }
            data.contains("youtu.be/") -> {
                val id = data.substringAfter("youtu.be/").substringBefore("?")
                "$mainUrl/embed/$id"
            }
            else -> data
        }

        return loadExtractor(fixedUrl, "$mainUrl/", subtitleCallback, callback)
    }

    private suspend fun getChannelVideos(url: String): List<SearchResponse> {
        val channelId = getChannelId(url) ?: return emptyList()
        val feedUrl = "$mainUrl/feeds/videos.xml?channel_id=$channelId"

        val doc = app.get(feedUrl, headers = ytHeaders).document
        val results = mutableListOf<SearchResponse>()

        doc.select("entry").forEach { entry ->
            val title = cleanTitle(entry.selectFirst("title")?.text() ?: return@forEach)
            val videoId = entry.selectFirst("yt|videoId")?.text()
                ?: entry.selectFirst("videoId")?.text()
                ?: return@forEach

            val videoUrl = "$mainUrl/embed/$videoId"
            val poster = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

            results.add(
                newMovieSearchResponse(title, videoUrl, TvType.Movie) {
                    posterUrl = poster
                }
            )
        }

        return results
    }

    private suspend fun getChannelId(handleUrl: String): String? {
        val html = app.get(handleUrl, headers = ytHeaders).text

        return Regex(""""channelId":"(UC[^"]+)"""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?: Regex("""https://www.youtube.com/channel/(UC[^"]+)""")
                .find(html)
                ?.groupValues
                ?.getOrNull(1)
            ?: Regex(""""externalId":"(UC[^"]+)"""")
                .find(html)
                ?.groupValues
                ?.getOrNull(1)
    }

    private suspend fun getPlaylists(url: String): List<SearchResponse> {
        val html = app.get(url, headers = ytHeaders).text
        val results = mutableListOf<SearchResponse>()

        val regex = Regex(
            """"playlistId"\s*:\s*"([^"]+)".{0,3000}?"title"\s*:\s*\{\s*"simpleText"\s*:\s*"([^"]+)".{0,3000}?"thumbnail"\s*:\s*\{\s*"thumbnails"\s*:\s*\[(.*?)\]""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )

        regex.findAll(html).forEach { match ->
            val playlistId = match.groupValues[1]
            val title = cleanTitle(match.groupValues[2])
            val thumbBlock = match.groupValues[3]

            if (title.isBlank()) return@forEach
            if (results.any { it.url?.contains(playlistId) == true }) return@forEach

            val poster = extractBestThumb(thumbBlock)

            results.add(
                newTvSeriesSearchResponse(
                    title,
                    "$mainUrl/playlist?list=$playlistId",
                    TvType.TvSeries
                ) {
                    posterUrl = poster
                }
            )
        }

        return results
    }

    private suspend fun getPlaylistVideos(url: String): List<Episode> {
        val html = app.get(url, headers = ytHeaders).text
        val episodes = mutableListOf<Episode>()

        val regex = Regex(
            """"videoId"\s*:\s*"([^"]+)".{0,3000}?"title"\s*:\s*\{\s*"runs"\s*:\s*\[\s*\{\s*"text"\s*:\s*"([^"]+)".{0,3000}?"thumbnail"\s*:\s*\{\s*"thumbnails"\s*:\s*\[(.*?)\]""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )

        regex.findAll(html).forEachIndexed { index, match ->
            val videoId = match.groupValues[1]
            val title = cleanTitle(match.groupValues[2])
            val thumbBlock = match.groupValues[3]

            if (title.isBlank()) return@forEachIndexed
            if (title.equals("Shorts", true)) return@forEachIndexed
            if (episodes.any { it.data.contains(videoId) }) return@forEachIndexed

            val poster = extractBestThumb(thumbBlock)
                ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

            episodes.add(
                newEpisode("$mainUrl/watch?v=$videoId") {
                    name = title
                    episode = index + 1
                    posterUrl = poster
                }
            )
        }

        return episodes
    }

    private fun extractBestThumb(block: String): String? {
        return Regex(""""url":"(.*?)"""")
            .findAll(block)
            .map { it.groupValues[1].replace("\\u0026", "&") }
            .lastOrNull()
    }

    private fun cleanTitle(title: String): String {
        return title
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .replace("&#39;", "'")
            .replace("&quot;", "\"")
            .replace("\\\"", "\"")
            .trim()
    }

    private val ytHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/145.0 Safari/537.36",
        "Accept-Language" to "es-419,es;q=0.9,en;q=0.8"
    )

    data class YtChannel(
        val name: String,
        val handleUrl: String
    )
}