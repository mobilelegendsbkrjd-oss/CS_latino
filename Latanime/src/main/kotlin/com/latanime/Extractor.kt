package com.latanime

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import kotlin.random.Random

object LatanimeExternalExtractor {

    suspend fun resolve(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fixed = fixHosts(url)
        val lower = fixed.lowercase()
        var found = false

        fun emit(link: ExtractorLink) {
            found = true
            callback.invoke(link)
        }

        try {
            when {

                lower.contains("bysekoze.com") -> {
                    BysekozeLatanime().getUrl(fixed, referer, subtitleCallback) { emit(it) }
                }

                lower.contains("dood.") ||
                        lower.contains("doodstream") ||
                        lower.contains("do7go.com") -> {
                    DoodLatanime().getUrl(fixed, referer, subtitleCallback) { emit(it) }
                }

                lower.contains("upns.online") ||
                        lower.contains("vidstack") -> {
                    VidStack().getUrl(fixed, referer, subtitleCallback) { emit(it) }
                }

                lower.contains("mp4upload.com") -> {
                    val normalized = normalizeMp4Upload(fixed)
                    loadExtractor(normalized, referer, subtitleCallback) {
                        emit(it)
                    }
                }

                lower.contains("voe.") -> {
                    loadExtractor(fixed, referer, subtitleCallback) {
                        emit(it)
                    }
                }

                lower.contains("mixdrop.") ||
                        lower.contains("mixdrop") -> {
                    loadExtractor(fixed, referer, subtitleCallback) {
                        emit(it)
                    }
                }

                lower.contains("hexload.") ||
                        lower.contains("savefiles.") ||
                        lower.contains("mega.nz") ||
                        lower.contains("dsvplay.") -> {
                    found = resolveGeneric(fixed, referer, callback)
                    if (!found) {
                        loadExtractor(fixed, referer, subtitleCallback) {
                            emit(it)
                        }
                    }
                }

                else -> {
                    found = resolveGeneric(fixed, referer, callback)
                    if (!found) {
                        loadExtractor(fixed, referer, subtitleCallback) {
                            emit(it)
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        return found
    }

    private suspend fun resolvePixeldrain(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = Regex("""pixeldrain\.com/(?:u|file|api/file)/([^/?#]+)""")
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?: return

        if (id.equals("download", true)) return

        callback.invoke(
            newExtractorLink(
                source = "Latanime",
                name = "Pixeldrain",
                url = "https://pixeldrain.com/api/file/$id?download",
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
            }
        )
    }

    private suspend fun resolveGeneric(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val response = app.get(
                url,
                headers = mapOf(
                    "Referer" to referer,
                    "User-Agent" to USER_AGENT
                )
            )

            var text = response.text

            if (text.contains("eval(function(p,a,c,k,e,d")) {
                JsUnpacker(text).unpack()?.let {
                    text = it
                }
            }

            val links = Regex("""https?://[^\s"'<>]+?\.(m3u8|mp4)[^\s"'<>]*""")
                .findAll(text)
                .map { it.value.replace("\\/", "/") }
                .distinct()
                .toList()

            links.forEach { video ->
                callback.invoke(
                    newExtractorLink(
                        source = "Latanime",
                        name = "Generic",
                        url = video,
                        type = if (video.contains(".m3u8")) {
                            ExtractorLinkType.M3U8
                        } else {
                            ExtractorLinkType.VIDEO
                        }
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
            }

            links.isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    private fun normalizeMp4Upload(url: String): String {
        val id = Regex("""(?:embed-|/)([A-Za-z0-9]+)(?:\.html)?""")
            .find(url)
            ?.groupValues
            ?.getOrNull(1)

        return if (!id.isNullOrBlank()) {
            "https://www.mp4upload.com/embed-$id.html"
        } else {
            url
        }
    }

    private fun fixHosts(url: String): String {
        var fixed = url.trim()

        if (fixed.startsWith("//")) {
            fixed = "https:$fixed"
        }

        return fixed
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("doodstream.com", "dood.la")
            .replace("do7go.com", "dood.la")
            .replace("voe.sx", "voe.unblockit.cat")
            .replace("filemoon.link", "filemoon.sx")
            .replace("filemoon.lat", "filemoon.sx")
            .replace("hglink.to", "streamwish.to")
            .replace("swdyu.com", "streamwish.to")
            .replace("wishembed.com", "streamwish.to")
            .replace("vidhide.com", "vidhidepro.com")
            .replace("mivalyo.com", "vidhidepro.com")
            .replace("dinisglows.com", "vidhidepro.com")
            .replace("uqload.io", "uqload.com")
            .replace("sbfull.com", "watchsb.com")
    }
}

class BysekozeLatanime : ExtractorApi() {

    override var name = "Bysekoze"
    override var mainUrl = "https://bysekoze.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "Referer" to (referer ?: mainUrl),
            "User-Agent" to USER_AGENT
        )

        try {
            val id = Regex("""/e/([a-zA-Z0-9]+)""")
                .find(url)
                ?.groupValues
                ?.getOrNull(1)
                ?: return

            val apiUrl = "$mainUrl/api/videos/$id/embed/playback"
            val json = JSONObject(app.get(apiUrl, headers = headers).text)

            val sources = json.optJSONArray("sources") ?: return

            for (i in 0 until sources.length()) {
                val obj = sources.getJSONObject(i)
                val link = obj.optString("url")

                if (link.isBlank()) continue

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = link,
                        type = if (link.contains(".m3u8")) {
                            ExtractorLinkType.M3U8
                        } else {
                            ExtractorLinkType.VIDEO
                        }
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            }

            return
        } catch (_: Exception) {
        }

        try {
            val document = app.get(url, headers = headers).document
            val packed = document
                .selectFirst("script:containsData(function(p,a,c,k,e,d))")
                ?.data()
                .orEmpty()

            JsUnpacker(packed).unpack()?.let { unpacked ->
                Regex("""sources:\s*\[\{file:\s*["'](.*?)["']""")
                    .find(unpacked)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.let { link ->
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = name,
                                url = link,
                                type = if (link.contains(".m3u8")) {
                                    ExtractorLinkType.M3U8
                                } else {
                                    ExtractorLinkType.VIDEO
                                }
                            ) {
                                this.referer = url
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
            }
        } catch (_: Exception) {
        }
    }
}

class DoodLatanime : ExtractorApi() {

    override val name = "Dood"
    override val mainUrl = "https://dood.la"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val safeUrl = url
                .replace("/d/", "/e/")
                .replace("doodstream.com", "dood.la")
                .replace("do7go.com", "dood.la")

            val html = app.get(
                safeUrl,
                referer = referer
            ).text

            val md5Path = Regex("""/pass_md5/[^"']+""")
                .find(html)
                ?.value
                ?: return

            val base = safeUrl.substringBefore("/e/")
            val md5Url = "$base$md5Path"

            val token = Regex("""token=([a-zA-Z0-9]+)""")
                .find(html)
                ?.groupValues
                ?.getOrNull(1)
                .orEmpty()

            val prefix = app.get(
                md5Url,
                referer = safeUrl,
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "User-Agent" to USER_AGENT
                )
            ).text.trim()

            val finalUrl = prefix + randomString(10) + "?token=$token"

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = finalUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = safeUrl
                    this.quality = Qualities.Unknown.value
                }
            )
        } catch (_: Exception) {
        }
    }

    private fun randomString(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        return (1..length)
            .map { chars.random(Random) }
            .joinToString("")
    }
}