package com.novelas360

import com.google.gson.Gson
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object UniversalExtractor {
    private val chromeUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

    suspend fun resolve(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var success = false
        try {
            // Prioridad 1: extractor nativo de CloudStream (lo más efectivo)
            if (loadExtractor(url, referer, subtitleCallback, callback)) {
                success = true
            }

            when {
                url.contains("hqq.to") || url.contains("waaw.to") || url.contains("netu.tv") -> {
                    success = success || extractHqq(url, referer, callback)
                }
                url.contains("bysejikuar") || url.contains("f75s") -> {
                    success = success || extractBysejikuar(url, referer, callback)
                }
                url.contains("cyou") || url.contains("cyfs") -> {
                    success = success || extractAflamy(url, referer, callback)
                }
                else -> {
                    success = success || extractGeneric(url, referer, callback)
                }
            }

            // Último intento si nada funcionó
            if (!success) {
                success = loadExtractor(url, referer, subtitleCallback, callback)
            }
        } catch (_: Exception) {}
        return success
    }

    // AFLAMY / CYOU / CYFS (el más común en novelas360)
    private suspend fun extractAflamy(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val key = url.substringAfter("/e/")
            val base = url.substringBefore("/e/")
            val headers = mapOf(
                "Referer" to referer,
                "Origin" to base,
                "X-Requested-With" to "XMLHttpRequest",
                "User-Agent" to chromeUA,
                "Accept" to "*/*"
            )
            val data = mapOf(
                "v" to key,
                "secure" to "0",
                "ver" to "4",
                "adb" to "0",
                "wasmcheck" to "0"
            )
            val res = app.post(
                "$base/player/get_md5.php",
                data = data,
                headers = headers
            )
            val json = res.parsedSafe<Map<String, String>>() ?: return false
            val file = json["file"] ?: return false

            callback.invoke(
                newExtractorLink("Aflamy/Cyou", "Servidor Directo", file) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                    this.type = if (file.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    this.headers = mapOf(
                        "Referer" to referer,
                        "Origin" to base,
                        "User-Agent" to chromeUA
                    )
                }
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    // HQQ / NETU / WAAW (lo dejas como estaba, pero con headers)
    private suspend fun extractHqq(
        embedUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val id = Regex("""/e/([A-Za-z0-9]+)""").find(embedUrl)?.groupValues?.get(1) ?: return false
            val base = embedUrl.substringBefore("/e/")
            val details = app.get(
                "$base/api/videos/$id/embed/details",
                headers = mapOf("Referer" to referer, "User-Agent" to chromeUA)
            ).text
            val embedFrame = Gson().fromJson(details, DetailsResponse::class.java).embed_frame_url ?: return false
            val playbackText = app.get(
                "$base/api/videos/$id/embed/playback",
                headers = mapOf("Referer" to embedFrame, "User-Agent" to chromeUA)
            ).text
            val playback = Gson().fromJson(playbackText, PlaybackResponse::class.java).playback ?: return false
            val decrypted = decryptPlayback(playback) ?: return false
            val sources = Gson().fromJson(decrypted, DecryptedPlayback::class.java).sources ?: return false
            sources.forEach { src ->
                src.url?.let { videoUrl ->
                    callback.invoke(
                        newExtractorLink("HQQ", "HQQ", videoUrl) {
                            this.referer = referer
                            this.quality = Qualities.Unknown.value
                            this.type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        }
                    )
                }
            }
            sources.isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    // BYSEJIKUAR / F75S (versión mejorada con cookies y headers)
    private suspend fun extractBysejikuar(
        embedUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val id = Regex("""/(e|d)/([A-Za-z0-9]+)""").find(embedUrl)?.groupValues?.get(2) ?: return false
            val base = embedUrl.substringBefore("/e/")
            val embedRes = app.get(embedUrl, referer = referer, headers = mapOf("User-Agent" to chromeUA))
            val cookies = embedRes.cookies
            val viewerId = cookies["byse_viewer_id"] ?: ""
            val deviceId = cookies["byse_device_id"] ?: ""
            val detailsText = app.get(
                "$base/api/videos/$id/embed/details",
                headers = mapOf(
                    "Referer" to embedUrl,
                    "User-Agent" to chromeUA,
                    "X-Embed-Origin" to "novelas360.com"
                )
            ).text
            val embedFrame = Gson().fromJson(detailsText, DetailsResponse::class.java).embed_frame_url ?: return false
            val playbackBase = if (embedFrame.contains("f75")) "https://f75s.com" else base
            val playbackText = app.get(
                "$playbackBase/api/videos/$id/embed/playback",
                headers = mapOf(
                    "Referer" to embedFrame,
                    "Origin" to playbackBase,
                    "User-Agent" to chromeUA,
                    "Cookie" to buildString {
                        if (viewerId.isNotEmpty()) append("byse_viewer_id=$viewerId; ")
                        if (deviceId.isNotEmpty()) append("byse_device_id=$deviceId")
                    }.trimEnd(';',' ')
                )
            ).text
            val playback = Gson().fromJson(playbackText, PlaybackResponse::class.java).playback ?: return false
            val decrypted = decryptPlayback(playback) ?: return false
            val sources = Gson().fromJson(decrypted, DecryptedPlayback::class.java).sources ?: return false
            sources.forEach { src ->
                src.url?.let { videoUrl ->
                    callback.invoke(
                        newExtractorLink("Byse/F75s", "Byse/F75s", videoUrl) {
                            this.referer = embedFrame
                            this.quality = Qualities.Unknown.value
                            this.type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        }
                    )
                }
            }
            sources.isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    // GENERIC (con unpack)
    private suspend fun extractGeneric(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val text = app.get(url, referer = referer).text
            var searchText = text
            if (text.contains("eval(function(p,a,c,k,e")) {
                try {
                    val unpacker = JsUnpacker(text)
                    if (unpacker.detect()) {
                        unpacker.unpack()?.let { searchText = it }
                    }
                } catch (_: Exception) {}
            }
            Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""").find(searchText)?.groupValues?.get(1)?.let { videoUrl ->
                callback.invoke(
                    newExtractorLink("Generic M3U8", "Generic M3U8", videoUrl) {
                        this.referer = referer
                        this.quality = Qualities.Unknown.value
                        this.type = ExtractorLinkType.M3U8
                    }
                )
                return true
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun decryptPlayback(data: PlaybackData): String? {
        return try {
            val decoder = Base64.getUrlDecoder()
            val iv = decoder.decode(pad(data.iv))
            val payload = decoder.decode(pad(data.payload))
            val key = decoder.decode(pad(data.key_parts[0])) + decoder.decode(pad(data.key_parts[1]))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            String(cipher.doFinal(payload))
        } catch (_: Exception) {
            null
        }
    }

    private fun pad(s: String): String {
        var str = s
        while (str.length % 4 != 0) str += "="
        return str
    }

    data class DetailsResponse(val embed_frame_url: String?)
    data class PlaybackResponse(val playback: PlaybackData?)
    data class PlaybackData(val iv: String, val payload: String, val key_parts: List<String>)
    data class DecryptedPlayback(val sources: List<DecryptedSource>?)
    data class DecryptedSource(val url: String?)
}
