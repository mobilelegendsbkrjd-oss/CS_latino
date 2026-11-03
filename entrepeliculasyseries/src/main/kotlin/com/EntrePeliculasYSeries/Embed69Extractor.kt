package com.EntrePeliculasYSeries

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object Embed69Extractor {

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36"

    private const val ONLY_LAT = false
    private const val SOURCE = "EntrePeliculasYSeries"

    suspend fun load(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("Embed69Extractor", "Cargando: $url")

            val fixedUrl = fixHosts(url)
            val basePlayer = deriveBasePlayer(fixedUrl)

            val html = app.get(
                fixedUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer
                )
            ).text

            // =========================
            // 0. NUEVO: desempaquetar con JsUnpacker y sacar la URL del HLS
            // =========================
            try {
                val unpacked = JsUnpacker(html).unpack() ?: html
                val origin = basePlayer
                Log.d("Embed69Extractor", "unpacked=${unpacked.length}")

                // Buscar el objeto n con "1h" (URL ofuscada)
                val nMatch = Regex("""n\s*=\s*\{\s*"1h"\s*:\s*"([^"]+)"""")
                    .find(unpacked)?.groupValues?.get(1)
                    ?: Regex("""n\s*=\s*\{\s*"1h"\s*:\s*"([^"]+)"""")
                        .find(html)?.groupValues?.get(1)

                if (!nMatch.isNullOrBlank()) {
                    val clean = nMatch.replace("\\/", "/")
                    Log.d("Embed69Extractor", "URL desde n.1h: $clean")
                    if (clean.contains(".m3u8")) {
                        emitHls(clean, "$origin/", callback)
                        return
                    }
                }

                // Fallback: cualquier .m3u8 en el texto desempaquetado
                val m3u8 = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(unpacked)?.value
                if (!m3u8.isNullOrBlank()) {
                    val clean = m3u8.replace("\\/", "/")
                    Log.d("Embed69Extractor", "HLS genérico: $clean")
                    emitHls(clean, "$origin/", callback)
                    return
                }

                // Fallback: MP4 directo en el texto desempaquetado
                val mp4 = Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*""").find(unpacked)?.value
                if (!mp4.isNullOrBlank()) {
                    val clean = mp4.replace("\\/", "/")
                    Log.d("Embed69Extractor", "MP4 directo: $clean")
                    val link = newExtractorLink(
                        source = SOURCE,
                        name = "Embed69",
                        url = clean,
                        type = ExtractorLinkType.VIDEO
                    )
                    link.referer = "$origin/"
                    callback.invoke(link)
                    return
                }
            } catch (e: Exception) {
                Log.e("Embed69Extractor", "Error morencius: ${e.message}")
            }

            // =========================
            // 1. TOKEN + s.php (solo si el host parece Embed69 real)
            // =========================
            val host = runCatching { URI(fixedUrl).host?.lowercase().orEmpty() }.getOrDefault("")
            val looksLikeEmbed69 = host.contains("embed69") || host.contains("pelisserieshoy") || host.contains("player.")
            val token = if (looksLikeEmbed69) {
                Regex("""['"]([a-f0-9]{32})['"]""").find(html)?.groupValues?.getOrNull(1)
            } else null

            if (!token.isNullOrBlank()) {
                Log.d("Embed69Extractor", "Token encontrado: $token")
                try {
                    app.post(
                        "$basePlayer/s.php",
                        data = mapOf("a" to "click", "tok" to token),
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to fixedUrl,
                            "Content-Type" to "application/x-www-form-urlencoded"
                        )
                    )

                    val scanResponse = app.post(
                        "$basePlayer/s.php",
                        data = mapOf("a" to "1", "tok" to token),
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to fixedUrl,
                            "Content-Type" to "application/x-www-form-urlencoded"
                        )
                    ).text

                    Log.d("Embed69Extractor", "Scan response: $scanResponse")
                    val scanJson = JSONObject(scanResponse)

                    if (scanJson.has("s")) {
                        val array = scanJson.getJSONArray("s")
                        for (i in 0 until array.length()) {
                            try {
                                val item = array.getJSONArray(i)
                                if (item.length() < 2) continue

                                val serverName = item.getString(0)
                                val id = item.getString(1)

                                val srvLower = serverName.lowercase()
                                if (srvLower.contains("1fichier") ||
                                    srvLower.contains("plustream") ||
                                    srvLower.contains("embedsito") ||
                                    srvLower.contains("disable") ||
                                    srvLower.contains("xupalace") ||
                                    srvLower.contains("uploadfox") ||
                                    srvLower == "download" ||
                                    srvLower == "up2box"
                                ) continue

                                val postResponse = app.post(
                                    "$basePlayer/s.php",
                                    data = mapOf("a" to "2", "v" to id, "tok" to token),
                                    headers = mapOf(
                                        "User-Agent" to USER_AGENT,
                                        "Referer" to fixedUrl,
                                        "Content-Type" to "application/x-www-form-urlencoded"
                                    )
                                ).text

                                val json = JSONObject(postResponse)
                                val realUrl = json.optString("u")
                                val sig = json.optString("sig")
                                if (realUrl.isBlank()) continue

                                val playerUrl =
                                    "$basePlayer/p.php?url=${URLEncoder.encode(realUrl, "UTF-8")}&sig=$sig"

                                val playerHtml = app.get(
                                    playerUrl,
                                    headers = mapOf(
                                        "User-Agent" to USER_AGENT,
                                        "Referer" to fixedUrl
                                    )
                                ).text

                                var found = false

                                Regex("""https?://[^\s"'<>]+?\.m3u8[^\s"'<>]*""")
                                    .findAll(playerHtml)
                                    .forEach { match ->
                                        found = true
                                        val link = newExtractorLink(
                                            source = SOURCE,
                                            name = serverName,
                                            url = match.value,
                                            type = ExtractorLinkType.M3U8
                                        )
                                        link.referer = playerUrl
                                        callback.invoke(link)
                                    }

                                Regex("""https?://[^\s"'<>]+?\.mp4[^\s"'<>]*""")
                                    .findAll(playerHtml)
                                    .forEach { match ->
                                        found = true
                                        val link = newExtractorLink(
                                            source = SOURCE,
                                            name = serverName,
                                            url = match.value,
                                            type = ExtractorLinkType.VIDEO
                                        )
                                        link.referer = playerUrl
                                        callback.invoke(link)
                                    }

                                if (!found) {
                                    loadExtractor(playerUrl, fixedUrl, subtitleCallback, callback)
                                }
                            } catch (e: Exception) {
                                Log.e("Embed69Extractor", "Error servidor: ${e.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("Embed69Extractor", "Error token: ${e.message}")
                }
            }

            // =========================
            // 2. PoW + AES + dataLink
            // =========================
            try {
                val challenge = Regex("""const\s+POW_CHALLENGE\s*=\s*'([^']+)'""")
                    .find(html)?.groupValues?.get(1)
                val difficulty = Regex("""const\s+POW_DIFFICULTY\s*=\s*(\d+)""")
                    .find(html)?.groupValues?.get(1)?.toIntOrNull()
                val salt = Regex("""const\s+POW_SALT\s*=\s*'([^']+)'""")
                    .find(html)?.groupValues?.get(1)

                var aesKey: ByteArray? = null
                if (challenge != null && difficulty != null && salt != null) {
                    Log.d("Embed69Extractor", "Realizando PoW...")
                    aesKey = deriveAesKey(challenge, difficulty, salt)
                    Log.d("Embed69Extractor", "PoW completado.")
                }

                val dataLinkMatch = Regex("""dataLink\s*=\s*(\[.+?\]);""").find(html)
                if (dataLinkMatch != null) {
                    Log.d("Embed69Extractor", "Procesando dataLink nuevo con AES")
                    val arr = JSONArray(dataLinkMatch.groupValues[1])

                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val lang = obj.optString("video_language", "LAT").ifBlank { "LAT" }

                        if (ONLY_LAT && lang != "LAT") {
                            Log.d("Embed69Extractor", "Idioma $lang no permitido (solo LAT). Saltando...")
                            continue
                        }

                        val embedsArr = obj.optJSONArray("sortedEmbeds") ?: continue
                        for (j in 0 until embedsArr.length()) {
                            val e = embedsArr.getJSONObject(j)
                            val servername = e.optString("servername", "").lowercase()
                            val linkEnc = e.optString("link", null) ?: continue

                            if (servername.equals("download", ignoreCase = true)) continue

                            val link = when {
                                linkEnc.split(".").size == 3 -> decodeBase64Link(linkEnc)
                                aesKey != null -> decryptAES(linkEnc, aesKey)
                                else -> null
                            }

                            if (!link.isNullOrBlank()) {
                                val fixedLink = fixHostsLinks(link)
                                val nameWithLang = if (lang.isNotBlank()) "$lang[$servername]" else servername
                                processHostLink(fixedLink, nameWithLang, fixedUrl, subtitleCallback, callback)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("Embed69Extractor", "Error PoW: ${e.message}")
            }

            // =========================
            // 3. Fallback sl_direct
            // =========================
            for (m in Regex("""sl_direct([^"'&<>\s]+)""").findAll(html)) {
                val enc = m.groupValues.getOrNull(1) ?: continue
                try {
                    val decoded = String(Base64.decode(enc, Base64.DEFAULT))
                    val parts = decoded.split("||")
                    if (parts.size < 2) continue

                    val id = parts[0]
                    val originalWeb = parts[1]
                    val origin = originalWeb.substringBefore("/f/")

                    val post = app.post(
                        "$origin/s.php",
                        data = mapOf("a" to "2", "v" to id),
                        headers = mapOf(
                            "Referer" to originalWeb,
                            "Origin" to origin,
                            "User-Agent" to USER_AGENT,
                            "Content-Type" to "application/x-www-form-urlencoded"
                        )
                    ).text

                    val json = JSONObject(post)
                    val realUrl = json.optString("u")
                    val sig = json.optString("sig")
                    if (realUrl.isBlank()) continue

                    val playerUrl =
                        "$basePlayer/p.php?url=${URLEncoder.encode(realUrl, "UTF-8")}&sig=$sig"

                    val playerHtml = app.get(
                        playerUrl,
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to originalWeb
                        )
                    ).text

                    Regex("""https?://[^\s"'<>]+?\.(m3u8|mp4)[^\s"'<>]*""")
                        .findAll(playerHtml)
                        .forEach { match ->
                            val linkUrl = match.value
                            val type = if (linkUrl.contains(".m3u8")) ExtractorLinkType.M3U8
                            else ExtractorLinkType.VIDEO
                            val link = newExtractorLink(
                                source = SOURCE,
                                name = "Embed69",
                                url = linkUrl,
                                type = type
                            )
                            link.referer = playerUrl
                            callback.invoke(link)
                        }
                } catch (_: Exception) {}
            }

            // =========================
            // 4. Fallback dataLink viejo (JWT)
            // =========================
            val oldDataLink = Regex("""dataLink\s*=\s*(\[.*?\]);""")
                .find(html)?.groupValues?.getOrNull(1)
            if (oldDataLink != null) {
                val parsed = AppUtils.tryParseJson<List<Map<String, Any>>>(oldDataLink)
                if (parsed != null) {
                    for (lang in parsed) {
                        val langValue = lang["video_language"] as? String ?: "LAT"
                        if (ONLY_LAT && langValue != "LAT") continue

                        val embeds = lang["sortedEmbeds"] as? List<Map<String, Any>> ?: continue
                        for (embed in embeds) {
                            val enc = embed["link"] as? String ?: continue
                            val realUrl = decodeOldJwt(enc) ?: continue
                            val fixedLink = fixHostsLinks(realUrl)
                            try {
                                loadExtractor(fixedLink, fixedUrl, subtitleCallback, callback)
                            } catch (_: Exception) {}
                        }
                    }
                }
            }

            // =========================
            // 5. Fallback iframes
            // =========================
            val doc = app.get(
                fixedUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer
                )
            ).document
            for (iframe in doc.select("iframe")) {
                val src = iframe.attr("src")
                if (src.startsWith("http")) {
                    try {
                        loadExtractor(src, fixedUrl, subtitleCallback, callback)
                    } catch (_: Exception) {}
                }
            }

        } catch (e: Exception) {
            Log.e("Embed69Extractor", "Error general: ${e.message}")
        }
    }

    // =========================
    // Helpers
    // =========================

    private suspend fun emitHls(
        m3u8Url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val clean = m3u8Url.trim()
        if (!clean.startsWith("http")) return

        val auto = newExtractorLink(
            source = SOURCE,
            name = "Embed69 (Auto)",
            url = clean,
            type = ExtractorLinkType.M3U8
        )
        auto.referer = referer
        callback.invoke(auto)

        try {
            com.lagradost.cloudstream3.utils.M3u8Helper
                .generateM3u8("Embed69", clean, referer)
                .forEach { callback.invoke(it) }
        } catch (_: Exception) {}
    }

    private fun deriveBasePlayer(embedUrl: String): String {
        return try {
            val uri = URI(embedUrl)
            val scheme = uri.scheme ?: "https"
            val host = uri.host ?: "embed69.org"
            "$scheme://$host"
        } catch (_: Exception) {
            "https://embed69.org"
        }
    }

    private suspend fun processHostLink(
        fixedLink: String,
        name: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            when {
                fixedLink.contains(".m3u8") || fixedLink.contains(".mp4") -> {
                    val type = if (fixedLink.contains(".m3u8")) ExtractorLinkType.M3U8
                    else ExtractorLinkType.VIDEO
                    val link = newExtractorLink(
                        source = SOURCE,
                        name = name,
                        url = fixedLink,
                        type = type
                    )
                    link.referer = referer
                    callback.invoke(link)
                }
                else -> {
                    val tempLinks = mutableListOf<ExtractorLink>()
                    loadExtractor(fixedLink, referer, subtitleCallback) { extracted ->
                        tempLinks.add(extracted)
                    }
                    for (extracted in tempLinks) {
                        val link = newExtractorLink(
                            source = SOURCE,
                            name = name,
                            url = extracted.url,
                            type = extracted.type
                        )
                        link.referer = extracted.referer
                        link.headers = extracted.headers
                        link.quality = extracted.quality
                        link.extractorData = extracted.extractorData
                        callback.invoke(link)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun decodeOldJwt(enc: String): String? {
        return try {
            val parts = enc.split(".")
            if (parts.size != 3) return null
            var payload = parts[1]
            val pad = payload.length % 4
            if (pad != 0) payload += "=".repeat(4 - pad)
            val json = String(Base64.decode(payload, Base64.DEFAULT))
            Regex("\"link\":\"(.*?)\"").find(json)?.groupValues?.getOrNull(1)
        } catch (_: Exception) { null }
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
        } catch (_: Exception) { null }
    }

    private fun decodeBase64Link(encryptedLink: String): String? {
        return try {
            val parts = encryptedLink.split(".")
            if (parts.size != 3) return null
            var payload = parts[1]
            if (payload.length % 4 != 0) payload += "=".repeat(4 - payload.length % 4)
            val json = String(Base64.decode(payload, Base64.DEFAULT))
            Regex("\"link\":\"(.*?)\"").find(json)?.groupValues?.get(1)
        } catch (_: Exception) { null }
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

    private fun fixHostsLinks(url: String): String {
        return url
            .replace("https://hglink.to", "https://streamwish.to")
            .replace("https://swdyu.com", "https://streamwish.to")
            .replace("https://cybervynx.com", "https://streamwish.to")
            .replace("https://dumbalag.com", "https://streamwish.to")
            .replace("https://mivalyo.com", "https://vidhidepro.com")
            .replace("https://dinisglows.com", "https://vidhidepro.com")
            .replace("https://dhtpre.com", "https://vidhidepro.com")
            .replace("https://filemoon.link", "https://filemoon.sx")
            .replace("https://sblona.com", "https://watchsb.com")
            .replace("https://lulu.st", "https://lulustream.com")
            .replace("https://uqload.io", "https://uqload.com")
            .replace("https://do7go.com", "https://dood.la")
    }
}