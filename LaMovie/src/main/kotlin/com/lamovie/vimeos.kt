package com.lamovie

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Vimeos : ExtractorApi() {
    override val name = "Vimeos"
    override val mainUrl = "https://vimeos.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embed = if (!url.contains("/embed-", true)) {
            val videoId = url.substringAfter("$mainUrl/")
            "$mainUrl/embed-$videoId"
        } else {
            url
        }

        val ref = "https://lamovie.org/"

        val html = app.get(
            embed,
            referer = ref,
            headers = mapOf(
                "User-Agent" to LaMovie.UA,
                "Referer" to ref,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "es-419,es;q=0.9"
            )
        ).text

        val unpacked = getAndUnpack(html).ifBlank { html }

        val videoUrl =
            Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
                .find(unpacked)?.groupValues?.getOrNull(1)
                ?: Regex("""https?://[^\s"'<>]+?\.m3u8[^\s"'<>]*""", RegexOption.IGNORE_CASE)
                    .find(unpacked)?.value

        if (!videoUrl.isNullOrBlank()) {
            M3u8Helper.generateM3u8(
                name,
                fixUrl(videoUrl),
                embed,
                headers = mapOf(
                    "User-Agent" to LaMovie.UA,
                    "Referer" to "$mainUrl/",
                    "Origin" to mainUrl
                )
            ).forEach(callback)
        }
    }
}

class GoodstreamExtractor : ExtractorApi() {
    override var name = "Goodstream"
    override val mainUrl = "https://goodstream.one"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = app.get(url, referer = referer).text

        val link = Regex("""file:\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)
            ?: Regex("""https?://[^\s"'<>]+?\.m3u8[^\s"'<>]*""", RegexOption.IGNORE_CASE)
                .find(html)?.value

        if (!link.isNullOrBlank()) {
            M3u8Helper.generateM3u8(
                name,
                fixUrl(link),
                "$mainUrl/"
            ).forEach(callback)
        }
    }
}