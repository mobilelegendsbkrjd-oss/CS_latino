package com.tubepelis

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.KeyPairGenerator
import java.security.Signature
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object FilemoonResolver {

    private val json = Json {
        ignoreUnknownKeys = true
    }

    private val FILEMOON_MIRRORS = listOf(
        "398fitus",
        "bysedikamoum",
        "r66nv9ed",
        "filemoon",
        "moonembed",
        "moonalu",
        "fmoon",
        "byseqekaho"
    )

    fun isFilemoon(url: String): Boolean {

        return FILEMOON_MIRRORS.any {
            url.contains(it, true)
        }
    }

    suspend fun resolve(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        try {

            val code =
                Regex("""/(?:e|d|v)/([a-zA-Z0-9_-]+)""")
                    .find(url)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?: return false

            val base =
                url.substringBefore("/e/")
                    .substringBefore("/d/")
                    .substringBefore("/v/")

            println("FILEMOON URL => $url")
            println("FILEMOON CODE => $code")

            // ============================================================
            // CHALLENGE
            // ============================================================

            val challengeResponse = app.post(
                "$base/api/videos/access/challenge",
                headers = mapOf(
                    "Origin" to base,
                    "Referer" to url,
                    "X-Embed-Origin" to "tubepelis.com",
                    "X-Embed-Referer" to referer,
                    "User-Agent" to USER_AGENT
                )
            ).text

            println("CHALLENGE => $challengeResponse")

            val challengeJson =
                json.parseToJsonElement(challengeResponse)
                    .jsonObject

            val nonce =
                challengeJson["nonce"]
                    ?.jsonPrimitive
                    ?.content
                    ?: return false

            // ============================================================
            // DUMMY SIGNATURE
            // ============================================================

            val keyGen =
                KeyPairGenerator.getInstance("EC")

            keyGen.initialize(256)

            val pair = keyGen.generateKeyPair()

            val signer =
                Signature.getInstance("SHA256withECDSA")

            signer.initSign(pair.private)

            signer.update(
                nonce.toByteArray()
            )

            val signature =
                Base64.encodeToString(
                    signer.sign(),
                    Base64.NO_WRAP or Base64.URL_SAFE
                )

            val attestJson = buildJsonObject {

                put("signature", signature)

                putJsonObject("public_key") {

                    put("kty", "EC")
                    put("crv", "P-256")

                    put(
                        "x",
                        "ZHVtbXk"
                    )

                    put(
                        "y",
                        "ZHVtbXk"
                    )
                }
            }

            println("ATTEST => $attestJson")

            // ============================================================
            // PLAYBACK
            // ============================================================

            val playback = app.post(
                "$base/api/videos/$code/embed/playback",
                headers = mapOf(
                    "Origin" to base,
                    "Referer" to url,
                    "X-Embed-Origin" to "tubepelis.com",
                    "X-Embed-Referer" to referer,
                    "User-Agent" to USER_AGENT,
                    "Content-Type" to "application/json"
                ),
                requestBody = attestJson.toString()
                    .toRequestBody(
                        "application/json".toMediaType()
                    )
            ).text

            println("PLAYBACK => $playback")

            val playbackJson =
                json.parseToJsonElement(playback)
                    .jsonObject

            val playbackData =
                playbackJson["playback"]
                    ?.jsonObject
                    ?: return false

            val keyParts =
                playbackData["key_parts"]
                    ?.jsonArray
                    ?: return false

            val iv =
                playbackData["iv"]
                    ?.jsonPrimitive
                    ?.content
                    ?: return false

            val payload =
                playbackData["payload"]
                    ?.jsonPrimitive
                    ?.content
                    ?: return false

            // ============================================================
            // AES KEY REAL
            // ============================================================

            val key = buildString {

                keyParts.forEach {

                    append(
                        it.jsonPrimitive.content
                    )
                }
            }

            val keyBytes =
                Base64.decode(
                    key,
                    Base64.URL_SAFE or
                            Base64.NO_WRAP or
                            Base64.NO_PADDING
                )

            val ivBytes =
                Base64.decode(
                    iv,
                    Base64.URL_SAFE or
                            Base64.NO_WRAP or
                            Base64.NO_PADDING
                )

            val payloadBytes =
                Base64.decode(
                    payload,
                    Base64.URL_SAFE or
                            Base64.NO_WRAP or
                            Base64.NO_PADDING
                )

            println("KEY SIZE => ${keyBytes.size}")

            // ============================================================
            // AES GCM DECRYPT
            // ============================================================

            val cipher =
                Cipher.getInstance(
                    "AES/GCM/NoPadding"
                )

            val secretKey =
                SecretKeySpec(
                    keyBytes,
                    "AES"
                )

            val spec =
                GCMParameterSpec(
                    128,
                    ivBytes
                )

            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey,
                spec
            )

            val decryptedBytes =
                cipher.doFinal(payloadBytes)

            val decrypted =
                decryptedBytes.toString(
                    Charsets.UTF_8
                )

            println("DECRYPTED => $decrypted")

            // ============================================================
            // JSON PARSE
            // ============================================================

            val decryptedJson =
                json.parseToJsonElement(
                    decrypted
                ).jsonObject

            // ============================================================
            // SOURCES ARRAY
            // ============================================================

            val sources =
                decryptedJson["sources"]
                    ?.jsonArray

            if (sources != null) {

                sources.forEach { source ->

                    val obj =
                        source.jsonObject

                    val file =
                        obj["file"]
                            ?.jsonPrimitive
                            ?.content

                            ?: obj["url"]
                                ?.jsonPrimitive
                                ?.content

                            ?: return@forEach

                    val quality =
                        obj["height"]
                            ?.jsonPrimitive
                            ?.content

                            ?: "1080"

                    println("SOURCE => $file")

                    callback.invoke(
                        newExtractorLink(
                            source = "Filemoon",
                            name = "Filemoon ${quality}p",
                            url = file
                        ) {

                            this.referer = base

                            this.quality =
                                getQualityFromName(
                                    "${quality}p"
                                )

                            this.type =
                                INFER_TYPE
                                    ?: ExtractorLinkType.M3U8
                        }
                    )
                }

                return true
            }

            // ============================================================
            // HLS FALLBACK
            // ============================================================

            val hls =
                decryptedJson["hls"]
                    ?.jsonPrimitive
                    ?.content

            if (hls != null) {

                println("HLS => $hls")

                callback.invoke(
                    newExtractorLink(
                        source = "Filemoon",
                        name = "Filemoon HLS",
                        url = hls
                    ) {

                        this.referer = base

                        this.type =
                            ExtractorLinkType.M3U8

                        this.quality =
                            Qualities.P1080.value
                    }
                )

                return true
            }

        } catch (e: Exception) {

            println("FILEMOON ERROR => ${e.message}")

            e.printStackTrace()
        }

        return false
    }
}