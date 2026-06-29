package com.megadede

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import java.net.URI
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object Embed69Extractor {

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"

    private val preferredLangs = listOf("LAT", "ESP", "SUB", "VOSE")

    private val serverPriority = listOf(
        "rapidvideo",
        "filemoon",
        "streamwish",
        "vidhide",
        "stape",
        "waaw",
        "doodstream",
        "voe"
    )

    suspend fun load(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val fixedUrl = fixUrl(url, referer)

            if (fixedUrl.contains("xupalace.org", true)) {
                loadXupalace(
                    fixedUrl,
                    referer,
                    subtitleCallback,
                    callback
                )
                return
            }

            val html = app.get(
                fixedUrl,
                referer = referer,
                headers = browserHeaders(referer)
            ).text

            val challenge = Regex("""POW_CHALLENGE\s*=\s*['"]([^'"]+)['"]""")
                .find(html)
                ?.groupValues
                ?.getOrNull(1)

            val difficulty = Regex("""POW_DIFFICULTY\s*=\s*(\d+)""")
                .find(html)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull() ?: 0

            val salt = Regex("""POW_SALT\s*=\s*['"]([^'"]+)['"]""")
                .find(html)
                ?.groupValues
                ?.getOrNull(1)

            val dataLinkRaw = Regex("""let\s+dataLink\s*=\s*(\[[\s\S]*?]);""")
                .find(html)
                ?.groupValues
                ?.getOrNull(1)

            if (challenge.isNullOrBlank() || salt.isNullOrBlank() || dataLinkRaw.isNullOrBlank()) {
                fallbackIframes(
                    html,
                    fixedUrl,
                    subtitleCallback,
                    callback
                )
                return
            }

            val aesKey = solvePowKey(challenge, difficulty, salt) ?: return
            val arr = JSONArray(dataLinkRaw)

            val langItems = (0 until arr.length())
                .mapNotNull { arr.optJSONObject(it) }
                .sortedBy {
                    val lang = it.optString("video_language").uppercase()
                    preferredLangs.indexOf(lang).let { pos ->
                        if (pos == -1) 99 else pos
                    }
                }

            val selectedLangs = langItems.filter {
                it.optString("video_language").equals("LAT", true)
            }.ifEmpty {
                langItems.take(1)
            }

            val sentLinks = mutableSetOf<String>()

            for (langObj in selectedLangs) {
                val lang = langObj.optString("video_language")
                    .uppercase()
                    .ifBlank { "LAT" }

                val embedsRaw = langObj.optJSONArray("sortedEmbeds") ?: continue

                val embeds = (0 until embedsRaw.length())
                    .mapNotNull { embedsRaw.optJSONObject(it) }
                    .sortedBy {
                        val server = it.optString("servername").lowercase()
                        serverPriority.indexOf(server).let { pos ->
                            if (pos == -1) 99 else pos
                        }
                    }
                    .take(3)

                for (embed in embeds) {
                    val serverRaw = embed.optString("servername", "Embed69")
                    val encrypted = embed.optString("link")

                    if (encrypted.isBlank()) continue

                    val realUrl = decryptAesCbc(encrypted, aesKey) ?: continue
                    val finalUrl = fixHosts(realUrl.trim().replace("`", ""))

                    if (finalUrl.isBlank() || !sentLinks.add(finalUrl)) continue

                    try {
                        if (finalUrl.contains("xupalace.org", true)) {
                            loadXupalace(
                                finalUrl,
                                fixedUrl,
                                subtitleCallback,
                                callback
                            )
                        } else {
                            loadExtractor(
                                finalUrl,
                                fixedUrl,
                                subtitleCallback,
                                callback
                            )
                        }
                    } catch (_: Exception) {
                    }
                }
            }

        } catch (_: Exception) {
        }
    }

    private suspend fun loadXupalace(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            XupalaceExtractor().getUrl(
                url = url,
                referer = referer,
                subtitleCallback = subtitleCallback,
                callback = callback
            )
        } catch (_: Exception) {
        }
    }

    private fun solvePowKey(
        challenge: String,
        difficulty: Int,
        salt: String
    ): ByteArray? {
        return try {
            val prefix = "0".repeat(difficulty)
            var nonce = 0

            while (nonce < 20_000_000) {
                val hash = sha256Hex(challenge + nonce)

                if (hash.startsWith(prefix)) {
                    return sha256Bytes(challenge + nonce + salt)
                }

                nonce++
            }

            null
        } catch (_: Exception) {
            null
        }
    }

    private fun decryptAesCbc(
        encryptedBase64: String,
        aesKey: ByteArray
    ): String? {
        return try {
            val raw = Base64.decode(encryptedBase64, Base64.DEFAULT)
            if (raw.size <= 16) return null

            val iv = raw.copyOfRange(0, 16)
            val cipherText = raw.copyOfRange(16, raw.size)
            val key = aesKey.copyOfRange(0, 32)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(iv)
            )

            String(cipher.doFinal(cipherText), Charsets.UTF_8)

        } catch (_: Exception) {
            null
        }
    }

    private fun sha256Hex(input: String): String {
        return sha256Bytes(input).joinToString("") {
            "%02x".format(it)
        }
    }

    private fun sha256Bytes(input: String): ByteArray {
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
    }

    private suspend fun fallbackIframes(
        html: String,
        fixedUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Regex("""<iframe[^>]+(?:src|data-src)=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { fixUrl(it, fixedUrl) }
            .forEach { src ->
                try {
                    if (src.contains("xupalace.org", true)) {
                        loadXupalace(
                            src,
                            fixedUrl,
                            subtitleCallback,
                            callback
                        )
                    } else {
                        loadExtractor(
                            src,
                            fixedUrl,
                            subtitleCallback,
                            callback
                        )
                    }
                } catch (_: Exception) {
                }
            }
    }

    private fun browserHeaders(referer: String): Map<String, String> {
        return mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to referer,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "es-419,es;q=0.9",
            "Connection" to "keep-alive"
        )
    }

    private fun fixUrl(url: String, baseUrl: String): String {
        val clean = url.trim()
            .replace("\\/", "/")
            .replace("&amp;", "&")

        return try {
            when {
                clean.startsWith("//") -> "https:$clean"
                clean.startsWith("http") -> clean
                else -> URI(baseUrl).resolve(clean).toString()
            }
        } catch (_: Exception) {
            clean
        }
    }

    private fun fixHosts(url: String): String {
        return url
            .replace("hglink.to", "streamwish.to")
            .replace("swdyu.com", "streamwish.to")
            .replace("wishembed.com", "streamwish.to")
            .replace("vidhide.com", "vidhidepro.com")
            .replace("filemoon.link", "filemoon.sx")
            .replace("doodstream.com", "dood.la")
            .replace("voe.sx", "voe.unblockit.cat")
    }
}