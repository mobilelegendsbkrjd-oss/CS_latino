package com.locopelis

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URI

class LocoPelis : MainAPI() {
    override var mainUrl = "https://www.locopelis.com"
    override var name = "LocoPelis"
    override val hasMainPage = true
    override var lang = "es"
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Inicio",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data == "$mainUrl/") {
            if (page <= 1) "$mainUrl/" else "$mainUrl/page/$page/"
        } else {
            "${request.data}?page=$page"
        }
        val document = app.get(url).document
        val home = document.select("ul.peliculas li.peli_bx").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h3.titpeli")?.text()?.trim() ?: return null
        val href = this.selectFirst("div.peli_img_img a")?.attr("href") ?: return null
        val poster = this.selectFirst("div.peli_img_img a img")?.attr("src")
        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/buscar/?q=$query").document
        return document.select("ul.peliculas li.peli_bx").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.movie-title")?.text() ?: "Sin título"
        val poster = document.selectFirst("div.movie-poster img")?.attr("src")
        val plot = document.selectFirst("div.movie-synopsis")?.text()
        val year = document.selectFirst("span.pill-year")?.text()?.toIntOrNull()
        val tags = document.select("a.movie-pill.pill-genre").map { it.text() }
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val iframes = document.select("div.tab_content iframe")

        val byseHosts = listOf(
            "byse.sx", "byseqekaho.com", "bysesukior.com", "bysejikuar.com",
            "bysezoxexe.com", "bysezejataos.com", "bysebuho.com", "bysevepoin.com",
            "f16px.com", "bysesayeveum.com", "bysetayico.com", "bysefujedu.com",
            "bysedikamoum.com", "bysewihe.com", "byselapuix.com", "embedplaybyse.top"
        )

        var found = false

        iframes.forEach { iframe ->
            val src = iframe.attr("data-src").ifBlank { iframe.attr("src") }
            if (src.isBlank() || src == "about:blank") return@forEach

            if (src.contains("locopelis.com/player")) {
                val hParam = src.substringAfter("h=", "").substringBefore("&")
                if (hParam.isNotBlank()) {
                    try {
                        val headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to data,
                            "Origin" to mainUrl,
                            "X-Requested-With" to "XMLHttpRequest"
                        )
                        val response = app.post(
                            "$mainUrl/player/api.php",
                            data = mapOf("h" to hParam),
                            headers = headers
                        )

                        val json = JSONObject(response.text)
                        val realIframeUrl = json.optString("url", "")

                        if (realIframeUrl.startsWith("http")) {
                            val host = try { URI(realIframeUrl).host ?: "" } catch (e: Exception) { "" }

                            if (byseHosts.any { host.contains(it, ignoreCase = true) }) {
                                ByseExtractor().getUrl(realIframeUrl, referer = data, subtitleCallback, callback)
                            } else {
                                loadExtractor(realIframeUrl, data, subtitleCallback, callback)
                            }
                            found = true
                        }
                    } catch (e: Exception) {
                        // fallo silencioso
                    }
                }
            } else {
                loadExtractor(src, data, subtitleCallback, callback)
                found = true
            }
        }

        return found
    }
}