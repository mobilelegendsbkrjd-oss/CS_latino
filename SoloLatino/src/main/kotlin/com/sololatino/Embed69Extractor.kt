package com.sololatino

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

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
            Log.d("Embed69Extractor", "Cargando: $url")

            val fixedUrl = fixHosts(url)

            val html = app.get(
                fixedUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer
                )
            ).text

            // =========================
            // 1. TOKEN NUEVO + SERVIDORES (Método principal)
            // =========================
            val token = Regex("""['"]([a-f0-9]{32})['"]""")
                .find(html)
                ?.groupValues
                ?.getOrNull(1)

            if (!token.isNullOrBlank()) {
                Log.d("Embed69Extractor", "Token encontrado: $token")

                try {
                    // Click fake
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

                    // Scan servers
                    val scanResponse = app.post(
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

                                Log.d("Embed69Extractor", "Procesando servidor: $serverName (ID: $id)")

                                val srvLower = serverName.lowercase()
                                if (srvLower.contains("1fichier") ||
                                    srvLower.contains("plustream") ||
                                    srvLower.contains("embedsito") ||
                                    srvLower.contains("disable") ||
                                    srvLower.contains("xupalace") ||
                                    srvLower.contains("uploadfox") ||
                                    srvLower == "download" ||
                                    srvLower == "up2box") {
                                    continue
                                }

                                // Resolve server
                                val postResponse = app.post(
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

                                val json = JSONObject(postResponse)
                                val realUrl = json.optString("u")
                                val sig = json.optString("sig")

                                if (realUrl.isBlank()) continue

                                val playerUrl = "$BASE_PLAYER/p.php?url=${URLEncoder.encode(realUrl, "UTF-8")}&sig=$sig"

                                val playerHtml = app.get(
                                    playerUrl,
                                    headers = mapOf(
                                        "User-Agent" to USER_AGENT,
                                        "Referer" to fixedUrl
                                    )
                                ).text

                                var found = false

                                // M3U8
                                Regex("""https?://[^\s"'<>]+?\.m3u8[^\s"'<>]*""")
                                    .findAll(playerHtml)
                                    .forEach { match ->
                                        found = true
                                        val link = match.value
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

                                // MP4
                                Regex("""https?://[^\s"'<>]+?\.mp4[^\s"'<>]*""")
                                    .findAll(playerHtml)
                                    .forEach { match ->
                                        found = true
                                        val link = match.value
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

                                if (!found) {
                                    loadExtractor(playerUrl, fixedUrl, subtitleCallback, callback)
                                }

                            } catch (e: Exception) {
                                Log.e("Embed69Extractor", "Error procesando servidor: ${e.message}")
                            }
                        }
                    }

                } catch (e: Exception) {
                    Log.e("Embed69Extractor", "Error en método de token: ${e.message}")
                }
            }

            // =========================
            // 2. MÉTODO NUEVO: POW + AES + DATALINK (CON FILTRO LAT)
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

                    val arr = org.json.JSONArray(dataLinkMatch.groupValues[1])

                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val lang = obj.optString("video_language", "LAT").ifBlank { "LAT" }

                        // ============================================
                        // FILTRO: SOLO PROCESAR SI ES LAT
                        // ============================================
                        if (lang != "LAT") {
                            Log.d("Embed69Extractor", "❌ Idioma $lang no permitido (solo LAT). Saltando...")
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
                                Log.d("Embed69Extractor", "Link descifrado: $link para $servername")
                                val fixedLink = fixHostsLinks(link)
                                val nameWithLang = "$lang[$servername]"

                                when {
                                    // MINOCHINOS - Usar el extractor V2 con idioma
                                    fixedLink.contains("minochinos") -> {
                                        Log.d("Embed69Extractor", "Usando MinochinosExtractorV2 para: $fixedLink (Idioma: $lang)")
                                        MinochinosExtractorV2().withLanguage(lang).getUrl(fixedLink, fixedUrl, subtitleCallback, callback)
                                    }
                                    // DOOD
                                    fixedLink.contains("dood") || fixedLink.contains("do7go") || fixedLink.contains("dood.la") -> {
                                        Log.d("Embed69Extractor", "Usando DoodExtractor para: $fixedLink")
                                        DoodExtractor().getUrl(fixedLink, fixedUrl, subtitleCallback, callback)
                                    }
                                    // F75S
                                    fixedLink.contains("f75s") -> {
                                        Log.d("Embed69Extractor", "Usando F75s para: $fixedLink")
                                        F75s().getUrl(fixedLink, fixedUrl, subtitleCallback, callback)
                                    }
                                    // PLAYHYDRAX
                                    fixedLink.contains("playhydrax") || fixedLink.contains("abyssplayer") -> {
                                        Log.d("Embed69Extractor", "Usando PlayHydrax para: $fixedLink")
                                        PlayHydrax().getUrl(fixedLink, fixedUrl, subtitleCallback, callback)
                                    }
                                    // Enlace directo M3U8/MP4
                                    fixedLink.contains(".m3u8") || fixedLink.contains(".mp4") -> {
                                        Log.d("Embed69Extractor", "Enlace directo: $fixedLink")
                                        callback.invoke(
                                            newExtractorLink(
                                                source = "SoloLatino",
                                                name = nameWithLang,
                                                url = fixedLink,
                                                type = if (fixedLink.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                            ) {
                                                this.referer = fixedUrl
                                            }
                                        )
                                    }
                                    // CUALQUIER OTRO - Usar loadExtractor con idioma
                                    else -> {
                                        Log.d("Embed69Extractor", "Cargando con loadExtractor: $fixedLink")
                                        loadExtractor(fixedLink, fixedUrl, subtitleCallback) { extractedLink ->
                                            val newLink = ExtractorLink(
                                                source = "SoloLatino",
                                                name = nameWithLang,
                                                url = extractedLink.url,
                                                type = extractedLink.type,
                                                quality = extractedLink.quality,
                                                referer = extractedLink.referer,
                                                headers = extractedLink.headers,
                                                extractorData = extractedLink.extractorData
                                            )
                                            callback.invoke(newLink)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("Embed69Extractor", "Error en método POW: ${e.message}")
            }

            // =========================
            // 3. FALLBACK SL_DIRECT (Conservado)
            // =========================
            Regex("""sl_direct([^"'&<>\s]+)""")
                .findAll(html)
                .mapNotNull { it.groupValues.getOrNull(1) }
                .distinct()
                .forEach { enc ->
                    try {
                        val decoded = String(Base64.decode(enc, Base64.DEFAULT))
                        val parts = decoded.split("||")

                        if (parts.size < 2) return@forEach

                        val id = parts[0]
                        val originalWeb = parts[1]
                        val origin = originalWeb.substringBefore("/f/")

                        val post = app.post(
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

                        val json = JSONObject(post)
                        val realUrl = json.optString("u")
                        val sig = json.optString("sig")

                        if (realUrl.isBlank()) return@forEach

                        val playerUrl = "$BASE_PLAYER/p.php?url=${URLEncoder.encode(realUrl, "UTF-8")}&sig=$sig"

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
                                val link = match.value
                                val type = if (link.contains(".m3u8")) {
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
            // 4. FALLBACK DATALINK VIEJO (CON FILTRO LAT)
            // =========================
            Regex("""dataLink\s*=\s*(\[.*?\]);""")
                .find(html)
                ?.groupValues
                ?.getOrNull(1)
                ?.let { jsonStr ->
                    AppUtils.tryParseJson<List<Map<String, Any>>>(jsonStr)
                        ?.forEach { lang ->
                            // ============================================
                            // FILTRO: SOLO PROCESAR SI ES LAT
                            // ============================================
                            val langValue = lang["video_language"] as? String ?: "LAT"
                            if (langValue != "LAT") {
                                Log.d("Embed69Extractor", "❌ Idioma $langValue no permitido (solo LAT). Saltando...")
                                return@forEach
                            }

                            (lang["sortedEmbeds"] as? List<Map<String, Any>>)
                                ?.forEach { embed ->
                                    val enc = embed["link"] as? String ?: return@forEach
                                    val realUrl = decodeOldJwt(enc) ?: return@forEach
                                    val fixedLink = fixHostsLinks(realUrl)

                                    try {
                                        when {
                                            fixedLink.contains("minochinos") -> {
                                                MinochinosExtractorV2().getUrl(fixedLink, fixedUrl, subtitleCallback, callback)
                                            }
                                            fixedLink.contains("dood") || fixedLink.contains("do7go") -> {
                                                DoodExtractor().getUrl(fixedLink, fixedUrl, subtitleCallback, callback)
                                            }
                                            else -> {
                                                loadExtractor(fixedLink, fixedUrl, subtitleCallback, callback)
                                            }
                                        }
                                    } catch (_: Exception) {}
                                }
                        }
                }

            // =========================
            // 5. FALLBACK IFRAMES (Conservado)
            // =========================
            app.get(
                fixedUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer
                )
            ).document.select("iframe")
                .forEach {
                    val src = it.attr("src")
                    if (src.startsWith("http")) {
                        try {
                            when {
                                src.contains("minochinos") -> {
                                    MinochinosExtractorV2().getUrl(src, fixedUrl, subtitleCallback, callback)
                                }
                                src.contains("dood") || src.contains("do7go") -> {
                                    DoodExtractor().getUrl(src, fixedUrl, subtitleCallback, callback)
                                }
                                else -> {
                                    loadExtractor(src, fixedUrl, subtitleCallback, callback)
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }

        } catch (e: Exception) {
            Log.e("Embed69Extractor", "Error general: ${e.message}")
        }
    }

    // =========================
    // JWT VIEJO (Conservado)
    // =========================
    private fun decodeOldJwt(enc: String): String? {
        return try {
            val parts = enc.split(".")
            if (parts.size != 3) return null

            var payload = parts[1]
            val pad = payload.length % 4
            if (pad != 0) {
                payload += "=".repeat(4 - pad)
            }

            val json = String(Base64.decode(payload, Base64.DEFAULT))
            Regex("\"link\":\"(.*?)\"").find(json)?.groupValues?.getOrNull(1)
        } catch (_: Exception) {
            null
        }
    }

    // =========================
    // MÉTODOS NUEVOS (POW + AES)
    // =========================
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

    private fun decodeBase64Link(encryptedLink: String): String? {
        return try {
            val parts = encryptedLink.split(".")
            if (parts.size != 3) return null

            var payload = parts[1]
            if (payload.length % 4 != 0) {
                payload += "=".repeat(4 - payload.length % 4)
            }

            val json = String(Base64.decode(payload, Base64.DEFAULT))
            Regex("\"link\":\"(.*?)\"").find(json)?.groupValues?.get(1)
        } catch (_: Exception) {
            null
        }
    }

    // =========================
    // FIX HOSTS
    // =========================
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