package com.sololatino

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class F75s : ExtractorApi() {

    override var name = "F75s"

    override var mainUrl =
        "https://f75s.com"

    override val requiresReferer =
        true

    // =========================
    // BASE64 SAFE
    // =========================
    private fun decode(
        value: String
    ): ByteArray {

        val normalized =
            value + "=".repeat(
                (4 - (value.length % 4)) % 4
            )

        return Base64.decode(
            normalized,
            Base64.URL_SAFE or
                    Base64.NO_WRAP
        )
    }

    // =========================
    // MAIN
    // =========================
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        try {

            val code =
                url.substringAfterLast("/")

            val embedUrl =
                "$mainUrl/embed/$code"

            val headers = mapOf(
                "Referer" to
                        (referer ?: mainUrl),
                "User-Agent" to
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
            )

            val res = app.get(
                embedUrl,
                headers = headers
            )

            val root =
                JSONObject(res.text)

            val playback =
                root.getJSONObject(
                    "playback"
                )

            val algorithm =
                playback.optString(
                    "algorithm"
                )

            // =========================
            // AES-256-GCM
            // =========================
            if (
                algorithm.contains(
                    "AES-256-GCM",
                    true
                )
            ) {

                val iv =
                    playback.getString("iv")

                val payload =
                    playback.getString(
                        "payload"
                    )

                val keyParts =
                    root.optJSONArray(
                        "keys"
                    ) ?: JSONArray()

                val key =
                    buildString {

                        for (i in 0 until keyParts.length()) {
                            append(
                                keyParts.getString(i)
                            )
                        }
                    }

                val cipher = Cipher.getInstance(
                    "AES/GCM/NoPadding"
                )

                val secretKey =
                    SecretKeySpec(
                        key.toByteArray(),
                        "AES"
                    )

                val ivSpec =
                    javax.crypto.spec.GCMParameterSpec(
                        128,
                        decode(iv)
                    )

                cipher.init(
                    Cipher.DECRYPT_MODE,
                    secretKey,
                    ivSpec
                )

                val decrypted =
                    cipher.doFinal(
                        decode(payload)
                    )

                val json = JSONObject(
                    String(decrypted)
                )

                val sources =
                    json.optJSONArray(
                        "sources"
                    ) ?: JSONArray()

                for (i in 0 until sources.length()) {

                    val source =
                        sources.getJSONObject(i)

                    val file =
                        source.optString(
                            "file"
                        )

                    if (file.isNullOrBlank()) {
                        continue
                    }

                    callback.invoke(
                        newExtractorLink(
                            name,
                            name,
                            file
                        ) {

                            this.type =
                                if (
                                    file.contains(
                                        ".m3u8"
                                    )
                                ) {
                                    ExtractorLinkType.M3U8
                                } else {
                                    ExtractorLinkType.VIDEO
                                }

                            this.referer =
                                embedUrl
                        }
                    )
                }

                return
            }

            // =========================
            // FALLBACK NORMAL
            // =========================
            Regex(
                """https?:\/\/[^\s"'<>]+"""
            )
                .findAll(res.text)
                .map {
                    it.value
                }
                .distinct()
                .forEach { file ->

                    if (
                        file.contains(".m3u8") ||
                        file.contains(".mp4")
                    ) {

                        callback.invoke(
                            newExtractorLink(
                                name,
                                name,
                                file
                            ) {

                                this.type =
                                    if (
                                        file.contains(
                                            ".m3u8"
                                        )
                                    ) {
                                        ExtractorLinkType.M3U8
                                    } else {
                                        ExtractorLinkType.VIDEO
                                    }

                                this.referer =
                                    embedUrl
                            }
                        )
                    }
                }

        } catch (_: Exception) {}
    }
}