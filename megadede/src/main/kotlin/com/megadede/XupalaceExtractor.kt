package com.megadede

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import java.net.URI

class XupalaceExtractor : ExtractorApi() {

    override val name = "Xupalace"
    override val mainUrl = "https://xupalace.org"
    override val requiresReferer = true

    private val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"

    private val knownHosts = listOf(
        "vidhide",
        "filemoon",
        "dood",
        "voe",
        "wish",
        "streamwish",
        "hglink",
        "stape",
        "waaw",
        "player-cdn",
        "minoplayers",
        "minochinos"
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val safeUrl = fixUrlLocal(url, mainUrl)
            val mainReferer = referer ?: "https://megadede.mobi/"

            val doc = app.get(
                safeUrl,
                referer = mainReferer,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to mainReferer,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
            ).document

            val html = doc.html()
            val candidates = mutableListOf<String>()

            Regex("""go_to_playerVast\(['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
                .findAll(html)
                .map { clean(it.groupValues[1]) }
                .forEach { candidates.add(fixUrlLocal(it, safeUrl)) }

            doc.select("iframe").forEach {
                val src = it.attr("src")
                    .ifBlank { it.attr("data-src") }
                    .trim()

                if (src.isNotBlank()) {
                    candidates.add(fixUrlLocal(src, safeUrl))
                }
            }

            Regex("""https?://[^\s"'<>]+""")
                .findAll(html)
                .map { clean(it.value) }
                .forEach { candidates.add(it) }

            val unique = candidates
                .map { clean(fixHosts(it)) }
                .filter { it.startsWith("http") }
                .filterNot { it.contains("1fichier", true) }
                .filterNot { it.contains("/ggtz", true) }
                .distinct()

            unique.forEach { embed ->
                try {
                    val lower = embed.lowercase()

                    if (knownHosts.any { lower.contains(it) }) {
                        loadExtractor(
                            embed,
                            safeUrl,
                            subtitleCallback,
                            callback
                        )
                    }
                } catch (_: Exception) {
                }
            }

        } catch (_: Exception) {
        }
    }

    private fun clean(u: String): String {
        return u
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .trim()
            .trim('"', '\'')
    }

    private fun fixUrlLocal(url: String, baseUrl: String): String {
        val clean = clean(url)

        return try {
            when {
                clean.startsWith("//") -> "https:$clean"
                clean.startsWith("http") -> clean
                else -> URI(baseUrl).resolve(clean).toString()
            }
        } catch (_: Exception) {
            clean
        }
    }

    private fun fixHosts(url: String): String {
        return url
            .replace("hglink.to", "streamwish.to")
            .replace("swdyu.com", "streamwish.to")
            .replace("wishembed.com", "streamwish.to")
            .replace("vidhide.com", "vidhidepro.com")
            .replace("filemoon.link", "filemoon.sx")
            .replace("doodstream.com", "dood.la")
            .replace("voe.sx", "voe.unblockit.cat")
    }
}