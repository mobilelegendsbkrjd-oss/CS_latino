package com.novelas360

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

class ExtractorNovelas360 : ExtractorApi() {

    override val name = "Novelas360 / Cyou"
    override val mainUrl = "https://novelas360.cyou"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?
    ): List<ExtractorLink>? {

        val fixedReferer = referer ?: mainUrl

        app.get(
            url,
            referer = fixedReferer,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0",
                "Origin" to mainUrl
            )
        )

        val key = when {
            url.contains("/e/") -> url.substringAfter("/e/")
            url.contains("/v/") -> url.substringAfter("/v/")
            url.contains("/embed/") -> url.substringAfter("/embed/")
            else -> return null
        }

        val headers = mapOf(
            "Origin" to mainUrl,
            "Referer" to url,
            "X-Requested-With" to "XMLHttpRequest",
            "User-Agent" to "Mozilla/5.0"
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

        val link = newExtractorLink(
            source = name,
            name = "Servidor Cyou",
            url = file,
            referer = url
        ) {
            this.quality = Qualities.Unknown.value
            this.type = if (file.contains(".m3u8"))
                ExtractorLinkType.M3U8
            else
                ExtractorLinkType.VIDEO
        }

        return listOf(link)
    }
}
