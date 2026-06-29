package com.animefenix

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import java.util.Base64

class IronHentai : ExtractorApi() {
    override var name = "IronHentai"
    override var mainUrl = "https://re.ironhentai.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val html = app.get(
            url,
            referer = referer ?: "https://animefenix2.tv/"
        ).text

        val encoded = Regex(
            """atob\(atob\(['"]([^'"]+)['"]\)\.split\(''\)\.map""",
            RegexOption.DOT_MATCHES_ALL
        ).find(html)?.groupValues?.getOrNull(1) ?: return emptyList()

        val first = String(Base64.getDecoder().decode(encoded), Charsets.ISO_8859_1)
        val shifted = first.map { (it.code - 1).toChar() }.joinToString("")
        val decoded = String(Base64.getDecoder().decode(shifted), Charsets.UTF_8)
            .replace("\\/", "/")
            .replace("\\u0026", "&")

        val m3u8 = Regex("""https?://[^"'\\\s]+\.m3u8[^"'\\\s]*""")
            .find(decoded)
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
                this.quality = Qualities.P1080.value
            }
        )
    }
}