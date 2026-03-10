package com.novelas360

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

class ExtractorNovelas360 : ExtractorApi() {

    override val name = "Novelas360"
    override val mainUrl = "https://novelas360.cyou"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?
    ): List<ExtractorLink>? {

        val iframe = app.get(url, referer = referer).document
        val key = iframe.selectFirst("iframe")?.attr("src")
            ?.substringAfter("/e/")
            ?: return null

        val headers = mapOf(
            "Referer" to url,
            "Origin" to mainUrl,
            "X-Requested-With" to "XMLHttpRequest"
        )

        val data = mapOf(
            "v" to key,
            "secure" to "0",
            "ver" to "4",
            "adb" to "0",
            "wasmcheck" to "0"
        )

        val res = app.post(
            "$mainUrl/player/get_md5.php",
            data = data,
            headers = headers
        )

        val json = res.parsedSafe<Map<String, String>>() ?: return null
        val file = json["file"] ?: return null

        return listOf(
            newExtractorLink(
                source = name,
                name = name,
                url = file,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = url
                this.headers = mapOf(
                    "Referer" to url,
                    "Origin" to mainUrl,
                    "User-Agent" to USER_AGENT
                )
                this.quality = Qualities.Unknown.value
            }
        )
    }
}
