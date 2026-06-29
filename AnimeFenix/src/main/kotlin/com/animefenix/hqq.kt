package com.animefenix

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

class HqqExtractor : ExtractorApi() {
    override var name = "HQQ"
    override var mainUrl = "https://hqq.tv"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val html = app.get(url, referer = referer ?: "https://animefenix2.tv/").text

        val m3u8 = Regex("""https?://[^"'\\\s]+\.m3u8[^"'\\\s]*""")
            .find(html)
            ?.value
            ?: return emptyList()

        return listOf(
            newExtractorLink(
                source = name,
                name = name,
                url = m3u8,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = url
                this.quality = Qualities.Unknown.value
            }
        )
    }
}