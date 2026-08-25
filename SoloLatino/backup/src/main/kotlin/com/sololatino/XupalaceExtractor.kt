package com.sololatino

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

class XupalaceExtractor : ExtractorApi() {

    override val name =
        "Xupalace"

    override val mainUrl =
        "https://xupalace.org"

    override val requiresReferer =
        true

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

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        try {

            val safeUrl = fixUrl(url)

            val mainReferer =
                referer ?: mainUrl

            val doc = app.get(
                safeUrl,
                referer = mainReferer
            ).document

            val html = doc.html()

            val candidates =
                mutableListOf<String>()

            // =========================
            // IFRAME
            // =========================
            doc.select("iframe").forEach {

                val src =
                    it.attr("src")

                if (
                    src.startsWith("http")
                ) {
                    candidates.add(src)
                }
            }

            // =========================
            // SOURCES
            // =========================
            Regex(
                """https?:\/\/[^\s"'<>]+"""
            )
                .findAll(html)
                .map {
                    clean(it.value)
                }
                .distinct()
                .forEach {
                    candidates.add(it)
                }

            // =========================
            // EMBED URLs
            // =========================
            Regex(
                """embed[^"' ]+"""
            )
                .findAll(html)
                .map {
                    clean(it.value)
                }
                .distinct()
                .forEach {

                    if (
                        it.startsWith("http")
                    ) {
                        candidates.add(it)
                    }
                }

            val unique =
                candidates
                    .map {
                        clean(it)
                    }
                    .distinct()

            var found = false

            unique.forEach { embed ->

                try {

                    val lower =
                        embed.lowercase()

                    // =========================
                    // HOSTS CONOCIDOS
                    // =========================
                    if (
                        knownHosts.any {
                            lower.contains(it)
                        }
                    ) {

                        when {

                            lower.contains("dood") -> {

                                DoodExtractor().getUrl(
                                    embed,
                                    safeUrl,
                                    subtitleCallback
                                ) {
                                    found = true
                                    callback(it)
                                }
                            }

                            lower.contains("f75s") -> {

                                F75s().getUrl(
                                    embed,
                                    safeUrl,
                                    subtitleCallback
                                ) {
                                    found = true
                                    callback(it)
                                }
                            }

                            else -> {

                                loadExtractor(
                                    embed,
                                    safeUrl,
                                    subtitleCallback
                                ) {

                                    found = true
                                    callback(it)
                                }
                            }
                        }
                    }

                } catch (_: Exception) {}
            }

            // =========================
            // FALLBACK GENERAL
            // =========================
            if (!found) {

                unique.forEach { embed ->

                    try {

                        loadExtractor(
                            embed,
                            safeUrl,
                            subtitleCallback,
                            callback
                        )

                    } catch (_: Exception) {}
                }
            }

        } catch (_: Exception) {}
    }

    // =========================
    // CLEAN URL
    // =========================
    private fun clean(
        u: String
    ): String {

        return u
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .trim()
    }
}