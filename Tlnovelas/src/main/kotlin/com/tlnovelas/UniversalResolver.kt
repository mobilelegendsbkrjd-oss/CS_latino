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

    suspend fun resolve(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        var success = false

        try {

            // intento 1 extractor nativo
            success = success || loadExtractor(url, referer, subtitleCallback, callback)

            when {

                url.contains("hqq.to") ||
                url.contains("waaw.to") ||
                url.contains("netu.tv") -> {

                    success = success || extractHqq(url, referer, callback)
                }

                url.contains("bysejikuar") ||
                url.contains("f75s") -> {

                    success = success || extractBysejikuar(url, referer, callback)
                }

                else -> {

                    success = success || tryResolveGeneric(url, referer, callback)
                }
            }

        } catch (_: Exception) {}

        return success
    }

    private suspend fun tryResolveGeneric(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        return try {

            val text = app.get(url, referer = referer).text

            var searchText = text

            if (text.contains("eval(function(p,a,c,k,e")) {

                val unpacker = JsUnpacker(text)

                if (unpacker.detect()) {
                    unpacker.unpack()?.let { searchText = it }
                }
            }

            Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""")
                .find(searchText)?.groupValues?.get(1)?.let { videoUrl ->

                    callback.invoke(
                        newExtractorLink("Generic","Generic",videoUrl) {
                            this.referer = referer
                            this.quality = 0
                            this.type =
                                if (videoUrl.contains(".m3u8"))
                                    ExtractorLinkType.M3U8
                                else
                                    ExtractorLinkType.VIDEO
                        }
                    )

                    return true
                }

            Regex("""sources:\s*\[\{file:\s*["']([^"']+)""")
                .find(searchText)?.groupValues?.get(1)?.let { videoUrl ->

                    callback.invoke(
                        newExtractorLink("Generic","Generic",videoUrl) {
                            this.referer = referer
                            this.quality = 0
                            this.type =
                                if (videoUrl.contains(".m3u8"))
                                    ExtractorLinkType.M3U8
                                else
                                    ExtractorLinkType.VIDEO
                        }
                    )

                    return true
                }

            false

        } catch (_: Exception) {
            false
        }
    }

    private suspend fun extractHqq(
        embedUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        return try {

            val id =
                Regex("""/e/([A-Za-z0-9]+)""")
                    .find(embedUrl)
                    ?.groupValues?.get(1)
                    ?: return false

            val base = embedUrl.substringBefore("/e/")

            val embedRes = app.get(
                embedUrl,
                referer = referer,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0",
                    "Accept" to "*/*",
                    "Referer" to referer
                )
            )

            val cookies = embedRes.cookies
            val uid = cookies["uid"] ?: ""

            val details =
                app.get(
                    "$base/api/videos/$id/embed/details",
                    headers = mapOf(
                        "Referer" to embedUrl,
                        "X-Requested-With" to "XMLHttpRequest",
                        "User-Agent" to "Mozilla/5.0"
                    )
                ).text

            val embedFrame =
                Gson()
                    .fromJson(details, DetailsResponse::class.java)
                    .embed_frame_url
                    ?: return false

            // truco anti challenge
            try {
                app.get(embedUrl, referer = referer)
                app.get(embedFrame, referer = embedUrl)
            } catch (_: Exception) {}

            val playbackText =
                app.get(
                    "$base/api/videos/$id/embed/playback",
                    headers = mapOf(
                        "Referer" to embedFrame,
                        "Origin" to base,
                        "Cookie" to if (uid.isNotEmpty()) "uid=$uid" else ""
                    )
                ).text

            val playback =
                Gson()
                    .fromJson(playbackText, PlaybackResponse::class.java)
                    .playback
                    ?: return false

            val decrypted = decryptPlayback(playback) ?: return false

            val sources =
                Gson()
                    .fromJson(decrypted, DecryptedPlayback::class.java)
                    .sources
                    ?: return false

            var success = false

            sources.forEach { src ->

                src.url?.let { videoUrl ->

                    callback.invoke(
                        newExtractorLink("HQQ","HQQ",videoUrl) {
                            this.referer = embedUrl
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

            val id =
                Regex("""/(e|d)/([A-Za-z0-9]+)""")
                    .find(embedUrl)
                    ?.groupValues?.get(2)
                    ?: return false

            val base = embedUrl.substringBefore("/e/")

            val embedRes = app.get(
                embedUrl,
                referer = referer,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0",
                    "Referer" to referer
                )
            )

            val cookies = embedRes.cookies
            val viewerId = cookies["byse_viewer_id"] ?: ""
            val deviceId = cookies["byse_device_id"] ?: ""

            val detailsText =
                app.get(
                    "$base/api/videos/$id/embed/details",
                    headers = mapOf(
                        "Referer" to embedUrl,
                        "User-Agent" to "Mozilla/5.0",
                        "X-Embed-Origin" to "ww2.tlnovelas.net",
                        "X-Embed-Referer" to referer
                    )
                ).text

            val details =
                Gson().fromJson(detailsText, DetailsResponse::class.java)

            val embedFrame = details.embed_frame_url ?: embedUrl

            // truco anti challenge
            try {
                app.get(embedUrl, referer = referer)
                app.get(embedFrame, referer = embedUrl)
            } catch (_: Exception) {}

            val playbackBase =
                if (embedFrame.contains("f75"))
                    "https://f75s.com"
                else
                    base

            val playbackText =
                app.get(
                    "$playbackBase/api/videos/$id/embed/playback",
                    headers = mapOf(
                        "Referer" to embedFrame,
                        "Origin" to playbackBase,
                        "User-Agent" to "Mozilla/5.0",
                        "X-Embed-Origin" to "ww2.tlnovelas.net",
                        "X-Embed-Parent" to embedUrl,
                        "X-Embed-Referer" to referer,
                        "Cookie" to buildString {
                            if (viewerId.isNotEmpty()) append("byse_viewer_id=$viewerId; ")
                            if (deviceId.isNotEmpty()) append("byse_device_id=$deviceId")
                        }.trimEnd(';',' ')
                    )
                ).text

            val playback =
                Gson()
                    .fromJson(playbackText, PlaybackResponse::class.java)
                    .playback
                    ?: return false

            val decrypted = decryptPlayback(playback) ?: return false

            val sources =
                Gson()
                    .fromJson(decrypted, DecryptedPlayback::class.java)
                    .sources
                    ?: return false

            var success = false

            sources.forEach { src ->

                src.url?.let { videoUrl ->

                    callback.invoke(
                        newExtractorLink("Bysejikuar","Bysejikuar",videoUrl) {
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

            if (!success) {
                success = loadExtractor(embedUrl, referer, { }, callback)
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

        while (str.length % 4 != 0)
            str += "="

        return str
    }

    data class DetailsResponse(val embed_frame_url: String?)
    data class PlaybackResponse(val playback: PlaybackData?)
    data class PlaybackData(val iv: String, val payload: String, val key_parts: List<String>)
    data class DecryptedPlayback(val sources: List<DecryptedSource>?)
    data class DecryptedSource(val url: String?)
}
