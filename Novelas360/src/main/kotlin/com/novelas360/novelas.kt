package com.novelas360

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Novelas : MainAPI() {
    override var mainUrl = "https://novelas360.com"
    override var name = "Novelas360"
    override val hasMainPage = true
    override var lang = "es"
    override val supportedTypes = setOf(TvType.TvSeries)

    private val chromeUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

    private suspend fun getDoc(url: String): Document {
        return app.get(
            url,
            headers = mapOf(
                "User-Agent" to chromeUA,
                "Referer" to mainUrl,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Accept-Language" to "es-MX,es;q=0.9"
            )
        ).document
    }

    private fun fixUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return if (url.startsWith("//")) "https:$url" else url
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = getDoc("$mainUrl/telenovelas/mexico/")
        val items = document.select("div.tabcontent#Todos > a, div.item a").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(listOf(HomePageList("Telenovelas México", items)), false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = getDoc("$mainUrl/?s=$query")
        return document.select(".video-item, div.item").mapNotNull { item ->
            val link = item.selectFirst("a") ?: return@mapNotNull null
            val title = item.selectFirst("h3, .tabcontentnom")?.text() ?: return@mapNotNull null
            val img = item.selectFirst("img")
            val poster = fixUrl(img?.attr("data-src")?.ifBlank { img.attr("src") })
            newTvSeriesSearchResponse(title, link.attr("href"), TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = getDoc(url)
        val title = doc.selectFirst("h4 span, h1")?.text() ?: "Novela"
        val poster = fixUrl(doc.selectFirst("meta[property=og:image]")?.attr("content"))

        val allEpisodes = mutableListOf<Episode>()
        var pageCount = 1

        while (true) {
            val currentUrl = if (pageCount == 1) url else "${url.trimEnd('/')}/page/$pageCount/"
            val pageDoc = try { getDoc(currentUrl) } catch (e: Exception) { break }
            val items = pageDoc.select("div.item h3 a, .video-item h3 a")
            if (items.isEmpty()) break

            items.forEach { el ->
                allEpisodes.add(newEpisode(el.attr("href")) {
                    name = el.text().trim()
                })
            }
            pageCount++
            if (pageCount > 30) break
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, allEpisodes.distinctBy { it.data }.reversed()) {
            this.posterUrl = poster
            this.plot = doc.selectFirst("meta[name=description]")?.attr("content")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = getDoc(data)
        var success = false

        // Procesar iframes con UniversalExtractor
        document.select("iframe[src]").forEach { iframe ->
            val src = fixUrl(iframe.attr("abs:src")) ?: return@forEach
            try {
                if (UniversalExtractor.resolve(src, data, subtitleCallback, callback)) {
                    success = true
                }
            } catch (_: Exception) {}
        }

        // Fuentes directas en la página principal
        val pageText = document.outerHtml()
        Regex("""(https?://[^\s"'<>]+\.(?:m3u8|mp4|mkv)[^\s"'<>]*)""").findAll(pageText).forEach { m ->
            val videoUrl = m.groupValues[1]
            callback(
                newExtractorLink("Directo", "Directo", videoUrl) {
                    this.referer = data
                    this.quality = Qualities.Unknown.value
                    this.type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                }
            )
            success = true
        }

        return success
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("href")
        if (href.isBlank() || href.contains("/tag/")) return null
        val title = selectFirst("span.tabcontentnom, h3, h2")?.text()?.trim() ?: return null
        val img = selectFirst("img")
        val poster = fixUrl(img?.attr("data-src")?.ifBlank { img.attr("src") })
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }
}
