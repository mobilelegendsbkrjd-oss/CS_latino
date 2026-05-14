package com.tdtchannels

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.serialization.*
import kotlinx.serialization.json.Json

class TDTChannels : MainAPI() {

    override var mainUrl = "https://www.tdtchannels.com"
    override var name = "TDTChannels"
    override var lang = "es"

    override val supportedTypes = setOf(TvType.Live)

    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/lists/tv.json" to "📺 TV España + Internacional",
        "$mainUrl/lists/radio.json" to "📻 Radio"
    )

    // =============================
    // JSON MODELS
    // =============================

    @Serializable
    data class RootData(
        val countries: List<Country>? = null
    )

    @Serializable
    data class Country(
        val name: String? = null,
        val ambits: List<Ambit>? = null,
        val channels: List<Channel>? = null
    )

    @Serializable
    data class Ambit(
        val name: String? = null,
        val channels: List<Channel>? = null
    )

    @Serializable
    data class Channel(
        val name: String? = null,
        val logo: String? = null,
        val options: List<Option>? = null
    )

    @Serializable
    data class Option(
        val format: String? = null,
        val url: String? = null
    )

    @Serializable
    data class EpgRoot(
        val channels: Map<String, List<EpgProgram>>? = null
    )

    @Serializable
    data class EpgProgram(
        val hi: Long? = null,
        val hf: Long? = null,
        val t: String? = null,
        val d: String? = null
    )

    private val json = Json { ignoreUnknownKeys = true }

    // =============================
    // CHANNEL FETCH
    // =============================

    private suspend fun getAllChannels(jsonUrl: String): List<Triple<String, String?, String?>> {

        val response = app.get(jsonUrl, cacheTime = 3600)

        val root: RootData? = try {
            json.decodeFromString(response.text)
        } catch (_: Exception) {
            null
        }

        val list = mutableListOf<Triple<String, String?, String?>>()

        root?.countries?.forEach { country ->

            country.ambits?.forEach { ambit ->
                ambit.channels?.forEach { channel ->
                    processChannel(channel, list)
                }
            }

            country.channels?.forEach { channel ->
                processChannel(channel, list)
            }
        }

        return list.distinctBy { it.first }
    }

    private fun processChannel(
        channel: Channel,
        list: MutableList<Triple<String, String?, String?>>
    ) {

        val title = channel.name?.trim() ?: return

        val bestOption = channel.options
            ?.filter { it.url?.startsWith("http") == true }
            ?.maxByOrNull {

                when (it.format?.lowercase()) {

                    "m3u8", "hls" -> 10
                    "youtube" -> 5
                    else -> 1
                }
            }

        bestOption?.url?.let {

            list.add(
                Triple(
                    title,
                    channel.logo,
                    it
                )
            )
        }
    }

    // =============================
    // EPG
    // =============================

    private suspend fun getCurrentEpg(channelTitle: String?): String? {

        if (channelTitle.isNullOrBlank()) return null

        val epgResp = app.get(
            "$mainUrl/epg/TV.json",
            cacheTime = 1800
        )

        val epg: EpgRoot? = try {
            json.decodeFromString(epgResp.text)
        } catch (_: Exception) {
            null
        }

        val now = System.currentTimeMillis() / 1000

        val matchingKey = epg?.channels?.keys?.find {
            it.lowercase().contains(channelTitle.lowercase())
        } ?: return null

        val programs = epg.channels[matchingKey] ?: return null

        val current = programs.find {

            val start = it.hi ?: 0
            val end = it.hf ?: 0

            start <= now && end > now
        }

        return current?.let {

            val title = it.t ?: "Programa actual"
            val desc = it.d?.take(100) ?: ""

            "$title - $desc"
        }
    }

    // =============================
    // MAIN PAGE
    // =============================

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val channels = getAllChannels(request.data)

        val items = channels.map {

            val (title, logo, url) = it

            val data = "$title||$url"

            newLiveSearchResponse(title, data) {
                posterUrl = logo
            }
        }

        return newHomePageResponse(
            HomePageList(request.name, items)
        )
    }

    // =============================
    // SEARCH
    // =============================

    override suspend fun search(query: String): List<SearchResponse> {

        val q = query.lowercase().trim()

        if (q.length < 2) return emptyList()

        val tvChannels = getAllChannels("$mainUrl/lists/tv.json")
        val radioChannels = getAllChannels("$mainUrl/lists/radio.json")

        return (tvChannels + radioChannels)
            .filter { it.first.lowercase().contains(q) }
            .map {

                val (title, logo, url) = it

                val data = "$title||$url"

                newLiveSearchResponse(title, data) {
                    posterUrl = logo
                }
            }
    }

    // =============================
    // LOAD
    // =============================

    override suspend fun load(url: String): LoadResponse {

        val parts = url.split("||")

        val channelName = parts[0]
        val streamUrl = parts[1]

        val epg = getCurrentEpg(channelName)

        return newMovieLoadResponse(
            name = channelName,
            url = streamUrl,
            type = TvType.Live,
            dataUrl = streamUrl
        ) {

            plot = "Transmisión legal vía TDTChannels\n$epg"
        }
    }

    // =============================
    // STREAM
    // =============================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val isM3u8 = data.contains(".m3u8")
        val isDash = data.contains(".mpd")

        callback.invoke(
            newExtractorLink(
                source = name,
                name = "Direct Stream",
                url = data
            ) {
                referer = "https://play.tdtchannels.com/"
                quality = Qualities.Unknown.value
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "https://play.tdtchannels.com/",
                    "Origin" to "https://play.tdtchannels.com"
                )
            }
        )

        return true
    }
}