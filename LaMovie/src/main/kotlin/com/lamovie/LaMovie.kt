package com.lamovie

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder

class LaMovie : MainAPI() {
    override var mainUrl = "https://lamovie.org"
    override var name = "La.Movie"
    override val hasMainPage = true
    override var lang = "es"
    override val hasDownloadSupport = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val fastApi = "$mainUrl/wp-api/v1"

    private val imageHeaders = mapOf(
        "User-Agent" to UA,
        "Referer" to "$mainUrl/",
        "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"
    )

    override val mainPage = mainPageOf(
        "movies" to "🎬 Películas",
        "tvshows" to "📺 Series",
        "animes" to "🍥 Animes",
        "novels" to "🌹 Novelas"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val list = fetchListing(request.data, page)
        return newHomePageResponse(listOf(HomePageList(request.name, list)), list.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val all = mutableListOf<SearchResponse>()

        listOf("movies", "tvshows", "animes", "novels").forEach {
            all.addAll(fetchListing(it, 1, q))
        }

        return all.distinctBy { it.url }
    }

    private suspend fun fetchListing(postType: String, page: Int, search: String? = null): List<SearchResponse> {
        return try {
            val url = buildString {
                append("$fastApi/listing/$postType")
                append("?filter=[]")
                append("&page=$page")
                append("&orderBy=latest")
                append("&order=DESC")
                append("&postType=$postType")
                append("&postsPerPage=24")
                if (!search.isNullOrBlank()) append("&search=$search")
            }

            val raw = app.get(url, referer = mainUrl, headers = baseHeaders()).text
            val json = JSONObject(raw)
            val data = json.optJSONObject("data") ?: json

            val posts = data.optJSONArray("posts")
                ?: data.optJSONArray("items")
                ?: data.optJSONArray("results")
                ?: json.optJSONArray("posts")
                ?: json.optJSONArray("items")
                ?: JSONArray()

            val type = when (postType) {
                "movies" -> TvType.Movie
                "animes" -> TvType.Anime
                else -> TvType.TvSeries
            }

            val out = mutableListOf<SearchResponse>()

            for (i in 0 until posts.length()) {
                val item = posts.optJSONObject(i) ?: continue

                val title = cleanTitle(
                    item.optString("title")
                        .ifBlank { item.optString("post_title") }
                        .ifBlank { item.optString("name") }
                        .ifBlank { item.optString("title_rendered") }
                )

                if (title.isBlank()) continue

                val slug = item.optString("slug")
                    .ifBlank { item.optString("post_name") }
                    .ifBlank { item.optString("permalink").substringAfterLast("/").trim('/') }

                val link = item.optString("url")
                    .ifBlank { item.optString("link") }
                    .ifBlank { item.optString("permalink") }
                    .ifBlank { if (slug.isNotBlank()) buildPostUrl(postType, slug) else "" }

                if (link.isBlank()) continue

                val poster = extractPosterFromJson(item)

                out.add(
                    if (type == TvType.Movie) {
                        newMovieSearchResponse(title, fixUrl(link), type) {
                            posterUrl = poster
                            posterHeaders = imageHeaders
                        }
                    } else {
                        newTvSeriesSearchResponse(title, fixUrl(link), type) {
                            posterUrl = poster
                            posterHeaders = imageHeaders
                        }
                    }
                )
            }

            out.distinctBy { it.url }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun buildPostUrl(postType: String, slug: String): String {
        return when (postType) {
            "movies" -> "$mainUrl/peliculas/$slug/"
            "tvshows" -> "$mainUrl/series/$slug/"
            "animes" -> "$mainUrl/animes/$slug/"
            "novels" -> "$mainUrl/novelas/$slug/"
            else -> "$mainUrl/$slug/"
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, referer = mainUrl, headers = pageHeaders()).document
        val html = doc.html()
        val postId = extractPostId(html, url)

        val title = cleanTitle(
            doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore("|")
                ?: doc.selectFirst("h1, .full-info-8 h1, .entry-title")?.text()
                ?: "Sin título"
        )

        val poster = extractImage(html)
            ?: doc.selectFirst(".movies-full__poster img, img[src*='/wp-content/uploads/thumbs/']")?.attr("abs:src")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst("meta[name=description]")?.attr("content")

        val year = Regex("""\((\d{4})\)""").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""Fecha de Estreno[\s\S]{0,80}(\d{4})""").find(html)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val isMovie = url.contains("/peliculas/", true) || url.contains("/movies/", true)
        val isAnime = url.contains("/animes/", true)

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, postId.ifBlank { url }) {
                posterUrl = poster
                posterHeaders = imageHeaders
                backgroundPosterUrl = poster
                this.plot = plot
                this.year = year
            }
        }

        val episodes = if (postId.isNotBlank()) fetchEpisodes(postId, poster) else emptyList()

        return newTvSeriesLoadResponse(
            title,
            url,
            if (isAnime) TvType.Anime else TvType.TvSeries,
            episodes
        ) {
            posterUrl = poster
            posterHeaders = imageHeaders
            backgroundPosterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    private suspend fun fetchEpisodes(serieId: String, fallbackPoster: String?): List<Episode> {
        val out = mutableListOf<Episode>()

        suspend fun readSeason(seasonNumber: Int) {
            val apiUrl = "$fastApi/single/episodes/list?_id=$serieId&season=$seasonNumber&page=1&postsPerPage=300"
            val raw = app.get(apiUrl, referer = mainUrl, headers = baseHeaders()).text
            val json = JSONObject(raw)
            val data = json.optJSONObject("data") ?: json

            val arrays = listOfNotNull(
                data.optJSONArray("posts"),
                data.optJSONArray("episodes"),
                data.optJSONArray("items"),
                data.optJSONArray("results"),
                data.optJSONArray("seasons"),
                json.optJSONArray("posts"),
                json.optJSONArray("episodes"),
                json.optJSONArray("items"),
                json.optJSONArray("results"),
                json.optJSONArray("seasons")
            )

            arrays.forEach { parseEpisodeArray(it, seasonNumber, serieId, fallbackPoster, out) }
        }

        for (s in 1..30) {
            val before = out.size
            runCatching { readSeason(s) }
            if (out.size == before && out.isNotEmpty()) break
        }

        if (out.isEmpty()) {
            runCatching {
                val raw = app.get(
                    "$fastApi/single/episodes/list?_id=$serieId&page=1&postsPerPage=500",
                    referer = mainUrl,
                    headers = baseHeaders()
                ).text

                val json = JSONObject(raw)
                val data = json.optJSONObject("data") ?: json

                listOfNotNull(
                    data.optJSONArray("posts"),
                    data.optJSONArray("episodes"),
                    data.optJSONArray("items"),
                    data.optJSONArray("results"),
                    data.optJSONArray("seasons")
                ).forEach { parseEpisodeArray(it, 1, serieId, fallbackPoster, out) }
            }
        }

        return out.distinctBy { it.data }
            .sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 1 }))
    }

    private fun parseEpisodeArray(
        array: JSONArray,
        defaultSeason: Int,
        parentId: String,
        fallbackPoster: String?,
        out: MutableList<Episode>
    ) {
        for (i in 0 until array.length()) {
            val ep = array.optJSONObject(i) ?: continue

            val seasonNumber = ep.optString("season").toIntOrNull()
                ?: ep.optString("season_number").toIntOrNull()
                ?: ep.optString("number").toIntOrNull()
                ?: defaultSeason

            val nested = ep.optJSONArray("episodes")
                ?: ep.optJSONArray("items")
                ?: ep.optJSONArray("posts")
                ?: ep.optJSONArray("children")

            if (nested != null && nested.length() > 0) {
                parseEpisodeArray(nested, seasonNumber, parentId, fallbackPoster, out)
                continue
            }

            val epId = ep.optString("id")
                .ifBlank { ep.optString("ID") }
                .ifBlank { ep.optString("_id") }
                .ifBlank { ep.optString("postId") }
                .ifBlank { ep.optString("post_id") }

            if (epId.isBlank()) continue

            val epTitle = cleanTitle(
                ep.optString("title")
                    .ifBlank { ep.optString("post_title") }
                    .ifBlank { ep.optString("name") }
                    .ifBlank { "Episodio ${out.size + 1}" }
            )

            val slug = ep.optString("slug")
                .ifBlank { ep.optString("post_name") }

            val epUrl = ep.optString("url")
                .ifBlank { ep.optString("link") }
                .ifBlank { ep.optString("permalink") }
                .ifBlank { if (slug.isNotBlank()) "$mainUrl/episodio/$slug/" else "" }

            val epNum = ep.optString("episode_number").toIntOrNull()
                ?: ep.optString("episode").toIntOrNull()
                ?: ep.optString("episode_num").toIntOrNull()
                ?: ep.optString("number").toIntOrNull()
                ?: Regex("""(?:Episodio|Capitulo|Capítulo|E)\s*(\d+)""", RegexOption.IGNORE_CASE)
                    .find(epTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""(\d+)""").find(epTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: (out.count { it.season == seasonNumber } + 1)

            val epData = JSONObject()
                .put("id", epId)
                .put("url", epUrl)
                .put("parent", parentId)
                .toString()

            out.add(newEpisode(epData) {
                name = epTitle
                season = seasonNumber
                episode = epNum
                posterUrl = extractEpisodeImage(ep)
            })
        }
    }

    private fun extractEpisodeImage(ep: JSONObject): String? {
        val raw = ep.toString()
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")

        return Regex("""https?://image\.tmdb\.org/[^"'<>\s]+\.(?:webp|jpg|jpeg|png)""", RegexOption.IGNORE_CASE)
            .find(raw)?.value
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        suspend fun handle(server: String, referer: String): Boolean {
            val fixed = fixHosts(fixUrl(server, referer))
            if (fixed.isBlank()) return false

            return try {
                when {
                    fixed.contains("vimeos.net", true) -> {
                        Vimeos().getUrl(fixed, mainUrl, subtitleCallback, callback)
                        true
                    }

                    fixed.contains("goodstream", true) -> {
                        GoodstreamExtractor().getUrl(fixed, mainUrl, subtitleCallback, callback)
                        true
                    }

                    else -> loadExtractor(fixed, mainUrl, subtitleCallback, callback)
                }
            } catch (_: Exception) {
                false
            }
        }

        val ids = mutableListOf<String>()
        val urls = mutableListOf<String>()

        if (data.trim().startsWith("{")) {
            val obj = runCatching { JSONObject(data) }.getOrNull()
            obj?.optString("id")?.takeIf { it.isNotBlank() }?.let { ids.add(it) }
            obj?.optString("url")?.takeIf { it.isNotBlank() }?.let { urls.add(it) }
            obj?.optString("parent")?.takeIf { it.isNotBlank() }?.let { ids.add(it) }
        } else if (data.startsWith("http", true)) {
            urls.add(data)
        } else {
            ids.add(data)
        }

        urls.distinct().forEach { pageUrl ->
            runCatching {
                val html = app.get(pageUrl, referer = mainUrl, headers = pageHeaders()).text
                val cleanHtml = cleanUrl(html)

                extractPostId(html, pageUrl).takeIf { it.isNotBlank() }?.let { ids.add(0, it) }

                extractUrlsFromText(cleanHtml).forEach { server ->
                    if (isUsefulServer(server)) {
                        if (handle(server, pageUrl)) found = true
                    }
                }
            }
        }

        for (id in ids.distinct().filter { it.isNotBlank() }) {
            val playerUrls = listOf(
                "$fastApi/player?postId=$id&demo=0",
                "$fastApi/player?post_id=$id&demo=0",
                "$fastApi/player?id=$id&demo=0",
                "$fastApi/player?_id=$id&demo=0",
                "$fastApi/single/player?postId=$id&demo=0",
                "$fastApi/single/player?post_id=$id&demo=0",
                "$fastApi/single/player?id=$id&demo=0",
                "$fastApi/single/player?_id=$id&demo=0"
            )

            for (playerUrl in playerUrls) {
                runCatching {
                    val raw = app.get(playerUrl, referer = mainUrl, headers = baseHeaders()).text
                        .replace("\\/", "/")
                        .replace("\\u0026", "&")
                        .replace("&amp;", "&")

                    val servers = mutableListOf<String>()

                    val json = runCatching { JSONObject(raw) }.getOrNull()
                    if (json != null) collectUrlsFromJson(json, servers)

                    extractUrlsFromText(raw).forEach { servers.add(it) }

                    val sorted = servers.distinct().sortedBy { priorityIndex(it) }

                    for (server in sorted) {
                        if (isUsefulServer(server)) {
                            if (handle(server, mainUrl)) found = true
                        }
                    }
                }

                if (found) return true
            }
        }

        return found
    }

    private fun collectUrlsFromJson(any: Any?, out: MutableList<String>) {
        when (any) {
            is JSONObject -> {
                any.keys().forEach { key ->
                    val value = any.opt(key)
                    if (value is String && value.startsWith("http", true)) out.add(value)
                    collectUrlsFromJson(value, out)
                }
            }

            is JSONArray -> {
                for (i in 0 until any.length()) {
                    collectUrlsFromJson(any.opt(i), out)
                }
            }
        }
    }

    private fun extractUrlsFromText(raw: String): List<String> {
        return Regex("""https?://[^"'\s<>\\]+""")
            .findAll(raw)
            .map { it.value.trim() }
            .toList()
    }

    private fun isUsefulServer(url: String): Boolean {
        return url.contains("vimeos", true) ||
                url.contains("goodstream", true) ||
                url.contains("voe", true) ||
                url.contains("streamwish", true) ||
                url.contains("filemoon", true) ||
                url.contains("dood", true) ||
                url.contains("vidhide", true) ||
                url.contains("uqload", true) ||
                url.contains("mixdrop", true) ||
                url.contains(".m3u8", true)
    }

    private fun priorityIndex(url: String): Int {
        val priority = listOf("vimeos", "goodstream", "voe", "streamwish", "filemoon", "dood")
        val i = priority.indexOfFirst { url.contains(it, true) }
        return if (i == -1) 99 else i
    }

    private fun extractPostId(html: String, url: String): String {
        return Regex("""<link[^>]+rel=["']shortlink["'][^>]+href=["'][^"']+\?p=(\d+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)
            ?: Regex("""wp-api/v1/hit[^"']*_id=(\d+)""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1)
            ?: Regex("""["']?_id["']?\s*[:=]\s*["']?(\d+)""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1)
            ?: url.substringAfter("?p=", "").substringBefore("&").takeIf { it.all(Char::isDigit) }
            ?: ""
    }

    private fun extractPosterFromJson(item: JSONObject): String? {
        val images = item.optJSONObject("images")

        val poster = images?.optString("poster")?.takeIf { it.isNotBlank() }
            ?: item.optString("poster").takeIf { it.isNotBlank() }
            ?: item.optString("image").takeIf { it.isNotBlank() }
            ?: item.optString("thumbnail").takeIf { it.isNotBlank() }
            ?: extractImage(item.toString())

        return normalizeImageUrl(poster)
    }

    private fun extractImage(rawInput: String): String? {
        val raw = rawInput
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")

        val found = Regex("""https?://[^"'<>\s]+/wp-content/uploads/thumbs/[^"'<>\s]+\.(?:webp|jpg|jpeg|png)""", RegexOption.IGNORE_CASE)
            .find(raw)?.value
            ?: Regex("""https?://[^"'<>\s]+/wp-content/uploads/[^"'<>\s]+\.(?:webp|jpg|jpeg|png)""", RegexOption.IGNORE_CASE)
                .find(raw)?.value
            ?: Regex("""https?://image\.tmdb\.org/[^"'<>\s]+\.(?:webp|jpg|jpeg|png)""", RegexOption.IGNORE_CASE)
                .find(raw)?.value
            ?: Regex("""/(?:thumbs|backdrops|logos)/[^"'<>\s]+\.(?:webp|jpg|jpeg|png)""", RegexOption.IGNORE_CASE)
                .find(raw)?.value

        return normalizeImageUrl(found)
    }

    private fun normalizeImageUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        if (raw.startsWith("data:", true)) return null

        val clean = raw
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .trim('"', '\'', ' ', '\n', '\r', '\t')

        val realUrl = when {
            clean.startsWith("http", true) -> clean
            clean.startsWith("/thumbs/", true) -> "$mainUrl/wp-content/uploads$clean"
            clean.startsWith("/backdrops/", true) -> "$mainUrl/wp-content/uploads$clean"
            clean.startsWith("/logos/", true) -> "$mainUrl/wp-content/uploads$clean"
            clean.startsWith("/wp-content/uploads/", true) -> mainUrl.removeSuffix("/") + clean
            else -> null
        } ?: return null

        return if (realUrl.contains("lamovie.org/wp-content/uploads", true)) {
            "https://images.weserv.nl/?url=" + realUrl
                .removePrefix("https://")
                .removePrefix("http://")
        } else {
            realUrl
        }
    }

    private fun cleanTitle(raw: String): String {
        return Jsoup.parse(raw)
            .text()
            .replace(Regex("""^(Pelicula|Película|Serie|Anime|Novela)\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\(\d{4}\)\s*$"""), "")
            .replace("Online", "", ignoreCase = true)
            .replace("LaMovie", "", ignoreCase = true)
            .replace("|", "")
            .trim()
    }

    private fun cleanUrl(raw: String): String {
        return raw
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .trim('"', '\'', ' ', '\n', '\r', '\t')
    }

    private fun fixUrl(url: String, base: String = mainUrl): String {
        val clean = cleanUrl(url)

        return when {
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("http", true) -> clean
            clean.startsWith("/") -> mainUrl.removeSuffix("/") + clean
            clean.isBlank() -> ""
            else -> base.substringBeforeLast("/") + "/" + clean
        }
    }

    private fun fixHosts(url: String): String {
        return url
            .replace("hglink.to", "streamwish.to")
            .replace("swdyu.com", "streamwish.to")
            .replace("wishembed.com", "streamwish.to")
            .replace("vidhide.com", "vidhidepro.com")
            .replace("filemoon.link", "filemoon.sx")
            .replace("doodstream.com", "dood.la")
    }

    private fun baseHeaders(): Map<String, String> {
        return mapOf(
            "User-Agent" to UA,
            "Referer" to mainUrl,
            "Origin" to mainUrl,
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "es-419,es;q=0.9"
        )
    }

    private fun pageHeaders(): Map<String, String> {
        return mapOf(
            "User-Agent" to UA,
            "Referer" to mainUrl,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "es-419,es;q=0.9"
        )
    }

    companion object {
        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"
    }
}