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
            ),
            timeout = 45
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
            if (pageCount > 100) break  // límite muy alto por seguridad
        }

        // Orden como el sitio: más reciente primero (quitamos .reversed())
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, allEpisodes.distinctBy { it.data }) {
            this.posterUrl = poster
            this.plot = doc.selectFirst("meta[name=description]")?.attr("content") ?: ""
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = getDoc(data)
        var found = false

        // 1. Iframes principales
        document.select("iframe[src]").forEach { iframe ->
            val src = fixUrl(iframe.attr("abs:src")) ?: return@forEach
            try {
                val iframeRes = app.get(
                    src,
                    headers = mapOf(
                        "Referer" to data,
                        "User-Agent" to chromeUA,
                        "Origin" to "https://novelas360.cyou",
                        "Accept" to "*/*"
                    ),
                    timeout = 30
                )

                val iframeHtml = iframeRes.text
                val cookies = iframeRes.cookies.toString()

                // Regex fuerte para m3u8/mp4 directo
                Regex("""(https?://[^\s"'\\<>]+?\.(?:m3u8|mp4|ts|avi|mkv)[^\s"'\\<>]*?)["'\\]""").findAll(iframeHtml).forEach { match ->
                    var videoUrl = match.groupValues[1].replace("\\/", "/")
                    callback.invoke(
                        newExtractorLink("Servidor Directo", "Servidor Directo", videoUrl) {
                            this.referer = src
                            this.quality = Qualities.Unknown.value
                            this.type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            this.headers = mapOf(
                                "User-Agent" to chromeUA,
                                "Referer" to src,
                                "Origin" to "https://novelas360.cyou",
                                "Cookie" to cookies
                            )
                        }
                    )
                    found = true
                }

                // Extractor nativo
                if (loadExtractor(src, data, subtitleCallback, callback)) {
                    found = true
                }

                // Si quieres unpack (descomenta si ves eval en algún iframe)
                /*
                if (iframeHtml.contains("eval(function(p,a,c,k,e")) {
                    try {
                        val unpacker = JsUnpacker(iframeHtml)
                        if (unpacker.detect()) {
                            val unpacked = unpacker.unpack()
                            unpacked?.let { unpackedText ->
                                Regex("""(https?://[^\s"'<>]+\.(?:m3u8|mp4)[^\s"'<>]*)""").findAll(unpackedText).forEach { m ->
                                    val videoUrl = m.groupValues[1]
                                    callback.invoke(
                                        newExtractorLink("Unpacked", "Unpacked", videoUrl) {
                                            this.referer = src
                                            this.quality = Qualities.Unknown.value
                                        }
                                    )
                                    found = true
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
                */

            } catch (e: Exception) {
                // println("Error iframe $src: ${e.message}")
            }
        }

        // Fuentes directas en la página (por si no hay iframe)
        val pageText = document.outerHtml()
        Regex("""(https?://[^\s"'<>]+\.(?:m3u8|mp4)[^\s"'<>]*)""").findAll(pageText).forEach { m ->
            val videoUrl = m.groupValues[1]
            callback.invoke(
                newExtractorLink("Directo Página", "Directo Página", videoUrl) {
                    this.referer = data
                    this.quality = Qualities.Unknown.value
                    this.type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                }
            )
            found = true
        }

        // Si no encontró nada, agregamos nota en plot (opcional)
        if (!found) {
            // Puedes comentar esto si no quieres
            // println("No se encontraron enlaces en $data")
        }

        return found
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("href")
        if (href.isBlank()) return null
        val title = selectFirst("span.tabcontentnom")?.text()?.trim() ?: return null
        val img = selectFirst("img")
        val poster = fixUrl(img?.attr("data-src")?.ifBlank { img.attr("src") })
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }
}
