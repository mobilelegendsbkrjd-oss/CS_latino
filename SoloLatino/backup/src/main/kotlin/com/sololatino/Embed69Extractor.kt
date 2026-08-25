package com.sololatino

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URLEncoder

object Embed69Extractor {

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36"

    private const val BASE_PLAYER =
        "https://player.pelisserieshoy.com"

    suspend fun load(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        try {

            val fixedUrl =
                fixHosts(url)

            val html =
                app.get(
                    fixedUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to referer
                    )
                ).text

            // =========================
            // TOKEN NUEVO
            // =========================
            val token =
                Regex(
                    """['"]([a-f0-9]{32})['"]"""
                )
                    .find(html)
                    ?.groupValues
                    ?.getOrNull(1)

            if (!token.isNullOrBlank()) {

                try {

                    // =========================
                    // CLICK FAKE
                    // =========================
                    app.post(
                        "$BASE_PLAYER/s.php",
                        data = mapOf(
                            "a" to "click",
                            "tok" to token
                        ),
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to fixedUrl,
                            "Content-Type" to "application/x-www-form-urlencoded"
                        )
                    )

                    // =========================
                    // SCAN SERVERS
                    // =========================
                    val scanResponse =
                        app.post(
                            "$BASE_PLAYER/s.php",
                            data = mapOf(
                                "a" to "1",
                                "tok" to token
                            ),
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to fixedUrl,
                                "Content-Type" to "application/x-www-form-urlencoded"
                            )
                        ).text

                    val scanJson =
                        JSONObject(scanResponse)

                    if (scanJson.has("s")) {

                        val array =
                            scanJson.getJSONArray("s")

                        for (i in 0 until array.length()) {

                            try {

                                val item =
                                    array.getJSONArray(i)

                                if (item.length() < 2)
                                    continue

                                val serverName =
                                    item.getString(0)

                                val id =
                                    item.getString(1)

                                // =========================
                                // RESOLVE SERVER
                                // =========================
                                val postResponse =
                                    app.post(
                                        "$BASE_PLAYER/s.php",
                                        data = mapOf(
                                            "a" to "2",
                                            "v" to id,
                                            "tok" to token
                                        ),
                                        headers = mapOf(
                                            "User-Agent" to USER_AGENT,
                                            "Referer" to fixedUrl,
                                            "Content-Type" to "application/x-www-form-urlencoded"
                                        )
                                    ).text

                                val json =
                                    JSONObject(postResponse)

                                val realUrl =
                                    json.optString("u")

                                val sig =
                                    json.optString("sig")

                                if (realUrl.isBlank())
                                    continue

                                val playerUrl =
                                    "$BASE_PLAYER/p.php?url=${
                                        URLEncoder.encode(
                                            realUrl,
                                            "UTF-8"
                                        )
                                    }&sig=$sig"

                                val playerHtml =
                                    app.get(
                                        playerUrl,
                                        headers = mapOf(
                                            "User-Agent" to USER_AGENT,
                                            "Referer" to fixedUrl
                                        )
                                    ).text

                                // =========================
                                // M3U8
                                // =========================
                                Regex(
                                    """https?://[^\s"'<>]+?\.m3u8[^\s"'<>]*"""
                                )
                                    .findAll(playerHtml)
                                    .forEach { match ->

                                        val link =
                                            match.value

                                        callback.invoke(
                                            newExtractorLink(
                                                source = "SoloLatino",
                                                name = serverName,
                                                url = link,
                                                type = ExtractorLinkType.M3U8
                                            ) {
                                                this.referer = playerUrl
                                            }
                                        )
                                    }

                                // =========================
                                // MP4
                                // =========================
                                Regex(
                                    """https?://[^\s"'<>]+?\.mp4[^\s"'<>]*"""
                                )
                                    .findAll(playerHtml)
                                    .forEach { match ->

                                        val link =
                                            match.value

                                        callback.invoke(
                                            newExtractorLink(
                                                source = "SoloLatino",
                                                name = serverName,
                                                url = link,
                                                type = ExtractorLinkType.VIDEO
                                            ) {
                                                this.referer = playerUrl
                                            }
                                        )
                                    }

                            } catch (_: Exception) {}
                        }
                    }

                } catch (_: Exception) {}
            }

            // =========================
            // FALLBACK SL_DIRECT
            // =========================
            Regex(
                """sl_direct([^"'&<>\s]+)"""
            )
                .findAll(html)
                .mapNotNull {
                    it.groupValues.getOrNull(1)
                }
                .distinct()
                .forEach { enc ->

                    try {

                        val decoded =
                            String(
                                Base64.decode(
                                    enc,
                                    Base64.DEFAULT
                                )
                            )

                        val parts =
                            decoded.split("||")

                        if (parts.size < 2)
                            return@forEach

                        val id =
                            parts[0]

                        val originalWeb =
                            parts[1]

                        val origin =
                            originalWeb.substringBefore(
                                "/f/"
                            )

                        val post =
                            app.post(
                                "$origin/s.php",
                                data = mapOf(
                                    "a" to "2",
                                    "v" to id
                                ),
                                headers = mapOf(
                                    "Referer" to originalWeb,
                                    "Origin" to origin,
                                    "User-Agent" to USER_AGENT,
                                    "Content-Type" to "application/x-www-form-urlencoded"
                                )
                            ).text

                        val json =
                            JSONObject(post)

                        val realUrl =
                            json.optString("u")

                        val sig =
                            json.optString("sig")

                        if (realUrl.isBlank())
                            return@forEach

                        val playerUrl =
                            "$BASE_PLAYER/p.php?url=${
                                URLEncoder.encode(
                                    realUrl,
                                    "UTF-8"
                                )
                            }&sig=$sig"

                        val playerHtml =
                            app.get(
                                playerUrl,
                                headers = mapOf(
                                    "User-Agent" to USER_AGENT,
                                    "Referer" to originalWeb
                                )
                            ).text

                        Regex(
                            """https?://[^\s"'<>]+?\.(m3u8|mp4)[^\s"'<>]*"""
                        )
                            .findAll(playerHtml)
                            .forEach { match ->

                                val link =
                                    match.value

                                val type =
                                    if (link.contains(".m3u8")) {
                                        ExtractorLinkType.M3U8
                                    } else {
                                        ExtractorLinkType.VIDEO
                                    }

                                callback.invoke(
                                    newExtractorLink(
                                        source = "SoloLatino",
                                        name = "Embed69",
                                        url = link,
                                        type = type
                                    ) {
                                        this.referer = playerUrl
                                    }
                                )
                            }

                    } catch (_: Exception) {}
                }

            // =========================
            // FALLBACK DATALINK VIEJO
            // =========================
            Regex(
                """dataLink\s*=\s*(\[.*?\]);"""
            )
                .find(html)
                ?.groupValues
                ?.getOrNull(1)
                ?.let { jsonStr ->

                    AppUtils.tryParseJson<
                            List<Map<String, Any>>
                            >(jsonStr)
                        ?.forEach { lang ->

                            (
                                    lang["sortedEmbeds"]
                                            as? List<Map<String, Any>>
                                    )
                                ?.forEach { embed ->

                                    val enc =
                                        embed["link"] as? String
                                            ?: return@forEach

                                    val realUrl =
                                        decodeOldJwt(enc)
                                            ?: return@forEach

                                    try {

                                        loadExtractor(
                                            fixHosts(realUrl),
                                            fixedUrl,
                                            subtitleCallback,
                                            callback
                                        )

                                    } catch (_: Exception) {}
                                }
                        }
                }

            // =========================
            // FALLBACK IFRAMES
            // =========================
            app.get(
                fixedUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer
                )
            ).document.select("iframe")
                .forEach {

                    val src =
                        it.attr("src")

                    if (src.startsWith("http")) {

                        try {

                            loadExtractor(
                                src,
                                fixedUrl,
                                subtitleCallback,
                                callback
                            )

                        } catch (_: Exception) {}
                    }
                }

        } catch (_: Exception) {}
    }

    // =========================
    // JWT VIEJO
    // =========================
    private fun decodeOldJwt(
        enc: String
    ): String? {

        return try {

            val parts =
                enc.split(".")

            if (parts.size != 3)
                return null

            var payload =
                parts[1]

            val pad =
                payload.length % 4

            if (pad != 0) {
                payload += "=".repeat(
                    4 - pad
                )
            }

            val json =
                String(
                    Base64.decode(
                        payload,
                        Base64.DEFAULT
                    )
                )

            Regex(
                "\"link\":\"(.*?)\""
            )
                .find(json)
                ?.groupValues
                ?.getOrNull(1)

        } catch (_: Exception) {
            null
        }
    }

    // =========================
    // FIX HOSTS
    // =========================
    private fun fixHosts(
        url: String
    ): String {

        return url
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
                "vidhide.com",
                "vidhidepro.com"
            )
            .replace(
                "filemoon.link",
                "filemoon.sx"
            )
            .replace(
                "doodstream.com",
                "dood.la"
            )
            .replace(
                "voe.sx",
                "voe.unblockit.cat"
            )
    }
}