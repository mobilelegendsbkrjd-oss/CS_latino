package com.novelas360

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities

class ExtractorNovelas360 : ExtractorApi() {

    override val name = "Novelas360"
    override val mainUrl = "https://novelas360.cyou"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?
    ): List<ExtractorLink>? {

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0",
            "Referer" to (referer ?: mainUrl)
        )

        // abrir embed
        val res = app.get(
            url,
            headers = headers
        )

        val html = res.text

        // extraer ws token
        val ws = Regex("""var\s+ws\s*=\s*['"]([^'"]+)""")
            .find(html)
            ?.groupValues
            ?.get(1)
            ?: return null

        // extraer ID del video
        val id = url.substringAfter("/e/")

        // construir endpoint real
        val videoPage = "$mainUrl/f/$id$ws"

        val videoRes = app.get(
            videoPage,
            headers = headers,
            referer = url
        ).text

        // buscar playlist
        val m3u8 = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
            .find(videoRes)
            ?.value
            ?: return null

        val link = ExtractorLink(
            name,
            name,
            m3u8,
            mainUrl,
            Qualities.Unknown.value,
            ExtractorLinkType.M3U8
        )

        return listOf(link)
    }
}
