package com.invidious

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.AcraApplication
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

class InvidiousProvider : MainAPI() {

    override var mainUrl = "https://inv.nadeko.net"
    override var name = "Invidious"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)

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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val base = getWorkingInstance()

        val trendingJson = app.get("$base/api/v1/trending?fields=videoId,title").text

        val regex = """"videoId":"(.*?)".*?"title":"(.*?)"""".toRegex()

        val results = regex.findAll(trendingJson).map {
            val id = it.groupValues[1]
            val title = it.groupValues[2]
            newMovieSearchResponse(title, "$base/watch?v=$id", TvType.Movie)
        }.toList()

        return newHomePageResponse(listOf(HomePageList("Trending", results)))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val base = getWorkingInstance()
        val encoded = URLEncoder.encode(query, "UTF-8")

        val json = app.get("$base/api/v1/search?q=$encoded&type=video").text

        val regex = """"videoId":"(.*?)".*?"title":"(.*?)"""".toRegex()

        return regex.findAll(json).map {
            val id = it.groupValues[1]
            val title = it.groupValues[2]
            newMovieSearchResponse(title, "$base/watch?v=$id", TvType.Movie)
        }.toList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val base = getWorkingInstance()

        val videoId = Regex("watch\\?v=([a-zA-Z0-9_-]+)")
            .find(url)?.groups?.get(1)?.value ?: return null

        val json = app.get("$base/api/v1/videos/$videoId").text

        val title = """"title":"(.*?)"""".toRegex().find(json)?.groupValues?.get(1) ?: return null
        val description = """"description":"(.*?)"""".toRegex().find(json)?.groupValues?.get(1)

        return newMovieLoadResponse(title, url, TvType.Movie, videoId) {
            plot = description
            posterUrl = "$base/vi/$videoId/hqdefault.jpg"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val base = getWorkingInstance()
        val json = app.get("$base/api/v1/videos/$data").text

        val streamRegex = """"url":"(.*?)".*?"qualityLabel":"(.*?)"""".toRegex()

        streamRegex.findAll(json).forEach {
            val url = it.groupValues[1].replace("\\u0026", "&")
            val qualityLabel = it.groupValues[2]
            val q = qualityLabel.replace("p", "").toIntOrNull() ?: Qualities.Unknown.value

            callback(
                newExtractorLink(
                    name,
                    qualityLabel,
                    url
                ) {
                    quality = q
                    this.referer = base
                }
            )
        }

        return true
    }
}
