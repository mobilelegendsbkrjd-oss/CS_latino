package com.sololatino

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import kotlin.random.Random

class DoodExtractor : ExtractorApi() {

    override val name = "Dood"

    override val mainUrl =
        "https://dood.la"

    override val requiresReferer =
        true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        try {

            val safeUrl = url
                .replace("/d/", "/e/")
                .replace(
                    "doodstream.com",
                    "dood.la"
                )

            val response = app.get(
                safeUrl,
                referer = referer
            )

            val html = response.text

            // =========================
            // PLAYER URL
            // =========================
            val md5Match = Regex(
                """\/pass_md5\/[^"]+"""
            )
                .find(html)
                ?.value
                ?: return

            val base =
                safeUrl.substringBefore(
                    "/e/"
                )

            val md5Url =
                "$base$md5Match"

            val token = Regex(
                """token=([a-zA-Z0-9]+)"""
            )
                .find(html)
                ?.groupValues
                ?.getOrNull(1)
                ?: ""

            val prefix = app.get(
                md5Url,
                referer = safeUrl,
                headers = mapOf(
                    "X-Requested-With" to
                            "XMLHttpRequest"
                )
            ).text.trim()

            val finalUrl =
                prefix +
                        randomString(10) +
                        "?token=$token"

            callback.invoke(
                newExtractorLink(
                    name,
                    name,
                    finalUrl
                ) {
                    this.referer = safeUrl
                    this.quality = Qualities.Unknown.value
                    this.type =
                        ExtractorLinkType.VIDEO
                }
            )

        } catch (_: Exception) {}
    }

    // =========================
    // RANDOM STRING
    // =========================
    private fun randomString(
        length: Int
    ): String {

        val chars =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        return (1..length)
            .map {
                chars.random(Random)
            }
            .joinToString("")
    }
}