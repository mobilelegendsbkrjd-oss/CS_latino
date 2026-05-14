package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.StringUtils.encodeUri
import com.lagradost.cloudstream3.utils.loadExtractor

class InvidiousProvider : MainAPI() {

    // Lista actualizada de espejos que funcionan sin JS
    private val mirrors = listOf(
        "https://invidious.privacydev.net",
        "https://inv.odyssey346.dev",
        "https://invidious.lunar.icu",
        "https://invidious.nerdvpn.de",
        "https://inv.nadeko.net",
        "https://yewtu.be"
    )

    override var mainUrl = mirrors.first()
    override var name = "Invidious"
    override val supportedTypes = setOf(TvType.Others)
    override var lang = "es"
    override val hasMainPage = true

    // Headers para parecerse más a un navegador real
    private val headers = mapOf(
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "es-ES,es;q=0.9",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Origin" to "https://youtube.com",
        "Referer" to "https://youtube.com/"
    )

    private suspend fun safeGet(url: String): String? {
        for (m in mirrors) {
            try {
                val fixed = url.replace(mainUrl, m)
                val res = app.get(fixed, headers = headers, timeout = 30).text
                if (res.isNotEmpty() && !res.contains("Please enable JavaScript")) {
                    mainUrl = m
                    return res
                }
            } catch (e: Exception) {
                // Continuar con el siguiente espejo
                continue
            }
        }
        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val popular = tryParseJson<List<BasicVideoEntry>>(
            safeGet("$mainUrl/api/v1/popular?hl=es&region=MX") ?: ""
        )
        val trending = tryParseJson<List<BasicVideoEntry>>(
            safeGet("$mainUrl/api/v1/trending?hl=es&region=MX") ?: ""
        )

        return newHomePageResponse(
            listOf(
                HomePageList("Popular 🇲🇽", popular?.mapNotNull { it.toSearchResponse(this) } ?: emptyList(), true),
                HomePageList("Tendencias 🇲🇽", trending?.mapNotNull { it.toSearchResponse(this) } ?: emptyList(), true)
            ),
            false
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val res = tryParseJson<List<BasicVideoEntry>>(
            safeGet("$mainUrl/api/v1/search?q=${query.encodeUri()}&page=$page&type=video&hl=es&region=MX")
                ?: ""
        )
        return res?.mapNotNull { it.toSearchResponse(this) }?.toNewSearchResponseList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        val res = tryParseJson<List<BasicVideoEntry>>(
            safeGet("$mainUrl/api/v1/search?q=${query.encodeUri()}&page=1&type=video&hl=es&region=MX")
                ?: ""
        )
        return res?.mapNotNull { it.toSearchResponse(this) } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val videoId = Regex("""watch\?v=([a-zA-Z0-9_-]{11})""").find(url)?.groupValues?.get(1) ?: return null
        val res = tryParseJson<VideoEntry>(
            safeGet("$mainUrl/api/v1/videos/$videoId?hl=es") ?: return null
        )
        return res?.toLoadResponse(this)
    }

    private data class BasicVideoEntry(
        val title: String? = null,
        val videoId: String? = null,
        val author: String? = null,
        val videoThumbnails: List<Thumbnail>? = emptyList()
    ) {
        fun toSearchResponse(provider: InvidiousProvider): SearchResponse? {
            val id = videoId ?: return null
            val titleText = title ?: "Sin título"

            return provider.newMovieSearchResponse(
                titleText,
                "${provider.mainUrl}/watch?v=$id",
                TvType.Movie
            ) {
                this.posterUrl = videoThumbnails?.find { it.quality == "mqdefault" }?.url
                    ?: "${provider.mainUrl}/vi/$id/mqdefault.jpg"
            }
        }
    }

    private data class VideoEntry(
        val title: String? = null,
        val description: String? = null,
        val videoId: String? = null,
        val recommendedVideos: List<BasicVideoEntry>? = emptyList(),
        val author: String? = null,
        val authorThumbnails: List<Thumbnail>? = emptyList(),
        val videoThumbnails: List<Thumbnail>? = emptyList(),
        val lengthSeconds: Int? = 0
    ) {
        suspend fun toLoadResponse(provider: InvidiousProvider): LoadResponse? {
            val id = videoId ?: return null
            val titleText = title ?: "Sin título"

            return provider.newMovieLoadResponse(
                titleText,
                "${provider.mainUrl}/watch?v=$id",
                TvType.Movie,
                id
            ) {
                plot = description ?: ""
                posterUrl = videoThumbnails?.find { it.quality == "hqdefault" }?.url
                    ?: "${provider.mainUrl}/vi/$id/hqdefault.jpg"
                recommendations = recommendedVideos?.mapNotNull { it.toSearchResponse(provider) } ?: emptyList()
                duration = lengthSeconds

                author?.let { authorName ->
                    val actor = Actor(
                        authorName,
                        authorThumbnails?.lastOrNull()?.url ?: ""
                    )
                    actors = listOf(
                        ActorData(actor, roleString = "Autor")
                    )
                }
            }
        }
    }

    private data class Thumbnail(
        val url: String? = null,
        val quality: String? = null,
        val width: Int? = null,
        val height: Int? = null
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Usar múltiples métodos de extracción para mayor compatibilidad
        val urls = listOf(
            "https://youtube.com/watch?v=$data",
            "https://youtu.be/$data",
            "https://www.youtube.com/watch?v=$data"
        )

        for (url in urls) {
            try {
                loadExtractor(url, subtitleCallback, callback)
                return true
            } catch (e: Exception) {
                continue
            }
        }
        return false
    }
}