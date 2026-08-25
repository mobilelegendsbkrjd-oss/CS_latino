package com.sololatino

import android.util.Base64
import android.util.Log
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
    override var mainUrl = "https://f75s.com"
    override val requiresReferer = true

    private var language: String = "LAT"

    fun withLanguage(lang: String): F75s {
        this.language = lang
        return this
    }

    private fun decode(value: String): ByteArray {
        val normalized = value + "=".repeat((4 - (value.length % 4)) % 4)
        return Base64.decode(normalized, Base64.URL_SAFE or Base64.NO_WRAP)
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            if (language != "LAT") {
                Log.d("F75s", "❌ Idioma $language no permitido (solo LAT). Saltando...")
                return
            }

            Log.d("F75s", "=== INICIANDO ===")
            Log.d("F75s", "URL: $url")

            val code = url.substringAfterLast("/")
            val embedUrl = "$mainUrl/embed/$code"

            val headers = mapOf(
                "Referer" to (referer ?: mainUrl),
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
            )

            val res = app.get(embedUrl, headers = headers)

            try {
                val root = JSONObject(res.text)
                val playback = root.optJSONObject("playback")

                if (playback != null) {
                    val algorithm = playback.optString("algorithm")

                    if (algorithm.contains("AES-256-GCM", true)) {
                        val iv = playback.getString("iv")
                        val payload = playback.getString("payload")
                        val keyParts = root.optJSONArray("keys") ?: JSONArray()

                        val key = buildString {
                            for (i in 0 until keyParts.length()) {
                                append(keyParts.getString(i))
                            }
                        }

                        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                        val secretKey = SecretKeySpec(key.toByteArray(), "AES")
                        val ivSpec = javax.crypto.spec.GCMParameterSpec(128, decode(iv))

                        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
                        val decrypted = cipher.doFinal(decode(payload))
                        val json = JSONObject(String(decrypted))

                        val sources = json.optJSONArray("sources") ?: JSONArray()

                        for (i in 0 until sources.length()) {
                            val source = sources.getJSONObject(i)
                            val file = source.optString("file")

                            if (file.isNullOrBlank()) continue

                            val nameWithLang = "$language[$name]"

                            callback.invoke(
                                newExtractorLink(
                                    source = "SoloLatino",
                                    name = nameWithLang,
                                    url = file
                                ) {
                                    this.type = if (file.contains(".m3u8")) {
                                        ExtractorLinkType.M3U8
                                    } else {
                                        ExtractorLinkType.VIDEO
                                    }
                                    this.referer = embedUrl
                                }
                            )
                            Log.d("F75s", "✅ Link agregado: $nameWithLang")
                        }
                        return
                    }
                }
            } catch (_: Exception) {}

            Log.d("F75s", "Usando fallback")
            val html = res.text

            Regex("""https?:\/\/[^\s"'<>]+""")
                .findAll(html)
                .map { it.value }
                .distinct()
                .forEach { file ->
                    if (file.contains(".m3u8") || file.contains(".mp4")) {
                        val nameWithLang = "$language[$name]"
                        callback.invoke(
                            newExtractorLink(
                                source = "SoloLatino",
                                name = nameWithLang,
                                url = file
                            ) {
                                this.type = if (file.contains(".m3u8")) {
                                    ExtractorLinkType.M3U8
                                } else {
                                    ExtractorLinkType.VIDEO
                                }
                                this.referer = embedUrl
                            }
                        )
                        Log.d("F75s", "✅ Link fallback: $nameWithLang")
                    }
                }

        } catch (e: Exception) {
            Log.e("F75s", "Error: ${e.message}")
        }
    }
}