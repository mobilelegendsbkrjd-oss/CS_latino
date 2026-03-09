package com.tlnovelas

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.google.gson.Gson
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

            val doc = app.get(url, referer = referer).text

            Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""")
                .find(doc)?.groupValues?.get(1)?.let {

                    callback.invoke(
                        ExtractorLink(
                            "Direct",
                            "Direct",
                            it,
                            referer,
                            0,
                            true
                        )
                    )

                    return true
                }

            Regex("""sources:\s*\[\{file:\s*["']([^"']+)""")
                .find(doc)?.groupValues?.get(1)?.let {

                    callback.invoke(
                        ExtractorLink(
                            "Generic",
                            "Generic",
                            it,
                            referer,
                            0,
                            it.contains(".m3u8")
                        )
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

            val id = Regex("""/e/([A-Za-z0-9]+)""")
                .find(embedUrl)?.groupValues?.get(1) ?: return false

            val base = embedUrl.substringBefore("/e/")

            val details = app.get(
                "$base/api/videos/$id/embed/details",
                headers = mapOf("Referer" to referer)
            ).text

            val embedFrame = Gson()
                .fromJson(details, DetailsResponse::class.java)
                .embed_frame_url ?: return false

            val playbackText = app.get(
                "$base/api/videos/$id/embed/playback",
                headers = mapOf(
                    "Referer" to embedFrame,
                    "User-Agent" to "Mozilla/5.0"
                )
            ).text

            val playback = Gson()
                .fromJson(playbackText, PlaybackResponse::class.java)
                .playback ?: return false

            val decrypted = decryptPlayback(playback) ?: return false

            val sources = Gson()
                .fromJson(decrypted, DecryptedPlayback::class.java)
                .sources ?: return false

            sources.firstOrNull()?.url?.let {

                callback.invoke(
                    ExtractorLink(
                        "HQQ",
                        "HQQ",
                        it,
                        referer,
                        0,
                        it.contains(".m3u8")
                    )
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

            val key = decoder.decode(pad(data.key_parts[0])) +
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

    private suspend fun extractBysejikuar(
        embedUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        return try {

            val id = Regex("""/(e|d)/([A-Za-z0-9]+)""")
                .find(embedUrl)?.groupValues?.get(2) ?: return false

            val base = embedUrl.substringBefore("/e/")

            val detailsText = app.get(
                "$base/api/videos/$id/embed/details",
                headers = mapOf("Referer" to referer)
            ).text

            val embedFrame = Gson()
                .fromJson(detailsText, DetailsResponse::class.java)
                .embed_frame_url ?: return false

            val playbackText = app.get(
                "$base/api/videos/$id/embed/playback",
                headers = mapOf(
                    "Referer" to embedFrame,
                    "User-Agent" to "Mozilla/5.0"
                )
            ).text

            val playback = Gson()
                .fromJson(playbackText, PlaybackResponse::class.java)
                .playback ?: return false

            val decrypted = decryptPlayback(playback) ?: return false

            val sources = Gson()
                .fromJson(decrypted, DecryptedPlayback::class.java)
                .sources ?: return false

            sources.firstOrNull()?.url?.let {

                callback.invoke(
                    ExtractorLink(
                        "Bysejikuar",
                        "Bysejikuar",
                        it,
                        referer,
                        0,
                        it.contains(".m3u8")
                    )
                )

                return true
            }

            false

        } catch (_: Exception) {
            false
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
