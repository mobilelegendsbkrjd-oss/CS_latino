package com.gnulahd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Element

class GnulaHD : MainAPI() {
    override var mainUrl = "https://ww3.gnulahd.nu"
    override var name = "GnulaHD"
    override var lang = "es"
    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    override val mainPage = mainPageOf(
        "$mainUrl/ver/?type=Pelicula&order=latest" to "Últimas Películas",
        "$mainUrl/ver/?type=Serie&order=latest" to "Últimas Series",
        "$mainUrl/ver/?type=Anime&order=latest" to "Últimos Animes"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data, headers = headers()).document
        val items = doc.select("a.gnrd-card").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.encodeUrl()}"
        val doc = app.get(url, headers = headers()).document
        return doc.select("a.gnrd-card").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = headers()).document
        val title = doc.selectFirst("h1.gnrd-fi-title")?.text()?.trim() ?: return null
        val poster = doc.selectFirst("meta[itemprop=\"image\"]")?.attr("content")
        val description = doc.selectFirst("p.gnrd-fi-syn")?.text()

        val episodes = doc.select("a.gnrd-epc").mapNotNull { ep ->
            val epUrl = ep.attr("href")
            if (epUrl.isBlank()) return@mapNotNull null
            val epNumber = ep.selectFirst("span.gnrd-epc-n")?.text() ?: ""
            val epTitle = ep.selectFirst("span.gnrd-epc-title")?.text() ?: "Episodio"
            val epImage = ep.selectFirst(".gnrd-epc-thumb")?.attr("style")
                ?.let { Regex("""url\(['"]?(.*?)['"]?\)""").find(it)?.groupValues?.get(1) }
            val fullTitle = if (epNumber.isNotEmpty()) "$epNumber - $epTitle" else epTitle
            var season = 1
            var episode = 0
            val match = Regex("""(\d+)x(\d+)""").find(epNumber)
            if (match != null) {
                season = match.groupValues[1].toIntOrNull() ?: 1
                episode = match.groupValues[2].toIntOrNull() ?: 0
            }
            newEpisode(epUrl) {
                this.name = fullTitle
                this.season = season
                this.episode = if (episode > 0) episode else null
                this.posterUrl = epImage
            }
        }
        val suggestions = doc.select(".gnrd-similares .gnrd-card").mapNotNull { it.toSearchResult() }

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.recommendations = suggestions
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.recommendations = suggestions
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(data, headers = headers()).text
        var anyLink = false

        val regex = Regex("""var\s+(_gnpv_ep_langs|_gd)\s*=\s*(\[[\s\S]*?\])\s*;""")
        val match = regex.find(html) ?: return false

        val jsonStr = match.groupValues[2].replace("\\/", "/")

        try {
            val langs = JSONArray(jsonStr)
            for (i in 0 until langs.length()) {
                val langObj = langs.getJSONObject(i)
                val langLabel = langObj.optString("label", "Audio")
                val servers = langObj.getJSONArray("servers")

                for (j in 0 until servers.length()) {
                    val server = servers.getJSONObject(j)
                    var src = server.optString("src", "")
                    if (src.isBlank()) continue

                    src = src.replace("\\/", "/")
                    if (src.startsWith("//")) src = "https:$src"

                    try {
                        if (src.contains("they.tube")) {
                            val code = Regex("""the(?:y)?\.tube/(?:e/)?([A-Za-z0-9_-]+)""")
                                .find(src)?.groupValues?.get(1) ?: continue
                            val token = "eece56848d3929ee78f5fe8e3a62de2d"
                            val resolveUrl = "$mainUrl/panel/the-tube-resolve.php?code=$code&t=$token"
                            val resp = app.get(resolveUrl, headers = headers())
                            if (resp.isSuccessful) {
                                val master = JSONObject(resp.text).optString("master", "")
                                if (master.isNotEmpty()) {
                                    loadExtractor(
                                        fixUrl(master),
                                        data,
                                        subtitleCallback = subtitleCallback,
                                        callback = { link ->
                                            callback.invoke(
                                                ExtractorLink(
                                                    source = "${link.source ?: ""} - $langLabel",
                                                    name = "${link.name ?: ""} - $langLabel",
                                                    url = link.url,
                                                    referer = link.referer,
                                                    quality = link.quality,
                                                    type = link.type,
                                                    headers = link.headers
                                                )
                                            )
                                        }
                                    )
                                    anyLink = true
                                }
                            }
                        } else {
                            loadExtractor(
                                fixUrl(src),
                                data,
                                subtitleCallback = subtitleCallback,
                                callback = { link ->
                                    callback.invoke(
                                        ExtractorLink(
                                            source = "${link.source ?: ""} - $langLabel",
                                            name = "${link.name ?: ""} - $langLabel",
                                            url = link.url,
                                            referer = link.referer,
                                            quality = link.quality,
                                            type = link.type,
                                            headers = link.headers
                                        )
                                    )
                                }
                            )
                            anyLink = true
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return anyLink
    }

    private fun headers() = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "es-ES,es;q=0.9,en;q=0.8",
        "Referer" to mainUrl
    )

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.attr("href")
        if (link.isBlank()) return null
        val title = selectFirst("span.gnrd-card-title")?.text()?.trim() ?: return null
        val poster = selectFirst(".gnrd-card-art img")?.attr("src")
        val type = when {
            selectFirst("span.gnrd-type-badge")?.text()?.contains("Serie", true) == true -> TvType.TvSeries
            selectFirst("span.gnrd-type-badge")?.text()?.contains("Anime", true) == true -> TvType.Anime
            link.contains("pelicula") -> TvType.Movie
            link.contains("serie") -> TvType.TvSeries
            link.contains("anime") -> TvType.Anime
            else -> TvType.Movie
        }
        return newTvSeriesSearchResponse(title, fixUrl(link), type) {
            this.posterUrl = poster
        }
    }

    private fun String.encodeUrl(): String = java.net.URLEncoder.encode(this, "UTF-8")
}