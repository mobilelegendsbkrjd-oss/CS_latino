package com.tubepelis

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.UUID
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
        "byseqekaho",
        "byse",
        "bysejikuar",
        "bysebuho",
        "bysekoze",
        "bysedikamoum",
        "bysevepoin",
        "bysesayeveum"
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
            val code = Regex("""/(?:e|d|v)/([a-zA-Z0-9_-]+)""")
                .find(url)
                ?.groupValues
                ?.getOrNull(1)
                ?: return false

            val base = url.substringBefore("/e/")
                .substringBefore("/d/")
                .substringBefore("/v/")

            println("FILEMOON URL => $url")
            println("FILEMOON CODE => $code")

            val commonHeaders = mapOf(
                "Origin" to base,
                "Referer" to url,
                "User-Agent" to USER_AGENT,
                "Content-Type" to "application/json",
                "Accept" to "application/json"
            )

            // ============================================================
            // 1. CHALLENGE
            // ============================================================
            val challengeResponse = app.post(
                "$base/api/videos/access/challenge",
                headers = commonHeaders,
                requestBody = "{}".toRequestBody("application/json".toMediaType())
            ).text

            println("CHALLENGE => $challengeResponse")

            val challengeJson = json.parseToJsonElement(challengeResponse).jsonObject

            val challengeId = challengeJson["challenge_id"]
                ?.jsonPrimitive
                ?.content
                ?: return false

            val nonce = challengeJson["nonce"]
                ?.jsonPrimitive
                ?.content
                ?: return false

            // ============================================================
            // 2. GENERATE REAL EC KEYPAIR + SIGN NONCE
            // ============================================================
            val keyGen = KeyPairGenerator.getInstance("EC")
            keyGen.initialize(ECGenParameterSpec("secp256r1"))
            val pair = keyGen.generateKeyPair()

            val publicKey = pair.public as ECPublicKey

            val xBytes = padTo32(publicKey.w.affineX.toByteArray())
            val yBytes = padTo32(publicKey.w.affineY.toByteArray())

            val xB64 = Base64.encodeToString(
                xBytes,
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
            val yB64 = Base64.encodeToString(
                yBytes,
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )

            val signer = Signature.getInstance("SHA256withECDSA")
            signer.initSign(pair.private)
            signer.update(nonce.toByteArray(Charsets.UTF_8))

            val signature = Base64.encodeToString(
                signer.sign(),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )

            val viewerId = UUID.randomUUID().toString().replace("-", "")
            val deviceId = UUID.randomUUID().toString().replace("-", "")

            val attestJson = buildJsonObject {
                put("viewer_id", viewerId)
                put("device_id", deviceId)
                put("challenge_id", challengeId)
                put("nonce", nonce)
                put("signature", signature)
                putJsonObject("public_key") {
                    put("kty", "EC")
                    put("crv", "P-256")
                    put("x", xB64)
                    put("y", yB64)
                }
                putJsonObject("client") {
                    put("user_agent", USER_AGENT)
                }
                putJsonObject("storage") { }
                putJsonObject("attributes") {
                    put("entropy", "low")
                }
            }

            println("ATTEST PAYLOAD => $attestJson")

            // ============================================================
            // 3. ATTEST
            // ============================================================
            val attestResponse = app.post(
                "$base/api/videos/access/attest",
                headers = commonHeaders,
                requestBody = attestJson.toString()
                    .toRequestBody("application/json".toMediaType())
            ).text

            println("ATTEST RESPONSE => $attestResponse")

            val attestResult = json.parseToJsonElement(attestResponse).jsonObject

            val token = attestResult["token"]
                ?.jsonPrimitive
                ?.content
                ?: return false

            println("TOKEN => $token")

            // ============================================================
            // 4. PLAYBACK
            // ============================================================
            val playbackHeaders = commonHeaders + mapOf(
                "Authorization" to "Bearer $token",
                "X-Access-Token" to token
            )

            val playback = app.post(
                "$base/api/videos/$code/embed/playback",
                headers = playbackHeaders,
                requestBody = "{}".toRequestBody("application/json".toMediaType())
            ).text

            println("PLAYBACK => $playback")

            val playbackJson = json.parseToJsonElement(playback).jsonObject

            // A veces viene anidado en "playback", a veces plano
            val playbackData = playbackJson["playback"]?.jsonObject ?: playbackJson

            val keyParts = playbackData["key_parts"]?.jsonArray ?: return false
            val iv = playbackData["iv"]?.jsonPrimitive?.content ?: return false
            val payload = playbackData["payload"]?.jsonPrimitive?.content ?: return false

            // ============================================================
            // 5. AES-GCM DECRYPT
            // ============================================================
            val key = buildString {
                keyParts.forEach {
                    append(it.jsonPrimitive.content)
                }
            }

            val keyBytes = Base64.decode(
                key,
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
            val ivBytes = Base64.decode(
                iv,
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
            val payloadBytes = Base64.decode(
                payload,
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )

            println("KEY SIZE => ${keyBytes.size}")

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val secretKey = SecretKeySpec(keyBytes, "AES")
            val spec = GCMParameterSpec(128, ivBytes)

            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

            val decryptedBytes = cipher.doFinal(payloadBytes)
            val decrypted = decryptedBytes.toString(Charsets.UTF_8)

            println("DECRYPTED => $decrypted")

            val decryptedJson = json.parseToJsonElement(decrypted).jsonObject

            // ============================================================
            // 6. SOURCES
            // ============================================================
            val sources = decryptedJson["sources"]?.jsonArray

            if (sources != null) {
                sources.forEach { source ->
                    val obj = source.jsonObject

                    val file = obj["file"]?.jsonPrimitive?.content
                        ?: obj["url"]?.jsonPrimitive?.content
                        ?: return@forEach

                    val quality = obj["height"]?.jsonPrimitive?.content ?: "1080"

                    println("SOURCE => $file")

                    callback.invoke(
                        newExtractorLink(
                            source = "Filemoon",
                            name = "Filemoon ${quality}p",
                            url = file
                        ) {
                            this.referer = base
                            this.quality = getQualityFromName("${quality}p")
                            this.type = ExtractorLinkType.M3U8
                        }
                    )
                }
                return true
            }

            // HLS fallback
            val hls = decryptedJson["hls"]?.jsonPrimitive?.content
            if (hls != null) {
                println("HLS => $hls")

                callback.invoke(
                    newExtractorLink(
                        source = "Filemoon",
                        name = "Filemoon HLS",
                        url = hls
                    ) {
                        this.referer = base
                        this.type = ExtractorLinkType.M3U8
                        this.quality = Qualities.P1080.value
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

    /** BigInteger puede venir con byte de signo extra, hay que dejarlo en 32 bytes */
    private fun padTo32(bytes: ByteArray): ByteArray {
        return when {
            bytes.size == 32 -> bytes
            bytes.size > 32 -> bytes.copyOfRange(bytes.size - 32, bytes.size)
            else -> ByteArray(32 - bytes.size) + bytes
        }
    }
}