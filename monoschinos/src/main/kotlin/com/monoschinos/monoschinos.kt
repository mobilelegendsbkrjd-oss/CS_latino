package com.monoschinos

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder
import android.util.Base64
import okhttp3.FormBody
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable

@Serializable
data class VideoDetails(
    val id: Int,
    val code: String,
    val title: String,
    val poster_url: String?,
    val description: String?,
    val embed_frame_url: String?,
    val cache_status: String?
)

@Serializable
data class VideoSource(
    val file: String,
    val type: String,
    val label: String?
)

@Serializable
data class VideoPlayback(
    val sources: List<VideoSource>? = null
)

class MonosChinos : MainAPI() {
    override var mainUrl = "https://monoschinos.net"
    override var name = "MonosChinos"
    override var lang = "es"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val requestHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to mainUrl,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/directorio?tipo=tv&orden=desc&pag=" to "Animes (TV)",
        "$mainUrl/directorio?tipo=pelicula&orden=desc&pag=" to "Películas",
        "$mainUrl/directorio?tipo=ova&orden=desc&pag=" to "OVAs",
        "$mainUrl/directorio?tipo=especial&orden=desc&pag=" to "Especiales",
        // Categorías filtradas por latino
        "$mainUrl/directorio?buscar=latino&tipo=tv&orden=desc&pag=" to "Anime Latino",
        "$mainUrl/directorio?buscar=latino&tipo=pelicula&orden=desc&pag=" to "Películas Latino"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.contains("pag=")) {
            "${request.data}$page"
        } else {
            request.data
        }
        val doc = app.get(url, headers = requestHeaders).document
        val homeList = mutableListOf<HomePageList>()

        // Para las categorías normales y latinas: usamos el mismo selector de lista
        val items = doc.select("div#listanime ul li a[href*='/anime/'], div#listanime ul li a[href*='/ver/']")
            .mapNotNull { el ->
                el.toSearchResult()
            }
            .distinctBy { it.url }

        if (items.isNotEmpty()) {
            homeList.add(HomePageList(request.name, items))
        }

        return HomePageResponse(homeList)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("href").trim()
        val title = when {
            href.contains("/ver/") -> {
                selectFirst("h2")?.text()?.trim() ?: return null
            }
            else -> {
                selectFirst("h3")?.text()?.trim() ?: return null
            }
        }

        val poster = selectFirst("img")?.let { extractPoster(it) }
        val episodeNum = selectFirst(".episode")?.text()?.trim()
        return newTvSeriesSearchResponse(
            if (!episodeNum.isNullOrBlank()) "$title - Episodio $episodeNum" else title,
            fixUrl(href),
            TvType.Anime
        ) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.trim().isEmpty()) return emptyList()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/directorio?buscar=$encoded"
        val doc = app.get(url, headers = requestHeaders).document
        return doc.select("div#listanime ul li a[href*='/anime/']").mapNotNull { el ->
            el.toSearchResult()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse {
        if (url.contains("/ver/")) {
            val seriesSlug = url.substringAfter("/ver/").substringBefore("/")
            val seriesUrl = "$mainUrl/anime/$seriesSlug"
            return load(seriesUrl)
        }
        val doc = app.get(url, headers = requestHeaders).document
        val title = doc.selectFirst("h1.fs-2, h1.fs-3, h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")?.substringBefore(" | ")?.trim()
            ?: "Sin título"
        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: doc.selectFirst("img.lazy[data-src]")?.attr("data-src")
            ?: doc.selectFirst("img[src*='/cdn/img/anime/']")?.attr("src")
            ?: doc.selectFirst("img.aspecto, img[src*='/portada/']")?.let { extractPoster(it) }
        val plot: String = doc.selectFirst("meta[property='og:description']")?.attr("content")
            ?: doc.selectFirst("meta[name='description']")?.attr("content")
            ?: doc.selectFirst("p")?.text()
            ?: ""

        val episodes = mutableListOf<Episode>()

        val typeElement = doc.selectFirst(".badge.text-bg-dark, dd:contains(Tipo) + dd, dl dd:first-child")
        val contentType = typeElement?.text()?.lowercase() ?: ""

        val episodeCount = doc.selectFirst("section.caplist[data-e]")?.attr("data-e")?.toIntOrNull() ?: 0
        val animeSlug = doc.selectFirst("section.caplist[data-u]")?.attr("data-u") ?: ""

        if (contentType.contains("película") || contentType.contains("movie") || episodeCount == 1) {
            episodes.add(newEpisode("$mainUrl/ver/$animeSlug/1") {
                name = "Película Completa"
                season = 1
                episode = 1
                posterUrl = poster
            })
        } else {
            if (episodeCount > 0 && animeSlug.isNotBlank()) {
                for (i in 1..episodeCount) {
                    episodes.add(newEpisode("$mainUrl/ver/$animeSlug/$i") {
                        name = "Episodio $i"
                        season = 1
                        episode = i
                        posterUrl = poster
                    })
                }
            } else {
                doc.select("a[href*='/ver/']").map { it.attr("href") }.distinct().forEach { href ->
                    if (href.isNotBlank()) {
                        val epNumStr = href.substringAfterLast("/").replace(Regex("[^0-9]"), "")
                        val epNum = epNumStr.toIntOrNull() ?: (episodes.size + 1)
                        episodes.add(newEpisode(fixUrl(href)) {
                            name = "Episodio $epNum"
                            season = 1
                            episode = epNum
                            posterUrl = poster
                        })
                    }
                }
            }
        }

        episodes.sortBy { it.episode }
        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        val doc = app.get(data, headers = requestHeaders, timeout = 45).document

        // Obtener data-encrypt del ul.nav-tabs
        val encrypt = doc.selectFirst("ul.nav-tabs.opt")?.attr("data-encrypt") ?: return false

        // POST a ajax_pagination para obtener los servers
        val formBody = FormBody.Builder()
            .add("acc", "opt")
            .add("i", encrypt)
            .build()

        val ajaxResponse = app.post(
            "$mainUrl/ajax_pagination",
            headers = requestHeaders,
            requestBody = formBody,
            timeout = 45
        )

        val serverDoc = ajaxResponse.document

        // Extraer botones con data-player desde la respuesta AJAX
        serverDoc.select("button.play-video[data-player]").forEach { btn ->
            val base64 = btn.attr("data-player").trim()
            if (base64.isNotBlank()) {
                try {
                    val decoded = String(Base64.decode(base64, Base64.DEFAULT), Charsets.UTF_8).trim()
                    if (decoded.startsWith("http")) {
                        loadExtractor(decoded, data, subtitleCallback, callback)
                        found = true
                    }
                } catch (_: Throwable) {}
            }
        }

        // Fallback: iframes en el doc original
        doc.select("div.ifplay iframe[src], iframe[src]").forEach { iframe ->
            val src = iframe.attr("abs:src").trim()
            if (src.isNotBlank()) {
                loadExtractor(src, data, subtitleCallback, callback)
                found = true
            }
        }

        // Fallback: iframes en serverDoc (si se inyectaron)
        serverDoc.select("div.ifplay iframe[src], iframe[src]").forEach { iframe ->
            val src = iframe.attr("abs:src").trim()
            if (src.isNotBlank()) {
                loadExtractor(src, data, subtitleCallback, callback)
                found = true
            }
        }

        // Fallback: enlaces de descargas
        doc.select("a.btn-warning[target='_blank'][href]").forEach { a ->
            val downloadUrl = a.attr("abs:href").trim()
            if (downloadUrl.isNotBlank()) {
                loadExtractor(downloadUrl, data, subtitleCallback, callback)
                found = true
            }
        }

        return found
    }

    private fun extractPoster(imgElement: Element?): String? {
        if (imgElement == null) return null

        var src = imgElement.attr("data-src")
        if (src.isBlank()) src = imgElement.attr("src")

        return if (src.isNotBlank() && !src.contains("anime.png") && !src.contains("episode.png") && !src.contains("placeholder")) {
            if (src.startsWith("http")) src else fixUrl(src)
        } else null
    }

    private inline fun <reified T> parseJson(json: String): T {
        return Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }.decodeFromString<T>(json)
    }
}