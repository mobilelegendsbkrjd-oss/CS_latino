package com.sololatino

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import kotlin.text.Regex

object PlayerPelisSeriesHoyExtractor {

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

    suspend fun load(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        try {

            val res = app.get(
                url,
                headers = mapOf(
                    "Referer" to referer,
                    "User-Agent" to USER_AGENT
                )
            )

            val doc = res.document
            val html = res.text

            val extracted =
                mutableSetOf<String>()

            // =========================
            // BOTONES OFICIALES
            // =========================
            val servers =
                doc.select("button.server-btn")
                    .mapNotNull {

                        val server =
                            it.attr("data-server-url")
                                .trim()

                        if (
                            server.isBlank()
                        ) {
                            return@mapNotNull null
                        }

                        server
                    }

            // =========================
            // FALLBACK GLOBAL
            // =========================
            val fallback =
                Regex(
                    """https?:\/\/[^\s"'<>]+"""
                )
                    .findAll(html)
                    .map {
                        it.value
                    }
                    .filter {

                        it.contains("/f/") ||
                                it.contains("embed69") ||
                                it.contains("playhydrax") ||
                                it.contains("streamwish") ||
                                it.contains("vidhide") ||
                                it.contains("filemoon") ||
                                it.contains("voe") ||
                                it.contains("goodstream")
                    }

            val allServers =
                (servers + fallback)
                    .distinct()

            // =========================
            // ROUTER
            // =========================
            allServers.forEach { server ->

                val fixed =
                    fixHosts(server)

                if (
                    !extracted.add(fixed)
                ) {
                    return@forEach
                }

                try {

                    // =========================
                    // EMBED69
                    // =========================
                    if (
                        fixed.contains("embed69")
                    ) {

                        Embed69Extractor.load(
                            fixed,
                            url,
                            subtitleCallback,
                            callback
                        )

                        return@forEach
                    }

                    // =========================
                    // HYDRAX
                    // =========================
                    if (
                        fixed.contains("playhydrax")
                    ) {

                        loadExtractor(
                            fixed,
                            "https://playhydrax.com/",
                            subtitleCallback,
                            callback
                        )

                        return@forEach
                    }

                    // =========================
                    // GOODSTREAM
                    // =========================
                    if (
                        fixed.contains("goodstream")
                    ) {

                        loadExtractor(
                            fixed,
                            url,
                            subtitleCallback,
                            callback
                        )

                        return@forEach
                    }

                    // =========================
                    // VOE
                    // =========================
                    if (
                        fixed.contains("voe")
                    ) {

                        loadExtractor(
                            fixed,
                            url,
                            subtitleCallback,
                            callback
                        )

                        return@forEach
                    }

                    // =========================
                    // FILEMOON
                    // =========================
                    if (
                        fixed.contains("filemoon")
                    ) {

                        loadExtractor(
                            fixed,
                            url,
                            subtitleCallback,
                            callback
                        )

                        return@forEach
                    }

                    // =========================
                    // VIDHIDE
                    // =========================
                    if (
                        fixed.contains("vidhide")
                    ) {

                        loadExtractor(
                            fixed,
                            url,
                            subtitleCallback,
                            callback
                        )

                        return@forEach
                    }

                    // =========================
                    // STREAMWISH
                    // =========================
                    if (
                        fixed.contains("streamwish") ||
                        fixed.contains("hglink") ||
                        fixed.contains("wishembed")
                    ) {

                        loadExtractor(
                            fixed,
                            url,
                            subtitleCallback,
                            callback
                        )

                        return@forEach
                    }

                    // =========================
                    // FALLBACK GLOBAL
                    // =========================
                    loadExtractor(
                        fixed,
                        url,
                        subtitleCallback,
                        callback
                    )

                } catch (_: Exception) {}
            }

        } catch (_: Exception) {}
    }

    // =========================
    // FIX HOSTS
    // =========================
    private fun fixHosts(
        url: String
    ): String {

        return url

            // STREAMWISH
            .replace(
                "hglink.to",
                "streamwish.to"
            )
            .replace(
                "swdyu.com",
                "streamwish.to"
            )
            .replace(
                "wishembed.com",
                "streamwish.to"
            )
            .replace(
                "stwishe.com",
                "streamwish.to"
            )

            // VIDHIDE
            .replace(
                "vidhide.com",
                "vidhidepro.com"
            )
            .replace(
                "mivalyo.com",
                "vidhidepro.com"
            )
            .replace(
                "dinisglows.com",
                "vidhidepro.com"
            )

            // FILEMOON
            .replace(
                "filemoon.link",
                "filemoon.sx"
            )
            .replace(
                "filemoon.lat",
                "filemoon.sx"
            )

            // VOE
            .replace(
                "voe.sx",
                "voe.unblockit.cat"
            )

            // OTHERS
            .replace(
                "uqload.io",
                "uqload.com"
            )
            .replace(
                "sbfull.com",
                "watchsb.com"
            )
    }
}