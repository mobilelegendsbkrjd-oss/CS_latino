package com.tlnovelas

import com.google.gson.Gson
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object UniversalResolver {

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"

    suspend fun resolve(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var success = false

        try {
            success = success || when {
                url.contains("hqq.to") ||
                        url.contains("waaw.to") ||
                        url.contains("netu.tv") -> {
                    // HQQ/Netu/Waaw tienen flujo JS/captcha. Usamos extractor nativo.
                    val fixedUrl = if (url.contains("hqq.to/e/")) {
                        url.replace("/e/", "/f/")
                    } else {
                        url
                    }

                    loadExtractor(fixedUrl, "https://ww2.tlnovelas.net/", subtitleCallback, callback) ||
                            loadExtractor(url, "https://ww2.tlnovelas.net/", subtitleCallback, callback)
                }

                url.contains("bysejikuar") ||
                        url.contains("f75s") -> {
                    loadExtractor(url, referer, subtitleCallback, callback) ||
                            extractBysejikuar(url, referer, callback)
                }

                url.contains("dooodster") ||
                        url.contains("dood.") ||
                        url.contains("doodstream") -> {
                    loadExtractor(url, referer, subtitleCallback, callback)
                }

                else -> {
                    loadExtractor(url, referer, subtitleCallback, callback) ||
                            tryResolveGeneric(url, referer, callback)
                }
            }
        } catch (_: Exception) {}

        return success
    }

    private fun getBase(url: String): String {
        val protocol = url.substringBefore("://")
        val host = url.substringAfter("://").substringBefore("/")
        return "$protocol://$host"
    }

    private suspend fun tryResolveGeneric(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val text = app.get(
                url,
                referer = referer,
                headers = mapOf(
                    "User-Agent" to UA,
                    "Accept" to "*/*",
                    "Referer" to referer
                )
            ).text

            var searchText = text

            if (text.contains("eval(function(p,a,c,k,e")) {
                val unpacker = JsUnpacker(text)
                if (unpacker.detect()) {
                    unpacker.unpack()?.let { searchText = it }
                }
            }

            val found = linkedSetOf<String>()

            Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""")
                .findAll(searchText)
                .forEach { found.add(it.groupValues[1].replace("\\/", "/")) }

            Regex("""file\s*:\s*["']([^"']+)""")
                .findAll(searchText)
                .forEach { found.add(it.groupValues[1].replace("\\/", "/")) }

            Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*["']([^"']+)""")
                .findAll(searchText)
                .forEach { found.add(it.groupValues[1].replace("\\/", "/")) }

            var success = false

            found.forEach { videoUrl ->
                if (videoUrl.startsWith("http")) {
                    callback.invoke(
                        newExtractorLink("Generic", "Generic", videoUrl) {
                            this.referer = url
                            this.quality = 0
                            this.type =
                                if (videoUrl.contains(".m3u8"))
                                    ExtractorLinkType.M3U8
                                else
                                    ExtractorLinkType.VIDEO
                        }
                    )
                    success = true
                }
            }

            success
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun extractBysejikuar(
        embedUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val id = Regex("""/(?:e|d)/([A-Za-z0-9]+)""")
                .find(embedUrl)
                ?.groupValues
                ?.get(1)
                ?: return false

            val base = getBase(embedUrl)

            val embedRes = app.get(
                embedUrl,
                referer = referer,
                headers = mapOf(
                    "User-Agent" to UA,
                    "Referer" to referer,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
            )

            val cookies = embedRes.cookies
            val viewerId = cookies["byse_viewer_id"] ?: ""
            val deviceId = cookies["byse_device_id"] ?: ""

            val detailsText = app.get(
                "$base/api/videos/$id/embed/details",
                headers = mapOf(
                    "Referer" to embedUrl,
                    "Origin" to base,
                    "User-Agent" to UA,
                    "Accept" to "application/json,text/plain,*/*",
                    "X-Requested-With" to "XMLHttpRequest",
                    "X-Embed-Origin" to "ww2.tlnovelas.net",
                    "X-Embed-Referer" to referer
                )
            ).text

            val details = Gson().fromJson(detailsText, DetailsResponse::class.java)
            val embedFrame = details.embed_frame_url ?: embedUrl

            try {
                app.get(embedUrl, referer = referer, headers = mapOf("User-Agent" to UA))
                app.get(embedFrame, referer = embedUrl, headers = mapOf("User-Agent" to UA))
            } catch (_: Exception) {}

            val playbackBase =
                if (embedFrame.contains("f75s.com")) "https://f75s.com"
                else base

            val cookie = buildString {
                if (viewerId.isNotEmpty()) append("byse_viewer_id=$viewerId; ")
                if (deviceId.isNotEmpty()) append("byse_device_id=$deviceId")
            }.trimEnd(';', ' ')

            val playbackText = app.get(
                "$playbackBase/api/videos/$id/embed/playback",
                headers = mapOf(
                    "Referer" to embedFrame,
                    "Origin" to playbackBase,
                    "User-Agent" to UA,
                    "Accept" to "application/json,text/plain,*/*",
                    "X-Requested-With" to "XMLHttpRequest",
                    "X-Embed-Origin" to "ww2.tlnovelas.net",
                    "X-Embed-Parent" to embedUrl,
                    "X-Embed-Referer" to referer,
                    "Cookie" to cookie
                )
            ).text

            val playback = Gson()
                .fromJson(playbackText, PlaybackResponse::class.java)
                .playback
                ?: return false

            val decrypted = decryptPlayback(playback) ?: return false

            val sources = Gson()
                .fromJson(decrypted, DecryptedPlayback::class.java)
                .sources
                ?: return false

            var success = false

            sources.forEach { src ->
                src.url?.let { videoUrl ->
                    callback.invoke(
                        newExtractorLink("Bysejikuar", "Bysejikuar", videoUrl) {
                            this.referer = embedFrame
                            this.quality = 0
                            this.type =
                                if (videoUrl.contains(".m3u8"))
                                    ExtractorLinkType.M3U8
                                else
                                    ExtractorLinkType.VIDEO
                        }
                    )
                    success = true
                }
            }

            success
        } catch (_: Exception) {
            false
        }
    }

    private fun decryptPlayback(data: PlaybackData): String? {
        return try {
            val decoder = Base64.getUrlDecoder()

            val iv = decoder.decode(pad(data.iv))
            val payload = decoder.decode(pad(data.payload))

            val key =
                decoder.decode(pad(data.key_parts[0])) +
                        decoder.decode(pad(data.key_parts[1]))

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")

            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, iv)
            )

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

    data class DetailsResponse(
        val embed_frame_url: String?
    )

    data class PlaybackResponse(
        val playback: PlaybackData?
    )

    data class PlaybackData(
        val iv: String,
        val payload: String,
        val key_parts: List<String>
    )

    data class DecryptedPlayback(
        val sources: List<DecryptedSource>?
    )

    data class DecryptedSource(
        val url: String?
    )
}