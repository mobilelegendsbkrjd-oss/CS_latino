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

        val res = app.get(
            url,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0",
                "Referer" to (referer ?: mainUrl)
            )
        )

        val html = res.text

        val links = mutableListOf<String>()

        // detectar m3u8 directo
        Regex("""https?:\/\/[^\s"'<>]+\.m3u8""")
            .findAll(html)
            .forEach {
                links.add(it.value.replace("\\/", "/"))
            }

        // detectar mp4
        Regex("""https?:\/\/[^\s"'<>]+\.mp4""")
            .findAll(html)
            .forEach {
                links.add(it.value.replace("\\/", "/"))
            }

        // detectar CDN oculto
        Regex("""https?:\/\/[^\s"'<>]*cfeucdn\.com[^\s"'<>]+""")
            .findAll(html)
            .forEach {
                links.add(it.value.replace("\\/", "/"))
            }

        if (links.isEmpty()) return null

        val finalLinks = links.distinct()

        val videos = mutableListOf<ExtractorLink>()

        finalLinks.forEach { link ->

            if (link.contains(".m3u8")) {

                val streams = M3u8Helper().m3u8Generation(
                    M3u8Helper.M3u8Stream(
                        link,
                        headers = mapOf(
                            "User-Agent" to "Mozilla/5.0",
                            "Referer" to mainUrl
                        )
                    ),
                    true
                )

                streams.forEach { stream ->

                    videos.add(
                        newExtractorLink(
                            source = name,
                            name = "$name ${stream.quality}p",
                            url = stream.streamUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = mainUrl
                            this.quality = stream.quality ?: Qualities.Unknown.value
                        }
                    )
                }

            } else {

                videos.add(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = link
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }

        return videos
    }
}