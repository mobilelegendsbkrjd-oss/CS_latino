package com.verpeliculasonline.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

class OpuxaExtractor : ExtractorApi() {

    override val name = "Opuxa"
    override val mainUrl = "https://opuxa.lat"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val realReferer = referer ?: url

        val headers = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "es-419,es;q=0.9,en;q=0.8",
            "DNT" to "1",
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1",
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "cross-site",
            "Pragma" to "no-cache",
            "Cache-Control" to "no-cache"
        )

        try {
            val doc = app.get(
                url,
                referer = realReferer,
                headers = headers
            ).document

            val iframe = doc.selectFirst("iframe[src]")

            if (iframe != null) {
                var iframeUrl = iframe.attr("abs:src").ifBlank { iframe.attr("src") }

                if (iframeUrl.startsWith("//")) iframeUrl = "https:$iframeUrl"
                if (iframeUrl.startsWith("/")) iframeUrl = "$mainUrl$iframeUrl"

                if (!iframeUrl.contains("http_referer")) {
                    iframeUrl += if (iframeUrl.contains("?")) {
                        "&http_referer=${realReferer.encodeURL()}"
                    } else {
                        "?http_referer=${realReferer.encodeURL()}"
                    }
                }

                if (!iframeUrl.contains("autoplay")) {
                    iframeUrl += if (iframeUrl.contains("?")) "&autoplay=yes" else "?autoplay=yes"
                }

                loadExtractor(
                    fixHostsLinks(iframeUrl),
                    realReferer,
                    subtitleCallback,
                    callback
                )
            }

            doc.select("script").forEach { script ->
                val scriptContent = script.html()

                val patterns = listOf(
                    """src\s*[:=]\s*["']([^"']+)["']""".toRegex(),
                    """file\s*[:=]\s*["']([^"']+)["']""".toRegex(),
                    """video_url\s*[:=]\s*["']([^"']+)["']""".toRegex(),
                    """["'](https?://[^"'\s]+?\.(?:m3u8|mp4|mkv|avi)(?:\?[^"'\s]*)?)["']""".toRegex(),
                    """["'](https?://[^"']+?/embed/[^"']+)["']""".toRegex()
                )

                patterns.forEach { pattern ->
                    pattern.findAll(scriptContent).forEach { match ->
                        val foundUrl = match.groups[1]?.value
                            ?.trim('\'', '"', ' ')
                            ?: return@forEach

                        if (foundUrl.startsWith("http")) {
                            val fixed = fixHostsLinks(foundUrl)

                            if (
                                fixed.contains(".m3u8") ||
                                fixed.contains(".mp4") ||
                                fixed.contains(".mkv") ||
                                fixed.contains(".avi")
                            ) {
                                callback.invoke(
                                    newExtractorLink(
                                        name,
                                        name,
                                        fixed,
                                        INFER_TYPE
                                    ) {
                                        this.referer = realReferer
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                            } else {
                                loadExtractor(
                                    fixed,
                                    realReferer,
                                    subtitleCallback,
                                    callback
                                )
                            }
                        }
                    }
                }
            }

        } catch (e: Exception) {
            println("Opuxa extractor error: ${e.message}")
        }
    }

    private fun String.encodeURL(): String {
        return URLEncoder.encode(this, "UTF-8")
    }

    private fun fixHostsLinks(url: String): String {
        return url
            .replace("https://hglink.to", "https://streamwish.to")
            .replace("https://swdyu.com", "https://streamwish.to")
            .replace("https://cybervynx.com", "https://streamwish.to")
            .replace("https://dumbalag.com", "https://streamwish.to")
            .replace("https://mivalyo.com", "https://vidhidepro.com")
            .replace("https://dinisglows.com", "https://vidhidepro.com")
            .replace("https://dhtpre.com", "https://vidhidepro.com")
            .replace("https://filemoon.link", "https://filemoon.sx")
            .replace("https://sblona.com", "https://watchsb.com")
            .replace("https://lulu.st", "https://lulustream.com")
            .replace("https://uqload.io", "https://uqload.com")
            .replace("https://do7go.com", "https://dood.la")
            .replace("https://dooood.com", "https://dood.la")
            .replace("https://dood.so", "https://dood.la")
            .replace("https://dood.ws", "https://dood.la")
            .replace("https://dood.to", "https://dood.la")
    }
}