package com.sololatino

import com.sololatino.DoodExtractor
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor

class XupalaceExtractor : ExtractorApi() {

    override val name = "Xupalace"
    override val mainUrl = "https://xupalace.org"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        val safeUrl = url.replace("xupalace.com", "xupalace.org")
        val mainReferer = referer ?: mainUrl

        try {
            val doc = app.get(safeUrl, referer = mainReferer).document
            val html = doc.html()

            val candidates = mutableListOf<String>()

            fun clean(u: String): String {
                return u.replace("\\/", "/")
                    .replace("&amp;", "&")
                    .trim()
            }

            // =========================
            // 1. go_to_playerVast
            // =========================
            Regex("""go_to_playerVast\(['"]([^'"]+)""")
                .findAll(html)
                .forEach {
                    candidates.add(clean(it.groupValues[1]))
                }

            // =========================
            // 2. iframes
            // =========================
            doc.select("iframe[src]").forEach {
                val src = it.attr("abs:src")
                if (src.startsWith("http")) {
                    candidates.add(clean(src))
                }
            }

            // =========================
            // 3. raw urls
            // =========================
            Regex("""https?://[^\s'"]+""")
                .findAll(html)
                .forEach {
                    val link = it.value
                    if (knownHosts.any { h -> link.contains(h) }) {
                        candidates.add(clean(link))
                    }
                }

            // =========================
            // limpiar + ordenar
            // =========================
            val unique = candidates
                .distinct()
                .filter { it.startsWith("http") }
                .sortedBy {
                    when {
                        it.contains("vidhide") -> 0
                        it.contains("filemoon") -> 1
                        it.contains("dood") -> 2
                        it.contains("voe") -> 3
                        it.contains("wish") -> 4
                        else -> 99
                    }
                }

            var found = false

            for (embed in unique) {

                if (found) break  // 🔥 corta cuando ya jaló uno

                println("[Xupalace] -> $embed")

                try {
                    when {
                        embed.contains("dood") -> {
                            DoodExtractor().getUrl(embed, safeUrl, subtitleCallback, callback)
                            found = true
                        }

                        else -> {
                            loadExtractor(embed, safeUrl, subtitleCallback) {
                                found = true
                                callback(it)
                            }
                        }
                    }

                } catch (_: Exception) {}
            }

            // 🔥 fallback real
            if (!found) {
                println("[Xupalace] fallback")

                loadExtractor(safeUrl, mainReferer, subtitleCallback) {
                    callback(it)
                }
            }

        } catch (e: Exception) {
            println("[Xupalace] fatal error: ${e.message}")
        }
    }

    private val knownHosts = listOf(
        "vidhide",
        "filemoon",
        "dood",
        "voe",
        "wish",
        "streamwish",
        "minoplayers",
        "minochinos"
    )
}