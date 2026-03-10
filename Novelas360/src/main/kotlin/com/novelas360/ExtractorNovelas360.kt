package com.novelas360

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class ExtractorNovelas360 : ExtractorApi() {

    override val name = "Novelas360"
    override val mainUrl = "https://novelas360.cyou"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?
    ): List<ExtractorLink>? {

        val doc = app.get(
            url,
            referer = referer,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0"
            )
        ).document

        val html = doc.html()

        val ws = Regex("""var ws\s*=\s*['"](.*?)['"]""")
            .find(html)?.groupValues?.get(1)
            ?: return null

        val id = url.substringAfter("/e/")

        val videoUrl = "$mainUrl/f/$id$ws"

        val res = app.get(
            videoUrl,
            referer = url,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0"
            )
        ).text

        val m3u8 = Regex("""https?://[^\s"]+\.m3u8[^\s"]*""")
            .find(res)?.value ?: return null

        val link = newExtractorLink(
            name,
            name,
            m3u8
        ) {
            this.referer = mainUrl
            this.quality = Qualities.Unknown.value
            this.type = ExtractorLinkType.M3U8
        }

        return listOf(link)
    }
}
