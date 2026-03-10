package com.novelas360

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

class ExtractorNovelas360 : ExtractorApi() {

    override val name = "Novelas360 Player"
    override val mainUrl = "https://novelas360.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?
    ): List<ExtractorLink>? {

        val fixedReferer = referer ?: mainUrl

        // 1. Obtener HTML del episodio
        val doc = app.get(url, referer = fixedReferer).document

        // 2. Buscar iframe
        val iframeElement =
            doc.selectFirst("div.player iframe[src*='novelas360.cyou/e/']")
                ?: doc.selectFirst("iframe[src*='novelas360.cyou/e/']")
                ?: doc.selectFirst(".embed-responsive iframe")
                ?: return null

        val iframeUrl = iframeElement.attr("abs:src")

        if (iframeUrl.isBlank() || !iframeUrl.contains("/e/"))
            return null

        val videoKey = iframeUrl.substringAfterLast("/e/")

        // 3. Visita iframe para cookies
        app.get(
            iframeUrl,
            referer = url,
            headers = mapOf(
                "Referer" to url,
                "Origin" to mainUrl,
                "Accept" to "*/*"
            )
        )

        // 4. Headers POST
        val postHeaders = mapOf(
            "Origin" to "https://novelas360.cyou",
            "Referer" to iframeUrl,
            "X-Requested-With" to "XMLHttpRequest",
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "User-Agent" to "Mozilla/5.0"
        )

        val postBody = mapOf(
            "v" to videoKey,
            "secure" to "0",
            "ver" to "4",
            "adb" to "0",
            "wasmcheck" to "0",
            "embed_from" to "0",
            "token" to "",
            "htoken" to "",
            "gt" to "",
            "adscore" to ""
        )

        val response = app.post(
            "https://novelas360.cyou/player/get_md5.php",
            data = postBody,
            headers = postHeaders,
            allowRedirects = true
        )

        val json = response.parsedSafe<Map<String, Any?>>() ?: return null

        val fileUrl = json["file"]?.toString() ?: return null

        if (fileUrl.isBlank())
            return null

        val links = mutableListOf<ExtractorLink>()

        links.add(
            newExtractorLink(
                name,
                "Novelas360 HLS",
                fileUrl
            ) {
                this.referer = iframeUrl
                this.quality = Qualities.Unknown.value
                this.type =
                    if (fileUrl.contains(".m3u8"))
                        ExtractorLinkType.M3U8
                    else
                        ExtractorLinkType.VIDEO

                this.headers = mapOf(
                    "Referer" to iframeUrl,
                    "Origin" to "https://novelas360.cyou",
                    "User-Agent" to "Mozilla/5.0"
                )
            }
        )

        return links
    }
}
