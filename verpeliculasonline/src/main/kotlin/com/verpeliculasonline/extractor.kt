package com.verpeliculasonline

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink

object UniversalHostResolver {

    suspend fun resolve(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fixed = fixHostsLinks(url)
        val lower = fixed.lowercase()
        var found = false

        try {
            when {
                lower.contains("opuxa") ||
                        lower.contains("waaw.to") ||
                        lower.contains("netu.tv") ||
                        lower.contains("hqq.to") -> {
                    found = extractOpuxa(fixed, referer, subtitleCallback, callback)
                }

                lower.contains(".m3u8") ||
                        lower.contains(".mp4") ||
                        lower.contains(".mkv") ||
                        lower.contains(".avi") -> {
                    callback.invoke(
                        newExtractorLink(
                            "Directo",
                            "Directo",
                            fixed,
                            INFER_TYPE
                        ) {
                            this.referer = referer
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    found = true
                }

                else -> {
                    try {
                        loadExtractor(fixed, referer, subtitleCallback) {
                            found = true
                            callback.invoke(it)
                        }
                    } catch (_: Exception) {
                    }

                    if (!found) {
                        found = tryResolveGeneric(fixed, referer, callback)
                    }
                }
            }
        } catch (_: Exception) {
        }

        return found
    }

    private suspend fun extractOpuxa(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        try {
            val doc = app.get(url, referer = referer).document

            val frames = doc.select("iframe[src], frame[src]")

            for (frame in frames) {
                var src = frame.attr("abs:src").ifBlank { frame.attr("src") }.trim()

                if (src.startsWith("//")) src = "https:$src"
                if (src.startsWith("/")) {
                    src = when {
                        url.contains("opuxa.lat") -> "https://opuxa.lat$src"
                        url.contains("waaw.to") -> "https://waaw.to$src"
                        url.contains("netu.tv") -> "https://netu.tv$src"
                        url.contains("hqq.to") -> "https://hqq.to$src"
                        else -> src
                    }
                }

                if (src.isBlank() || !src.startsWith("http")) continue

                val fixed = fixHostsLinks(src)

                try {
                    loadExtractor(fixed, url, subtitleCallback) {
                        found = true
                        callback.invoke(it)
                    }
                } catch (_: Exception) {
                }

                if (!found) {
                    tryResolveGeneric(fixed, url, callback).also {
                        if (it) found = true
                    }
                }
            }

            val html = doc.html()
                .replace("\\/", "/")
                .replace("&amp;", "&")

            val links = Regex("""https?://[^\s"'<>\\]+""")
                .findAll(html)
                .map { it.value.trim() }
                .filter { it.startsWith("http") }
                .distinct()
                .toList()

            for (link in links) {
                if (isBadLink(link)) continue

                val fixed = fixHostsLinks(link)

                if (
                    fixed.contains(".m3u8") ||
                    fixed.contains(".mp4") ||
                    fixed.contains(".mkv") ||
                    fixed.contains(".avi")
                ) {
                    callback.invoke(
                        newExtractorLink(
                            "Opuxa",
                            "Opuxa",
                            fixed,
                            INFER_TYPE
                        ) {
                            this.referer = url
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    found = true
                } else {
                    try {
                        loadExtractor(fixed, url, subtitleCallback) {
                            found = true
                            callback.invoke(it)
                        }
                    } catch (_: Exception) {
                    }
                }
            }

        } catch (_: Exception) {
        }

        return found
    }

    private suspend fun tryResolveGeneric(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        try {
            val html = app.get(url, referer = referer).text
                .replace("\\/", "/")
                .replace("&amp;", "&")

            val patterns = listOf(
                """file\s*[:=]\s*["']([^"']+)["']""".toRegex(),
                """src\s*[:=]\s*["']([^"']+)["']""".toRegex(),
                """source\s*[:=]\s*["']([^"']+)["']""".toRegex(),
                """video_url\s*[:=]\s*["']([^"']+)["']""".toRegex(),
                """["'](https?://[^"']+?\.(?:m3u8|mp4|mkv|avi)[^"']*)["']""".toRegex()
            )

            for (pattern in patterns) {
                for (match in pattern.findAll(html)) {
                    val video = match.groups[1]?.value?.trim() ?: continue
                    if (!video.startsWith("http")) continue

                    callback.invoke(
                        newExtractorLink(
                            "Directo",
                            "Directo",
                            video,
                            INFER_TYPE
                        ) {
                            this.referer = referer
                            this.quality = Qualities.Unknown.value
                        }
                    )

                    found = true
                }
            }

        } catch (_: Exception) {
        }

        return found
    }

    private fun fixHostsLinks(url: String): String {
        return url
            .replace("https://hglink.to", "https://streamwish.to")
            .replace("https://swdyu.com", "https://streamwish.to")
            .replace("https://cybervynx.com", "https://streamwish.to")
            .replace("https://dumbalag.com", "https://streamwish.to")
            .replace("https://mivalyo.com", "https://vidhidepro.com")
            .replace("https://dinisglows.com", "https://vidhidepro.com")
            .replace("https://dhtpre.com", "https://vidhidepro.com")
            .replace("https://filemoon.link", "https://filemoon.sx")
            .replace("https://sblona.com", "https://watchsb.com")
            .replace("https://lulu.st", "https://lulustream.com")
            .replace("https://uqload.io", "https://uqload.com")
            .replace("https://do7go.com", "https://dood.la")
            .replace("https://dooood.com", "https://dood.la")
            .replace("https://dood.so", "https://dood.la")
            .replace("https://dood.ws", "https://dood.la")
            .replace("https://dood.to", "https://dood.la")
    }

    private fun isBadLink(url: String): Boolean {
        val bad = listOf(
            "youtube.com",
            "youtu.be",
            "vimeo.com",
            "facebook.com",
            "instagram.com",
            "twitter.com",
            "trailer",
            "teaser",
            ".jpg",
            ".jpeg",
            ".png",
            ".webp",
            ".gif"
        )

        return bad.any { url.contains(it, ignoreCase = true) }
    }
}