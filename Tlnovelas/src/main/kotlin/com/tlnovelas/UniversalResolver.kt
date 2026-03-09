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
                else -> {
                    success = success || tryResolveGeneric(url, referer, callback)
                }
            }
            // Fallback fuerte para hqq si el custom no funciona
            if (!success && (url.contains("hqq.to") || url.contains("waaw.to") || url.contains("netu.tv"))) {
                success = success || loadExtractor(url, referer, subtitleCallback, callback)
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
                        newExtractorLink("Generic", "Generic", videoUrl) {
                            this.referer = referer
                            this.quality = 0
                            this.type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        }
                    )
                    return true
                }
            Regex("""sources:\s*\[\{file:\s*["']([^"']+)""")
                .find(searchText)?.groupValues?.get(1)?.let { videoUrl ->
                    callback.invoke(
                        newExtractorLink("Generic", "Generic", videoUrl) {
                            this.referer = referer
                            this.quality = 0
                            this.type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
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
            val id = Regex("""/e/([A-Za-z0-9]+)""").find(embedUrl)?.groupValues?.get(1) ?: return false
            val base = embedUrl.substringBefore("/e/")

            // Visita el embed para setear cookies (muy importante para hqq)
            val embedRes = app.get(embedUrl, referer = referer, headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Accept" to "*/*",
                "Referer" to referer
            ))

            val cookies = embedRes.cookies
            val uid = cookies["uid"] ?: ""

            val details = app.get(
                "$base/api/videos/$id/embed/details",
                headers = mapOf(
                    "Referer" to embedUrl,
                    "X-Requested-With" to "XMLHttpRequest",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )
            ).text

            val embedFrame = Gson().fromJson(details, DetailsResponse::class.java).embed_frame_url ?: return false

            val playbackText = app.get(
                "$base/api/videos/$id/embed/playback",
                headers = mapOf(
                    "Referer" to embedFrame,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Origin" to base,
                    "Cookie" to if (uid.isNotEmpty()) "uid=$uid" else ""
                )
            ).text

            val playback = Gson().fromJson(playbackText, PlaybackResponse::class.java).playback ?: return false
            val decrypted = decryptPlayback(playback) ?: return false
            val sources = Gson().fromJson(decrypted, DecryptedPlayback::class.java).sources ?: return false

            var success = false
            sources.forEach { src ->
                src.url?.let { videoUrl ->
                    callback.invoke(
