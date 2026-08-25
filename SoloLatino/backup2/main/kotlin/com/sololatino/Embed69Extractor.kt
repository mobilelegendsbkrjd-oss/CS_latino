package com.sololatino

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object Embed69Extractor {

    suspend fun load(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val scriptHtml = app.get(url, referer = referer).document
                .select("script")
                .firstOrNull { it.html().contains("dataLink = [") }
                ?.html()
                ?: return

            val challenge = scriptHtml.substringAfter("const POW_CHALLENGE = '").substringBefore("'")
            val difficulty = scriptHtml.substringAfter("const POW_DIFFICULTY = ").substringBefore(";").toIntOrNull() ?: return
            val salt = scriptHtml.substringAfter("const POW_SALT = '").substringBefore("'")

            if (challenge.isBlank() || salt.isBlank()) return

            val aesKey = deriveAesKey(challenge, difficulty, salt)

            val jsonStr = scriptHtml
                .substringAfter("dataLink = ")
                .substringBefore(";")

            AppUtils.tryParseJson<List<ServersByLang>>(jsonStr)?.amap { lang ->
                val links = lang.sortedEmbeds.amap { embed ->
                    decryptAES(embed.link ?: return@amap null, aesKey)
                }

                links.filterNotNull().amap { link ->
                    loadSourceNameExtractor(
                        lang.videoLanguage ?: "LAT",
                        fixHostsLinks(link),
                        referer,
                        subtitleCallback,
                        callback
                    )
                }
            }
        } catch (_: Exception) {}
    }

    private fun deriveAesKey(challenge: String, difficulty: Int, salt: String): ByteArray {
        val prefix = "0".repeat(difficulty)
        var nonce = 0L
        val md = MessageDigest.getInstance("SHA-256")

        while (true) {
            val hashHex = md.digest((challenge + nonce).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

            if (hashHex.startsWith(prefix)) {
                return md.digest((challenge + nonce + salt).toByteArray(Charsets.UTF_8))
            }
            nonce++
        }
    }

    private fun decryptAES(encryptedBase64: String, aesKey: ByteArray): String? {
        return try {
            val raw = Base64.decode(encryptedBase64, Base64.DEFAULT)
            val iv = raw.copyOfRange(0, 16)
            val ciphertext = raw.copyOfRange(16, raw.size)
            val keyBytes = aesKey.copyOfRange(0, 32)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }
}

data class Server(
    @JsonProperty("servername") val servername: String? = null,
    @JsonProperty("link") val link: String? = null,
    @JsonProperty("type") val type: String? = null,
)

data class ServersByLang(
    @JsonProperty("file_id") val fileId: String? = null,
    @JsonProperty("video_language") val videoLanguage: String? = null,
    @JsonProperty("sortedEmbeds") val sortedEmbeds: List<Server> = emptyList(),
    @JsonProperty("downloadEmbeds") val downloadEmbeds: List<Server> = emptyList(),
)

suspend fun loadSourceNameExtractor(
    source: String,
    url: String,
    referer: String? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    loadExtractor(url, referer, subtitleCallback) { link ->
        CoroutineScope(Dispatchers.IO).launch {
            callback.invoke(
                newExtractorLink(
                    "$source[${link.source}]",
                    "$source[${link.source}]",
                    link.url,
                ) {
                    this.quality = link.quality
                    this.type = link.type
                    this.referer = link.referer
                    this.headers = link.headers
                    this.extractorData = link.extractorData
                }
            )
        }
    }
}

fun fixHostsLinks(url: String): String {
    return url
        .replaceFirst("https://hglink.to", "https://streamwish.to")
        .replaceFirst("https://swdyu.com", "https://streamwish.to")
        .replaceFirst("https://cybervynx.com", "https://streamwish.to")
        .replaceFirst("https://dumbalag.com", "https://streamwish.to")
        .replaceFirst("https://mivalyo.com", "https://vidhidepro.com")
        .replaceFirst("https://dinisglows.com", "https://vidhidepro.com")
        .replaceFirst("https://dhtpre.com", "https://vidhidepro.com")
        .replaceFirst("https://filemoon.link", "https://filemoon.sx")
        .replaceFirst("https://sblona.com", "https://watchsb.com")
        .replaceFirst("https://lulu.st", "https://lulustream.com")
        .replaceFirst("https://uqload.io", "https://uqload.com")
        .replaceFirst("https://do7go.com", "https://dood.la")
}