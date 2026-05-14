package com.sololatino

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class F75s : ExtractorApi() {

    override var name = "F75s"
    override var mainUrl = "https://f75s.com"
    override val requiresReferer = false

    private fun decode(value: String): ByteArray {
        val normalized = value + "=".repeat((4 - value.length % 4) % 4)
        return Base64.decode(normalized, Base64.URL_SAFE or Base64.NO_WRAP)
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        val code = url.substringAfterLast("/")

        val embedUrl = "$mainUrl/e/$code"

        val headers = mapOf(
            "Referer" to embedUrl,
            "Origin" to mainUrl,
            "X-Embed-Origin" to "embed69.org"
        )

        val res = app.get(
            "$mainUrl/api/videos/$code/embed/playback",
            headers = headers
        ).text

        val root = JSONObject(res)
        val playback = root.getJSONObject("playback")

        val keyParts = playback.getJSONArray("key_parts")
        val iv = decode(playback.getString("iv"))
        val payload = decode(playback.getString("payload"))

        val key = (0 until keyParts.length())
            .map { decode(keyParts.getString(it)) }
            .reduce { acc, bytes -> acc + bytes }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, iv)
        )

        val decrypted = cipher.doFinal(payload)
        val json = JSONObject(String(decrypted, StandardCharsets.UTF_8))
        val sources = json.getJSONArray("sources")

        for (i in 0 until sources.length()) {
            val file = sources.getJSONObject(i).getString("url")

            callback.invoke(
                newExtractorLink(
                    name,
                    name,
                    file
                ) {
                    this.type = if (file.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    this.referer = embedUrl
                }
            )
        }
    }
}