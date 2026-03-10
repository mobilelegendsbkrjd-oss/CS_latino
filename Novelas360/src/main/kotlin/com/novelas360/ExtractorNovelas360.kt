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

    suspend fun resolve(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        var success = false

        try {

            // intentar extractores internos primero
            if (loadExtractor(url, referer, subtitleCallback, callback)) {
                success = true
            }

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

                url.contains("cyou") ||
                url.contains("cyfs") -> {
                    success = success || extractAflamy(url, referer, callback)
                }

                else -> {
                    success = success || extractGeneric(url, referer, callback)
                }
            }

        } catch (_: Exception) {}

        return success
    }

    // -------------------------
    // AFLAMY / CYOU / CYFS
    // -------------------------

    private suspend fun extractAflamy(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        return try {

            val key = url.substringAfter("/e/")

            val headers = mapOf(
                "Referer" to referer,
                "Origin" to url.substringBefore("/e/"),
                "X-Requested-With" to "XMLHttpRequest"
            )

            val data = mapOf(
                "v" to key,
                "secure" to "0",
                "ver" to "4",
                "adb" to "0",
                "wasmcheck" to "0"
            )

            val res = app.post(
                "${url.substringBefore("/e/")}/player/get_md5.php",
                data = data,
                headers = headers
            )

            val json = res.parsedSafe<Map<String,String>>() ?: return false

            val file = json["file"] ?: return false

            callback.invoke(
                newExtractorLink(
                    "AflamyPlayer",
                    "Servidor Directo",
                    file
                ) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                    this.type =
                        if (file.contains(".m3u8"))
                            ExtractorLinkType.M3U8
                        else
                            ExtractorLinkType.VIDEO

                    this.headers = mapOf(
                        "Referer" to referer,
                        "Origin" to url.substringBefore("/e/"),
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
                    )
                }
            )

            true

        } catch (_: Exception) {
            false
        }
    }

    // -------------------------
    // HQQ / NETU / WAAW
    // -------------------------

    private suspend fun extractHqq(
        embedUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        return try {

            val id = Regex("""/e/([A-Za-z0-9]+)""")
                .find(embedUrl)?.groupValues?.get(1) ?: return false

            val base = embedUrl.substringBefore("/e/")

            val details =
                app.get("$base/api/videos/$id/embed/details").text

            val embedFrame =
                Gson().fromJson(details, DetailsResponse::class.java)
                    .embed_frame_url ?: return false

            val playbackText =
                app.get(
                    "$base/api/videos/$id/embed/playback",
                    headers = mapOf(
                        "Referer" to embedFrame
                    )
                ).text

            val playback =
                Gson().fromJson(playbackText, PlaybackResponse::class.java)
                    .playback ?: return false

            val decrypted = decryptPlayback(playback) ?: return false

            val sources =
                Gson().fromJson(decrypted, DecryptedPlayback::class.java)
                    .sources ?: return false

            sources.firstOrNull()?.url?.let { videoUrl ->

                callback.invoke(
                    newExtractorLink(
                        "HQQ",
                        "HQQ",
                        videoUrl
                    ) {
                        this.referer = referer
                        this.quality = Qualities.Unknown.value
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

    // -------------------------
    // BYSEJIKUAR / F75S
    // -------------------------

    private suspend fun extractBysejikuar(
        embedUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        return try {

            val id =
                Regex("""/(e|d)/([A-Za-z0-9]+)""")
                    .find(embedUrl)?.groupValues?.get(2)
                    ?: return false

            val base = embedUrl.substringBefore("/e/")

            val detailsText =
                app.get("$base/api/videos/$id/embed/details").text

            val embedFrame =
                Gson().fromJson(detailsText, DetailsResponse::class.java)
                    .embed_frame_url ?: return false

            val playbackText =
                app.get(
                    "$base/api/videos/$id/embed/playback",
                    headers = mapOf(
                        "Referer" to embedFrame
                    )
                ).text

            val playback =
                Gson().fromJson(playbackText, PlaybackResponse::class.java)
                    .playback ?: return false

            val decrypted = decryptPlayback(playback) ?: return false

            val sources =
                Gson().fromJson(decrypted, DecryptedPlayback::class.java)
                    .sources ?: return false

            sources.firstOrNull()?.url?.let { videoUrl ->

                callback.invoke(
                    newExtractorLink(
                        "Bysejikuar",
                        "Bysejikuar",
                        videoUrl
                    ) {
                        this.referer = referer
                        this.quality = Qualities.Unknown.value
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

    // -------------------------
    // GENERIC M3U8 / MP4
    // -------------------------

    private suspend fun extractGeneric(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        return try {

            val text = app.get(url, referer = referer).text

            Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""")
                .find(text)?.groupValues?.get(1)?.let { videoUrl ->

                    callback.invoke(
                        newExtractorLink(
                            "Generic",
                            "Generic",
                            videoUrl
                        ) {
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

    // -------------------------
    // DECRYPT
    // -------------------------

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

    data class DetailsResponse(val embed_frame_url: String?)
    data class PlaybackResponse(val playback: PlaybackData?)
    data class PlaybackData(val iv: String, val payload: String, val key_parts: List<String>)
    data class DecryptedPlayback(val sources: List<DecryptedSource>?)
    data class DecryptedSource(val url: String?)
}
