package com.novelas360

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

class ExtractorNovelas360 : ExtractorApi() {

    override val name = "Novelas360 / Cyou"
    override val mainUrl = "https://novelas360.cyou"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?
    ): List<ExtractorLink>? {

        val fixedReferer = referer ?: mainUrl

        // visitar iframe para cookies
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

        if (key.isBlank()) return null

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
            headers = headers,
            timeout = 30
        )

        val json = res.parsedSafe<Map<String, String>>() ?: return null
        val file = json["file"] ?: return null

        return listOf(
            newExtractorLink(
                source = name,
                name = "Servidor Cyou",
                url = file
            ) {
                referer = url
                quality = Qualities.Unknown.value
                isM3u8 = file.contains(".m3u8")
            }
        )
    }
}
