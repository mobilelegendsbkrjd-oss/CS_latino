package com.locopelis

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

open class ByseExtractor : ExtractorApi() {
    override var name = "Byse"
    override var mainUrl = "https://byse.sx"
    override val requiresReferer = true

    companion object {
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:144.0) Gecko/20100101 Firefox/144.0"
        private const val BUFFER_SIZE  = 512
        private const val BUFFER_MASK  = 511
        private const val INIT_CONST   = -1640531535
        private const val FINAL_CONST  = -2048144777
        private const val MAX_POW_ITER = 2_000_000
        private val ID_REGEX = Regex("""/(?:e|d|v)/([^/?#]+)""")
    }

    private fun baseOf(url: String): String = url.split("/").take(3).joinToString("/")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val videoId = ID_REGEX.find(url)?.groupValues?.get(1)
            ?: url.substringBefore("?").trimEnd('/').substringAfterLast('/')
        if (videoId.isEmpty()) return

        val siteBase = baseOf(url)

        val embedUrl = app.get(
            "$siteBase/api/videos/$videoId/embed/details",
            referer = referer ?: "$siteBase/"
        ).parsedSafe<DetailsRoot>()?.embedFrameUrl ?: return

        val embedBase = baseOf(embedUrl)
        val embedCode = embedUrl.trimEnd('/').substringAfterLast('/').substringBefore("?")

        val playback = challengeFlow(siteBase, embedBase, embedCode, url) ?: return
        val sources = decryptPlayback(playback) ?: return
        if (sources.isEmpty()) return

        val headers = mapOf("Referer" to "$embedBase/", "User-Agent" to UA)

        sources.mapNotNull { it.url }.distinct().forEach { videoUrl ->
            if (videoUrl.contains(".m3u8")) {
                generateM3u8(name, videoUrl, "$embedBase/", headers = headers).forEach(callback)
            } else {
                callback.invoke(
                    newExtractorLink(this.name, this.name, videoUrl, type = INFER_TYPE) {
                        this.referer = "$embedBase/"
                        this.quality = sources.firstOrNull { it.url == videoUrl }?.height
                            ?: Qualities.Unknown.value
                        this.headers = headers
                    }
                )
            }
        }
    }

    private suspend fun challengeFlow(
        siteBase: String,
        embedBase: String,
        embedCode: String,
        parentUrl: String
    ): Playback? {
        val siteHost = siteBase.substringAfter("://").trimEnd('/')

        val apiHeaders = mapOf(
            "User-Agent"      to UA,
            "Accept"          to "*/*",
            "Accept-Language" to "en-US,en;q=0.5",
            "Referer"         to "$siteBase/",
            "Origin"          to embedBase
        )

        val challenge = app.post(
            "$embedBase/api/videos/access/challenge",
            headers = apiHeaders,
            json = emptyMap<String, String>()
        ).parsedSafe<ChallengeRoot>()

        if (challenge?.nonce == null || challenge.challengeId == null) return null

        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(challenge.nonce.toByteArray())
            b64UrlEncode(sign())
        }

        val point = (keyPair.public as ECPublicKey).w
        val publicKeyJwk = mapOf(
            "kty" to "EC",
            "crv" to "P-256",
            "x"   to b64UrlEncode(point.affineX.to32Bytes()),
            "y"   to b64UrlEncode(point.affineY.to32Bytes())
        )

        val attest = app.post(
            "$embedBase/api/videos/access/attest",
            headers = apiHeaders,
            json = mapOf(
                "viewer_id"    to "",
                "device_id"    to "",
                "challenge_id" to challenge.challengeId,
                "nonce"        to challenge.nonce,
                "signature"    to signature,
                "public_key"   to publicKeyJwk,
                "client"       to clientFingerprint(),
                "storage"      to emptyMap<String, String>(),
                "attributes"   to mapOf("entropy" to "low")
            )
        ).parsedSafe<AttestRoot>()

        if (attest?.token == null) return null

        val fingerprint = mapOf(
            "fingerprint" to mapOf(
                "token"      to attest.token,
                "viewer_id"  to attest.viewerId.orEmpty(),
                "device_id"  to attest.deviceId.orEmpty(),
                "confidence" to (attest.confidence ?: 0.5)
            )
        )

        val embedHeaders = apiHeaders + mapOf(
            "X-Embed-Origin"  to siteHost,
            "X-Embed-Referer" to "$siteBase/",
            "X-Embed-Parent"  to parentUrl,
            "Cookie"          to "byse_viewer_id=${attest.viewerId}; byse_device_id=${attest.deviceId}"
        )

        val captcha = app.post(
            "$embedBase/api/videos/$embedCode/embed/captcha",
            headers = embedHeaders,
            json = fingerprint
        ).parsedSafe<CaptchaRoot>()

        if (captcha?.powNonce == null || captcha.powToken == null) return null

        val difficulty = captcha.powDifficulty ?: 16
        val solution = solvePow(captcha.powNonce, difficulty) ?: return null

        val verify = app.post(
            "$embedBase/api/videos/$embedCode/embed/captcha/verify",
            headers = embedHeaders,
            json = mapOf(
                "pow_token"   to captcha.powToken,
                "solution"    to solution,
                "fingerprint" to fingerprint["fingerprint"]
            )
        ).parsedSafe<VerifyRoot>()

        if (verify?.status != "ok" || verify.token == null) return null

        return app.post(
            "$embedBase/api/videos/$embedCode/embed/playback",
            headers = embedHeaders + mapOf("X-Captcha-Token" to verify.token),
            json = fingerprint
        ).parsedSafe<PlaybackRoot>()?.playback
    }

    private fun clientFingerprint(): Map<String, Any> {
        val widths  = listOf(1920, 2560, 1366, 1440, 1680)
        val heights = listOf(1080, 1440, 768, 900, 1050)
        val index   = Random.nextInt(widths.size)

        fun randomHash(): String = b64UrlEncode(Random.nextBytes(32))

        return mapOf(
            "user_agent"           to UA,
            "pixel_ratio"          to 1,
            "screen_width"         to widths[index],
            "screen_height"        to heights[index],
            "color_depth"          to 24,
            "languages"            to listOf("tr", "en-US", "en"),
            "timezone"             to "Europe/Istanbul",
            "hardware_concurrency" to listOf(4, 8, 12, 16).random(),
            "touch_points"         to 0,
            "webgl_vendor"         to "Intel",
            "webgl_renderer"       to "ANGLE (Intel, Intel(R) UHD Graphics 630, OpenGL 4.5)",
            "canvas_hash"          to randomHash(),
            "audio_hash"           to randomHash(),
            "webgl_params_hash"    to randomHash(),
            "fonts_hash"           to randomHash(),
            "codecs_hash"          to randomHash(),
            "media_devices"        to "ai0ao0vi0",
            "pointer_type"         to "fine,hover",
            "extra"                to mapOf("vendor" to "", "appVersion" to "5.0 (Windows)")
        )
    }

    private fun powHash(input: ByteArray): Int {
        var s0 = 1779033703
        var s1 = -1150833019
        var s2 = 1013904242
        var s3 = -1521486534

        fun quarterRound() {
            s0 += s1; s3 = Integer.rotateLeft(s3 xor s0, 16)
            s2 += s3; s1 = Integer.rotateLeft(s1 xor s2, 12)
            s0 += s1; s3 = Integer.rotateLeft(s3 xor s0, 8)
            s2 += s3; s1 = Integer.rotateLeft(s1 xor s2, 7)
        }

        for (byte in input) {
            s0 = Integer.rotateLeft(s0 + (byte.toInt() and 0xFF), 7)
            quarterRound()
        }
        repeat(8) { quarterRound() }

        val buffer = IntArray(BUFFER_SIZE)
        for (i in 0 until BUFFER_SIZE) {
            quarterRound()
            buffer[i] = s0 xor s2
        }

        repeat(2) {
            for (i in 0 until BUFFER_SIZE) {
                var mixed = buffer[i] + buffer[buffer[i] and BUFFER_MASK]
                mixed = Integer.rotateLeft(mixed, 13)
                mixed = mixed xor (buffer[(i + 1) and BUFFER_MASK] * INIT_CONST)
                buffer[i] = mixed
                s0 = s0 xor mixed
                quarterRound()
            }
        }
        quarterRound()

        var out = s0
        for (i in 0 until 64) {
            val value = buffer[i]
            out = Integer.rotateLeft(out + value, 5) xor (value * FINAL_CONST)
        }
        return out xor s2
    }

    private fun solvePow(nonce: String, difficulty: Int): String? {
        val prefix = "$nonce:".toByteArray(Charsets.ISO_8859_1)
        for (counter in 0 until MAX_POW_ITER) {
            val input = prefix + counter.toString().toByteArray(Charsets.ISO_8859_1)
            if (Integer.numberOfLeadingZeros(powHash(input)) >= difficulty) return counter.toString()
        }
        return null
    }

    private fun decryptPlayback(playback: Playback): List<PlaybackSource>? {
        val keyParts = playback.keyParts
        if (keyParts.isNullOrEmpty() || playback.iv.isNullOrEmpty() || playback.payload.isNullOrEmpty()) return null

        return try {
            val decoded  = keyParts.map { b64UrlDecode(it) }
            val real     = decoded.filter { it.size == 16 }.take(2)
            val keyBytes = if (real.size == 2) real[0] + real[1]
            else decoded.take(2).fold(ByteArray(0)) { acc, part -> acc + part }

            val encrypted = b64UrlDecode(playback.payload)
            val cipher    = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                GCMParameterSpec(128, b64UrlDecode(playback.iv))
            )

            val json = String(cipher.doFinal(encrypted), Charsets.UTF_8).removePrefix("\uFEFF")
            tryParseJson<PlaybackDecrypt>(json)?.sources
        } catch (e: Exception) {
            null
        }
    }

    private fun b64UrlDecode(data: String): ByteArray {
        val fixed   = data.replace('-', '+').replace('_', '/')
        val padding = (4 - fixed.length % 4) % 4
        return Base64.decode(fixed + "=".repeat(padding), Base64.DEFAULT)
    }

    private fun b64UrlEncode(data: ByteArray): String =
        Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun BigInteger.to32Bytes(): ByteArray {
        val raw = this.toByteArray()
        val out = ByteArray(32)
        if (raw.size >= 32) {
            System.arraycopy(raw, raw.size - 32, out, 0, 32)
        } else {
            System.arraycopy(raw, 0, out, 32 - raw.size, raw.size)
        }
        return out
    }

    data class DetailsRoot(
        @JsonProperty("embed_frame_url") val embedFrameUrl: String? = null
    )

    data class ChallengeRoot(
        @JsonProperty("challenge_id") val challengeId: String? = null,
        @JsonProperty("nonce")        val nonce: String? = null
    )

    data class AttestRoot(
        @JsonProperty("token")      val token: String? = null,
        @JsonProperty("viewer_id")  val viewerId: String? = null,
        @JsonProperty("device_id")  val deviceId: String? = null,
        @JsonProperty("confidence") val confidence: Double? = null
    )

    data class CaptchaRoot(
        @JsonProperty("pow_nonce")      val powNonce: String? = null,
        @JsonProperty("pow_difficulty") val powDifficulty: Int? = null,
        @JsonProperty("pow_token")      val powToken: String? = null
    )

    data class VerifyRoot(
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("token")  val token: String? = null
    )

    data class PlaybackRoot(
        @JsonProperty("playback") val playback: Playback? = null
    )

    data class Playback(
        @JsonProperty("iv")        val iv: String? = null,
        @JsonProperty("payload")   val payload: String? = null,
        @JsonProperty("key_parts") val keyParts: List<String>? = null
    )

    data class PlaybackDecrypt(
        @JsonProperty("sources") val sources: List<PlaybackSource>? = null
    )

    data class PlaybackSource(
        @JsonProperty("label")  val label: String? = null,
        @JsonProperty("url")    val url: String? = null,
        @JsonProperty("height") val height: Int? = null
    )
}