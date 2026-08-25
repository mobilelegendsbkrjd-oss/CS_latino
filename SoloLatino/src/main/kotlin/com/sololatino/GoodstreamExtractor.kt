package com.sololatino

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink

class GoodstreamExtractor : ExtractorApi() {

    override var name = "Goodstream"
    override var mainUrl = "https://goodstream.one"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            println("[Goodstream] Procesando: $url")

            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                "Referer" to "https://goodstream.one"
            )

            val html = app.get(url, headers = headers).text

            // Buscar file: "url"
            val fileRegex = Regex("""file\s*:\s*["']([^"']+)["']""")
            val fileMatch = fileRegex.find(html)

            if (fileMatch != null) {
                val streamUrl = fileMatch.groupValues[1]
                println("[Goodstream] Stream encontrado: $streamUrl")

                val type = if (streamUrl.contains(".m3u8")) {
                    ExtractorLinkType.M3U8
                } else {
                    ExtractorLinkType.VIDEO
                }

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = streamUrl,
                        type = type
                    ) {
                        this.referer = "https://goodstream.one"
                        this.headers = headers
                    }
                )
                return
            }

            // Fallback: buscar m3u8
            val m3u8Regex = Regex("""["'](https?://[^"']*\.m3u8[^"']*)["']""")
            val m3u8Match = m3u8Regex.find(html)

            if (m3u8Match != null) {
                val streamUrl = m3u8Match.groupValues[1]
                println("[Goodstream] M3U8 encontrado: $streamUrl")

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = streamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://goodstream.one"
                        this.headers = headers
                    }
                )
                return
            }

            // Fallback: usar loadExtractor
            println("[Goodstream] No se encontraron enlaces, usando loadExtractor")
            com.lagradost.cloudstream3.utils.loadExtractor(url, referer, subtitleCallback, callback)

        } catch (e: Exception) {
            println("[Goodstream] Error: ${e.message}")
            com.lagradost.cloudstream3.utils.loadExtractor(url, referer, subtitleCallback, callback)
        }
    }
}