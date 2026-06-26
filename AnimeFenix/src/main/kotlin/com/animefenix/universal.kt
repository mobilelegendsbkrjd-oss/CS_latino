package com.animefenix

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/**
 * UniversalVideoResolver
 *
 * Uso desde loadLinks:
 *
 * var found = false
 * urls.forEach { url ->
 *     if (UniversalVideoResolver.resolve(url, pageUrl, subtitleCallback, callback)) {
 *         found = true
 *     }
 * }
 * return found
 *
 * Hosts cubiertos:
 * - IronHentai / AnimeMeow face.php
 * - Uqload
 * - OkRu
 * - YourUpload / YuCache
 * - Dood aliases
 * - Streamtape aliases
 * - VOE aliases con decrypt integrado
 * - Jawcloud
 * - Zilla Networks
 * - Upstream / StreamUp / StrmUp
 * - UpZur
 * - VidGuard
 * - VidHide / FileLions-like
 * - Fembed-like viejo api/source
 * - Byse/F75 AES-GCM
 * - HQQ viejo/básico + fallback, HQQ nuevo se intenta pero puede fallar
 * - MP4Upload / StreamSB / ViewSB / Moovies / Xonaplay / MailRu con fallback genérico
 */
object UniversalVideoResolver {
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"

    private val jsonType = "application/json; charset=utf-8".toMediaTypeOrNull()

    suspend fun resolve(

        rawUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val url = cleanUrl(rawUrl) ?: return false

        return try {
            when {
                isIronHentai(url) ->
                    extractIronHentai(url, referer, callback) ||
                            loadExtractor(url, referer, subtitleCallback, callback) ||
                            tryGeneric(url, referer, "IRONHENTAI", callback)

                isOkru(url) ->
                    extractOkru(url, referer, callback) ||
                            loadExtractor(url, referer, subtitleCallback, callback) ||
                            tryGeneric(url, referer, "OKRU", callback)

                isUqload(url) ->
                    extractUqload(url, referer, callback) ||
                            loadExtractor(url, referer, subtitleCallback, callback) ||
                            tryGeneric(url, referer, "UQLOAD", callback)

                isYourUpload(url) ->
                    extractYourUpload(url, referer, callback) ||
                            loadExtractor(url, referer, subtitleCallback, callback) ||
                            tryGeneric(url, referer, "YOURUPLOAD", callback)

                isDood(url) ->
                    extractDood(url, referer, callback) ||
                            loadExtractor(url, referer, subtitleCallback, callback) ||
                            tryGeneric(url, referer, "DOOD", callback)

                isStreamtape(url) ->
                    extractStreamtape(url, referer, callback) ||
                            loadExtractor(url, referer, subtitleCallback, callback) ||
                            tryGeneric(url, referer, "STREAMTAPE", callback)

                isVoe(url) ->
                    extractVoe(url, referer, callback) ||
                            loadExtractor(url, referer, subtitleCallback, callback) ||
                            tryGeneric(url, referer, "VOE", callback)

                isJawcloud(url) ->
                    extractJawcloud(url, referer, callback) ||
                            loadExtractor(url, referer, subtitleCallback, callback) ||
                            tryGeneric(url, referer, "JAWCLOUD", callback)

                isZilla(url) ->
                    extractZilla(url, referer, callback) ||
                            loadExtractor(url, referer, subtitleCallback, callback)

                isStreamUp(url) ->
                    extractStreamUp(url, referer, callback) ||
                            loadExtractor(url, referer, subtitleCallback, callback) ||
                            tryGeneric(url, referer, "STREAMUP", callback)

                isUpZur(url) ->
                    extractUpZur(url, referer, callback) ||
                            loadExtractor(url, referer, subtitleCallback, callback) ||
                            tryGeneric(url, referer, "UPZUR", callback)

                isVidGuard(url) ->
                    extractVidGuard(url, referer, callback) ||
                            loadExtractor(url, referer, subtitleCallback, callback) ||
                            tryGeneric(url, referer, "VIDGUARD", callback)

                isVidHide(url) ->
                    extractVidHide(url, referer, callback) ||
                            loadExtractor(url, referer, subtitleCallback, callback) ||
                            tryGeneric(url, referer, "VIDHIDE", callback)

                isFembed(url) ->
                    extractFembedLike(url, referer, callback) ||
                            loadExtractor(url, referer, subtitleCallback, callback) ||
                            tryGeneric(url, referer, "FEMBED", callback)

                isByse(url) ->
                    extractByse(url, referer, callback) ||
                            loadExtractor(url, referer, subtitleCallback, callback) ||
                            tryGeneric(url, referer, "BYSE", callback)

                isHqq(url) ->
                    extractHqq(url, referer, callback) ||
                            loadExtractor(url, referer, subtitleCallback, callback) ||
                            tryGeneric(url, referer, "HQQ", callback)

                isMailRu(url) ->
                    extractMailRu(url, referer, callback) ||
                            loadExtractor(url, referer, subtitleCallback, callback) ||
                            tryGeneric(url, referer, "MAILRU", callback)

                isMega(url) ->
                    // Mega embed casi nunca da stream directo usable en Cloudstream.
                    // Se intenta con extractor nativo/fallback, sin crear Direct falso.
                    loadExtractor(url, referer, subtitleCallback, callback)

                url.contains("viewsb", true) ||
                        url.contains("streamsb", true) ||
                        url.contains("watchsb", true) ||
                        url.contains("sbplay", true) -> {
                    extractViewSb(url, referer, callback) ||
                            loadExtractor(url, referer, subtitleCallback, callback) ||
                            tryGeneric(url, referer, "VIEWSB", callback)
                }

                else ->
                    loadExtractor(url, referer, subtitleCallback, callback) ||
                            tryGeneric(url, referer, detectName(url), callback)
            }
        } catch (_: Exception) {
            false
        }
    }

    fun cleanUrl(raw: String): String? {
        var url = raw.trim()
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("&amp;", "&")
            .replace("\\u0026", "&")

        if (url.startsWith("//")) url = "https:$url"

        if (url.contains("redirect.php?id=", true)) {
            url = url.substringAfter("redirect.php?id=").trim()
        }

        if (url.contains("smart.php?url=", true)) {
            url = url.substringAfter("smart.php?url=").trim()
        }



        url = try {
            URLDecoder.decode(url, "UTF-8")
        } catch (_: Exception) {
            url
        }

        return url.takeIf { it.startsWith("http") }
    }

    private fun isIronHentai(url: String) = url.contains("ironhentai", true)
    private fun isOkru(url: String) = url.contains("ok.ru", true)
    private fun isUqload(url: String) = url.contains("uqload", true)
    private fun isYourUpload(url: String) = url.contains("yourupload", true) || url.contains("yucache", true)
    private fun isDood(url: String) =
        listOf("dood.", "dood.to", "dood.la", "dood.li", "doodstream", "d000d", "dooood", "dsvplay", "myvidplay", "playmogo", "do7go", "vide0.net")
            .any { url.contains(it, true) }

    private fun isStreamtape(url: String) = url.contains("streamtape", true) || url.contains("streamta.site", true)
    private fun isJawcloud(url: String) = url.contains("jawcloud", true)
    private fun isZilla(url: String) = url.contains("player.zilla-networks.com", true)
    private fun isStreamUp(url: String) = url.contains("strmup.to", true) || url.contains("streamup", true) || url.contains("upstream", true)
    private fun isUpZur(url: String) = url.contains("upzur", true)
    private fun isVidGuard(url: String) =
        listOf("vidguard.to", "vembed.net", "bembed.cc", "vgfplay.com", "listeamed.net").any { url.contains(it, true) }

    private fun isVidHide(url: String) =
        listOf("dhtpre.com", "peytonepre.com", "vidhideplus.com", "mivalyo", "dinisglows", "dingtezuni.com", "dintezuvio.com", "minochinos.com", "moflix-stream.click", "filelions.to")
            .any { url.contains(it, true) }

    private fun isFembed(url: String) =
        listOf("fembed", "feurl", "fplayer", "frembed").any { url.contains(it, true) }

    private fun isByse(url: String) =
        listOf("bysejikuar", "f75s", "bysevepoin", "g9r6.com").any { url.contains(it, true) }

    private fun isHqq(url: String) =
        listOf("hqq.to", "hqq.tv", "waaw.to", "netu.tv").any { url.contains(it, true) }

    private fun isMailRu(url: String) = url.contains("my.mail.ru", true) || url.contains("mail.ru", true)
    private fun isMega(url: String) = url.contains("mega.nz", true)

    private fun isVoe(url: String): Boolean {
        val aliases = listOf(
            "voe.sx", "voe.", "jilliandescribecompany.com", "mikaylaarealike.com",
            "christopheruntilpoint.com", "walterprettytheir.com", "crystaltreatmenteast.com",
            "lauradaydo.com", "lancewhosedifficult.com", "dianaavoidthey.com",
            "jefferycontrolmodel.com", "charlestoughrace.com", "richardquestionbuilding.com",
            "jessicayeahcatch.com"
        )
        return aliases.any { url.contains(it, true) }
    }

    private fun detectName(url: String): String {
        return when {
            isFembed(url) -> "FEMBED"
            isOkru(url) -> "OKRU"
            isYourUpload(url) -> "YOURUPLOAD"
            isHqq(url) -> "HQQ"
            isUqload(url) -> "UQLOAD"
            isDood(url) -> "DOOD"
            isJawcloud(url) -> "JAWCLOUD"
            isStreamtape(url) -> "STREAMTAPE"
            url.contains("mp4upload", true) -> "MP4UPLOAD"
            isVoe(url) -> "VOE"
            isMega(url) -> "MEGA"
            isIronHentai(url) -> "IRONHENTAI"
            url.contains("viewsb", true) || url.contains("streamsb", true) -> "VIEWSB"
            isStreamUp(url) -> "STREAMUP"
            url.contains("moovies", true) -> "MOOVIES"
            url.contains("xonaplay", true) -> "XONAPLAY"
            isMailRu(url) -> "MAILRU"
            else -> "GENERIC"
        }
    }

    private fun getBase(url: String): String {
        val uri = URI(url)
        return "${uri.scheme}://${uri.host}"
    }

    private fun cleanFoundUrl(value: String): String {
        return value
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .trim()
    }

    private fun qualityFromOkruName(name: String): Int {
        return when (name.lowercase(Locale.ROOT)) {
            "ultra" -> Qualities.P2160.value
            "quad" -> Qualities.P1440.value
            "full" -> Qualities.P1080.value
            "hd" -> Qualities.P720.value
            "sd" -> Qualities.P480.value
            "low" -> Qualities.P360.value
            "lowest" -> Qualities.P240.value
            "mobile" -> Qualities.P144.value
            else -> Qualities.Unknown.value
        }
    }

    private fun makeHeaders(referer: String): Map<String, String> {
        return mapOf(
            "User-Agent" to UA,
            "Referer" to referer,
            "Accept" to "*/*"
        )
    }

    private suspend fun extractIronHentai(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val html = app.get(url, referer = referer, headers = makeHeaders(referer)).text

            val encoded = Regex(
                """atob\(atob\(['"]([^'"]+)['"]\)\.split\(''\)\.map""",
                RegexOption.DOT_MATCHES_ALL
            ).find(html)?.groupValues?.getOrNull(1)

            val searchText = if (!encoded.isNullOrBlank()) {
                val first = String(java.util.Base64.getDecoder().decode(encoded), Charsets.ISO_8859_1)
                val shifted = first.map { (it.code - 1).toChar() }.joinToString("")
                String(java.util.Base64.getDecoder().decode(shifted), Charsets.UTF_8)
                    .replace("\\/", "/")
                    .replace("\\u0026", "&")
            } else {
                html
            }

            val m3u8 = Regex("""https?://[^"'\\\s]+\.m3u8[^"'\\\s]*""")
                .find(searchText)
                ?.value
                ?: return false

            callback.invoke(
                newExtractorLink("IRONHENTAI", "IRONHENTAI", m3u8, ExtractorLinkType.M3U8) {
                    this.referer = url
                    this.quality = Qualities.P1080.value
                }
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun extractViewSb(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val base = getBase(url)
            val page = app.get(url, referer = referer, headers = makeHeaders(referer)).text

            val iframePath = Regex(
                """<iframe[^>]+src\s*=\s*['"]([^'"]+\.html)['"]""",
                RegexOption.IGNORE_CASE
            ).find(page)?.groupValues?.getOrNull(1) ?: return false

            val iframeUrl = when {
                iframePath.startsWith("http") -> iframePath
                iframePath.startsWith("/") -> "$base$iframePath"
                else -> "$base/$iframePath"
            }

            val iframeHtml = app.get(
                iframeUrl,
                referer = url,
                headers = makeHeaders(url)
            ).text

            val appVersion = Regex("""app\.min\.(\d+)\.js""")
                .find(iframeHtml)
                ?.groupValues
                ?.getOrNull(1)
                ?: "50"

            val videoCode = iframeUrl.substringAfterLast("/").substringBefore(".html")

            fun rand(len: Int): String {
                val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
                return (1..len).map { chars.random() }.joinToString("")
            }

            fun toHex(str: String): String =
                str.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }

            val req = listOf(
                rand(12),
                videoCode,
                rand(12),
                "streamsb"
            ).joinToString("||")

            val apiUrl = "$base/sources$appVersion/${toHex(req)}"

            val jsonText = app.get(
                apiUrl,
                referer = iframeUrl,
                headers = mapOf(
                    "User-Agent" to UA,
                    "Referer" to iframeUrl,
                    "watchsb" to "sbstream",
                    "Accept" to "application/json,text/plain,*/*"
                )
            ).text

            val json = JsonParser.parseString(jsonText).asJsonObject
            val streamData = json.getAsJsonObject("stream_data") ?: return false
            val file = streamData.get("file")?.asString ?: return false

            callback.invoke(
                newExtractorLink("VIEWSB", "VIEWSB", file, ExtractorLinkType.M3U8) {
                    this.referer = iframeUrl
                    this.quality = Qualities.Unknown.value
                }
            )

            true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun extractOkru(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val doc = app.get(url, referer = referer, headers = makeHeaders(referer)).document

            val dataOptions = doc.selectFirst("div[data-options]")?.attr("data-options")
            val found = linkedSetOf<Pair<Int, String>>()

            if (!dataOptions.isNullOrBlank()) {
                val arrayData = dataOptions
                    .substringAfterLast("\\\"videos\\\":[{\\\"name\\\":\\\"")
                    .substringBefore("]")

                arrayData.split("{\\\"name\\\":\\\"").reversed().forEach {
                    val videoUrl = it.substringAfter("url\\\":\\\"")
                        .substringBefore("\\\"")
                        .replace("\\\\u0026", "&")
                        .replace("\\/", "/")

                    val qualityName = it.substringBefore("\\\"")

                    if (videoUrl.startsWith("https://")) {
                        found.add(qualityFromOkruName(qualityName) to videoUrl)
                    }
                }
            }

            val html = doc.html()
            Regex("""https://vd\d+\.mycdn\.me/[^"'\\\s]+""")
                .findAll(html)
                .forEach { found.add(Qualities.Unknown.value to cleanFoundUrl(it.value)) }

            Regex("""&quot;url&quot;:&quot;([^&]+)&quot;""")
                .findAll(html)
                .forEach { found.add(Qualities.Unknown.value to cleanFoundUrl(it.groupValues[1])) }

            var success = false
            found.forEach { (quality, videoUrl) ->
                val type = if (videoUrl.contains(".mpd", true)) ExtractorLinkType.DASH else INFER_TYPE
                callback.invoke(
                    newExtractorLink("OKRU", "OKRU", videoUrl, type) {
                        this.referer = "https://ok.ru"
                        this.quality = quality
                        this.headers = mapOf("Referer" to "https://ok.ru", "User-Agent" to UA)
                    }
                )
                success = true
            }
            success
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun extractUqload(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val html = app.get(url, referer = referer, headers = makeHeaders(referer)).text

            val link = Regex("""sources:\s*\["([^"]+)"]""")
                .find(html)?.groupValues?.getOrNull(1)
                ?: Regex("""sources\s*:\s*\[\s*["']([^"']+)["']""")
                    .find(html)?.groupValues?.getOrNull(1)
                ?: Regex("""sources:.\[(.*?)\]""")
                    .find(html)?.groupValues?.getOrNull(1)?.replace("\"", "")

            if (link.isNullOrBlank()) return false

            callback.invoke(
                newExtractorLink("UQLOAD", "UQLOAD", cleanFoundUrl(link), INFER_TYPE) {
                    this.referer = getBase(url)
                    this.quality = Qualities.Unknown.value
                }
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun extractYourUpload(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val doc = app.get(url, referer = referer, headers = makeHeaders(referer)).document
            val script = doc.select("script:containsData(jwplayerOptions), script:containsData(file:)").html()

            val videoUrl = Regex("""file:\s*'([^']+\.(?:m3u8|mp4))'""")
                .find(script)
                ?.groupValues
                ?.getOrNull(1)
                ?: Regex("""file:\s*"([^"]+\.(?:m3u8|mp4))"""")
                    .find(script)
                    ?.groupValues
                    ?.getOrNull(1)
                ?: return false

            callback.invoke(
                newExtractorLink("YOURUPLOAD", "YOURUPLOAD", videoUrl, INFER_TYPE) {
                    this.referer = "https://www.yourupload.com"
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf("Referer" to "https://www.yourupload.com", "User-Agent" to UA)
                }
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun extractDood(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val embedUrl = url.replace("/d/", "/e/")
            val first = app.get(embedUrl, referer = referer, headers = makeHeaders(referer))
            val html = first.text

            val finalUrl = first.url.ifBlank { embedUrl }
            val finalBase = getBase(finalUrl)

            val md5Path = Regex("""/pass_md5/[^']*""")
                .find(html)
                ?.value
                ?: return false

            val md5Url = finalBase + md5Path
            val videoPrefix = app.get(md5Url, referer = finalUrl, headers = makeHeaders(finalUrl)).text

            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            val random = (1..10).map { alphabet.random() }.joinToString("")

            val videoUrl = videoPrefix + random + "?token=${md5Url.substringAfterLast("/")}"

            callback.invoke(
                newExtractorLink("DOOD", "DOOD", videoUrl, INFER_TYPE) {
                    this.referer = finalBase
                    this.quality = Qualities.Unknown.value
                }
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun extractStreamtape(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val base = getBase(url)
            val html = app.get(url, referer = referer, headers = makeHeaders(referer)).text

            val match = Regex(
                """document\.getElementById\('botlink'\)\.innerHTML\s*=\s*'([^']+)'\s*\+\s*\('([^']+)'\)\.substring\(([0-9]+)\)"""
            ).find(html) ?: return false

            val paramString = match.groupValues[2]
            val substringIndex = match.groupValues[3].toIntOrNull() ?: 0
            val cleanParams = paramString.substring(substringIndex)

            val videoId = Regex("""id=([^&]+)""").find(cleanParams)?.groupValues?.get(1) ?: return false
            val expires = Regex("""expires=([^&]+)""").find(cleanParams)?.groupValues?.get(1) ?: return false
            val ip = Regex("""ip=([^&]+)""").find(cleanParams)?.groupValues?.get(1) ?: return false
            val token = Regex("""token=([^&]+)""").find(cleanParams)?.groupValues?.get(1) ?: return false

            val getVideo = "$base/get_video?id=$videoId&expires=$expires&ip=$ip&token=$token&stream=1"
            val res = app.get(getVideo, referer = url, headers = makeHeaders(url))
            val finalVideoUrl = res.url.ifBlank { getVideo }

            callback.invoke(
                newExtractorLink("STREAMTAPE", "STREAMTAPE", finalVideoUrl, INFER_TYPE) {
                    this.referer = base
                    this.quality = Qualities.Unknown.value
                }
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun extractVoe(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val firstHtml = app.get(url, referer = url, headers = makeHeaders(url) + ("X-Requested-With" to "XMLHttpRequest")).text

            val redirectBase = Regex("""https://([a-zA-Z0-9.-]+)(?:/[^'"]*)?""")
                .find(firstHtml)
                ?.groupValues
                ?.getOrNull(1)
                ?.let { "https://$it/" }

            val pageUrl = if (!redirectBase.isNullOrBlank()) {
                val parsed = URL(url)
                redirectBase.trimEnd('/') + parsed.path + if (parsed.query != null) "?${parsed.query}" else ""
            } else {
                url
            }

            val html = app.get(pageUrl, referer = url, headers = makeHeaders(url) + ("X-Requested-With" to "XMLHttpRequest")).text

            val encoded = findVoeEncoded(html) ?: return false
            val json = decryptVoe(encoded)
            val m3u8 = json.get("source")?.asString.orEmpty()
            if (m3u8.isBlank()) return false

            val captions = json.getAsJsonArray("captions")
            captions?.forEach { item ->
                try {
                    val obj = item.asJsonObject
                    val file = obj.get("file")?.asString.orEmpty()
                    val label = obj.get("label")?.asString ?: "VOE"
                    if (file.startsWith("http")) {
                        // No sabemos si Cloudstream quiere vtt/srt; se pasa directo.
                        // subtitleCallback.invoke(SubtitleFile(label, file))
                    }
                } catch (_: Exception) {}
            }

            callback.invoke(
                newExtractorLink("VOE", "VOE", m3u8, ExtractorLinkType.M3U8) {
                    this.referer = pageUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun findVoeEncoded(source: String): String? {
        Regex("""<script\s+type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(source)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { if (it.isNotBlank()) return it.trim() }

        return Regex("""["']?data["']?\s*:\s*["']([^"']{100,})["']""")
            .find(source)
            ?.groupValues
            ?.getOrNull(1)
    }

    private fun decryptVoe(encodedString: String): JsonObject {
        return try {
            val vF = rot13(encodedString)
            val vF2 = replacePatterns(vF)
            val vF3 = vF2.replace("_", "")
            val vF4 = Base64.decode(vF3, Base64.NO_WRAP).toString(Charsets.UTF_8)
            val vF5 = vF4.map { (it.code - 3).toChar() }.joinToString("")
            val vF6 = vF5.reversed()
            val vAtob = Base64.decode(vF6, Base64.NO_WRAP).toString(Charsets.UTF_8)
            JsonParser.parseString(vAtob).asJsonObject
        } catch (_: Exception) {
            JsonObject()
        }
    }

    private fun rot13(input: String): String {
        return input.map { c ->
            when (c) {
                in 'A'..'Z' -> ((c - 'A' + 13) % 26 + 'A'.code).toChar()
                in 'a'..'z' -> ((c - 'a' + 13) % 26 + 'a'.code).toChar()
                else -> c
            }
        }.joinToString("")
    }

    private fun replacePatterns(input: String): String {
        val patterns = listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&")
        return patterns.fold(input) { result, pattern ->
            result.replace(Regex(Regex.escape(pattern)), "_")
        }
    }

    private suspend fun extractJawcloud(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val doc = app.get(url, referer = referer, headers = makeHeaders(referer)).document
            val link = doc.select("source").attr("src").ifBlank {
                doc.select("video").attr("src")
            }
            if (link.isBlank()) return false

            if (link.contains(".m3u8", true)) {
                M3u8Helper.generateM3u8("JAWCLOUD", link, url).forEach(callback)
            } else {
                callback.invoke(
                    newExtractorLink("JAWCLOUD", "JAWCLOUD", link, INFER_TYPE) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun extractZilla(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val id = url.substringAfterLast("/")
            val m3u8 = "https://player.zilla-networks.com/m3u8/$id"
            callback.invoke(
                newExtractorLink("ZILLA", "ZILLA", m3u8, ExtractorLinkType.M3U8) {
                    this.referer = url
                    this.quality = Qualities.P1080.value
                    this.headers = mapOf(
                        "User-Agent" to UA,
                        "Accept" to "*/*",
                        "Referer" to url,
                        "Origin" to "https://player.zilla-networks.com"
                    )
                }
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun extractStreamUp(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val base = getBase(url)
            val fileCode = URL(url).path.split("/").lastOrNull { it.isNotEmpty() } ?: return false
            val api = "$base/ajax/stream?filecode=$fileCode"
            val text = app.get(api, headers = makeHeaders("$base/v/$fileCode")).text
            val json = JsonParser.parseString(text).asJsonObject
            val stream = json.get("streaming_url")?.asString ?: return false

            callback.invoke(
                newExtractorLink("STREAMUP", "STREAMUP", stream, INFER_TYPE) {
                    this.referer = "$base/v/$fileCode"
                    this.quality = Qualities.Unknown.value
                }
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun extractUpZur(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val html = app.get(url, referer = referer, headers = makeHeaders(referer)).text
            val arrayData = Regex("""var uHo4sc = \[(.*?)]""")
                .find(html)
                ?.groupValues
                ?.getOrNull(1)
                ?: return false

            val decodedString = arrayData.split(",").map {
                it.trim().removeSurrounding("\"").removeSurrounding("'")
            }.reversed().joinToString("") { part ->
                val builder = StringBuilder()
                var i = 0
                while (i < part.length) {
                    if (part.startsWith("\\x", i) && i + 3 < part.length) {
                        val hex = part.substring(i + 2, i + 4)
                        builder.append(hex.toInt(16).toChar())
                        i += 4
                    } else if (part.startsWith("\\u", i) && i + 5 < part.length) {
                        val hex = part.substring(i + 2, i + 6)
                        builder.append(hex.toInt(16).toChar())
                        i += 6
                    } else {
                        builder.append(part[i])
                        i++
                    }
                }
                builder.toString()
            }

            val stream = Regex("""src\s*=\s*["']([^"']+)["']""")
                .find(decodedString)
                ?.groupValues
                ?.getOrNull(1)
                ?: return false

            callback.invoke(
                newExtractorLink("UPZUR", "UPZUR", absolutize(stream, url), INFER_TYPE) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                }
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun extractVidGuard(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val html = app.get(url, referer = referer, headers = makeHeaders(referer)).text
            val scriptData = html
                .substringAfter("eval(function(p,a,c,k,e,d)")
                .substringBefore("</script>")
                .let { "eval(function(p,a,c,k,e,d)$it" }

            if (!scriptData.startsWith("eval")) return false

            val unpacked = JsUnpacker(scriptData).unpack() ?: return false

            val encoded = unpacked
                .substringAfter("window.svg={\"stream\":\"")
                .substringBefore("\",\"hash")

            if (encoded.isBlank()) return false

            val finalUrl = sigDecodeVidGuard(encoded)

            callback.invoke(
                newExtractorLink("VIDGUARD", "VIDGUARD", finalUrl, INFER_TYPE) {
                    this.referer = getBase(url)
                    this.quality = Qualities.Unknown.value
                }
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun sigDecodeVidGuard(url: String): String {
        val sig = url.split("sig=")[1].split("&")[0]
        val decodedSig = sig.chunked(2)
            .joinToString("") { (Integer.parseInt(it, 16) xor 2).toChar().toString() }
            .let {
                val padding = when (it.length % 4) {
                    2 -> "=="
                    3 -> "="
                    else -> ""
                }
                String(Base64.decode((it + padding).toByteArray(), Base64.DEFAULT))
            }
            .dropLast(5)
            .reversed()
            .toCharArray()
            .apply {
                for (i in indices step 2) {
                    if (i + 1 < size) {
                        this[i] = this[i + 1].also { this[i + 1] = this[i] }
                    }
                }
            }
            .concatToString()
            .dropLast(5)
        return url.replace(sig, decodedSig)
    }

    private suspend fun extractVidHide(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val base = getBase(url)
            val html = app.get(
                url,
                referer = referer.ifBlank { base },
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0",
                    "Referer" to referer.ifBlank { base },
                    "Origin" to referer.ifBlank { base }
                )
            ).text

            val packed = Regex("""(eval\(function\(p,a,c,k,e,d\)(.|\n)*?)</script>""")
                .find(html)
                ?.groupValues
                ?.getOrNull(1)
                ?: return false

            val unpacked = JsUnpacker(packed).unpack() ?: return false

            val links = mutableMapOf<String, String>()
            Regex("""["'](hls\d+)["']\s*:\s*["'](.*?)["']""")
                .findAll(unpacked)
                .forEach {
                    links[it.groupValues[1]] = it.groupValues[2]
                }

            val finalUrl = links["hls4"] ?: links["hls2"] ?: links.values.firstOrNull() ?: return false
            val completeUrl = absolutize(finalUrl, url)

            callback.invoke(
                newExtractorLink("VIDHIDE", "VIDHIDE", completeUrl, ExtractorLinkType.M3U8) {
                    this.referer = base
                    this.quality = Qualities.Unknown.value
                }
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun extractMailRu(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val id = Regex("""embed/([0-9]+)""")
                .find(url)
                ?.groupValues
                ?.getOrNull(1)
                ?: return false

            val timestamp = System.currentTimeMillis()
            val metaUrl = "https://my.mail.ru/+/video/meta/$id?xemail=&ajax_call=1&func_name=&mna=&mnb=&ext=1&_=$timestamp"

            val text = app.get(metaUrl, headers = makeHeaders(referer)).text
            val json = JsonParser.parseString(text).asJsonObject
            val videos = json.getAsJsonArray("videos") ?: return false

            var success = false
            videos.forEach { item ->
                val obj = item.asJsonObject
                var streamUrl = obj.get("url")?.asString ?: return@forEach
                if (streamUrl.startsWith("//")) streamUrl = "https:$streamUrl"

                if (streamUrl.startsWith("http")) {
                    callback.invoke(
                        newExtractorLink("MAILRU", "MAILRU", streamUrl, INFER_TYPE) {
                            this.referer = "https://my.mail.ru"
                            this.quality = Qualities.Unknown.value
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

    private suspend fun extractFembedLike(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val id = url
                .substringAfterLast("/v/")
                .substringAfterLast("/e/")
                .substringAfterLast("/f/")
                .substringBefore("?")
                .substringBefore("/")

            if (id.isBlank() || id == url) return false

            val hosts = listOf(
                getBase(url),
                "https://www.fembed.com",
                "https://fembed.com",
                "https://fembed.sx",
                "https://feurl.com"
            ).distinct()

            for (host in hosts) {
                try {
                    val api = "$host/api/source/$id"
                    val res = app.post(
                        api,
                        referer = url,
                        headers = mapOf(
                            "User-Agent" to UA,
                            "Referer" to url,
                            "Origin" to host,
                            "X-Requested-With" to "XMLHttpRequest",
                            "Accept" to "application/json, text/javascript, */*; q=0.01"
                        )
                    ).text

                    val json = JsonParser.parseString(res).asJsonObject
                    val data = json.getAsJsonArray("data") ?: continue

                    var success = false
                    data.forEach { item ->
                        val obj = item.asJsonObject
                        val file = obj.get("file")?.asString ?: return@forEach
                        val label = obj.get("label")?.asString ?: ""

                        callback.invoke(
                            newExtractorLink("FEMBED", "FEMBED", cleanFoundUrl(file), INFER_TYPE) {
                                this.referer = url
                                this.quality = getQualityFromName(label)
                            }
                        )
                        success = true
                    }

                    if (success) return true
                } catch (_: Exception) {}
            }

            false
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun extractByse(
        embedUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val id = Regex("""/(?:e|d)/([A-Za-z0-9]+)""")
                .find(embedUrl)
                ?.groupValues
                ?.getOrNull(1)
                ?: return false

            val base = getBase(embedUrl)
            val embedRes = app.get(embedUrl, referer = referer, headers = makeHeaders(referer))
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
                    "X-Embed-Origin" to safeHost(referer),
                    "X-Embed-Referer" to referer
                )
            ).text

            val details = Gson().fromJson(detailsText, DetailsResponse::class.java)
            val embedFrame = details.embed_frame_url ?: embedUrl

            try {
                app.get(embedUrl, referer = referer, headers = mapOf("User-Agent" to UA))
                app.get(embedFrame, referer = embedUrl, headers = mapOf("User-Agent" to UA))
            } catch (_: Exception) {}

            val playbackBase = if (embedFrame.contains("f75s.com")) "https://f75s.com" else base

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
                    "X-Embed-Origin" to safeHost(referer),
                    "X-Embed-Parent" to embedUrl,
                    "X-Embed-Referer" to referer,
                    "Cookie" to cookie
                )
            ).text

            val playback = Gson().fromJson(playbackText, PlaybackResponse::class.java).playback ?: return false
            val decrypted = decryptPlayback(playback) ?: return false
            val sources = Gson().fromJson(decrypted, DecryptedPlayback::class.java).sources ?: return false

            var success = false
            sources.forEach { src ->
                src.url?.let { videoUrl ->
                    callback.invoke(
                        newExtractorLink("BYSE", "BYSE", videoUrl, if (videoUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else INFER_TYPE) {
                            this.referer = embedFrame
                            this.quality = Qualities.Unknown.value
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
            val decoder = java.util.Base64.getUrlDecoder()
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

    private suspend fun extractHqq(
        embedUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val realEmbed = if (embedUrl.contains("/f/")) embedUrl.replace("/f/", "/e/") else embedUrl
            val base = getBase(realEmbed)

            // 1) fallback rápido directo al HTML
            val quick = tryGeneric(realEmbed, referer, "HQQ", callback)
            if (quick) return true

            // 2) flujo viejo / API player
            val pageRes = app.get(realEmbed, referer = referer, headers = makeHeaders(referer))
            val html = pageRes.text
            val cookies = pageRes.cookies

            val mediaId = realEmbed.substringAfterLast("/").substringBefore("?")
            val videoKey = extractLiteral(html, """['"]videokey['"]\s*:\s*['"]([^'"]+)['"]""", mediaId)
                .ifBlank { extractJsVar(html, "videokey", mediaId) }
                .ifBlank { extractJsVar(html, "videokeyorig", mediaId) }

            val videoId = extractLiteral(html, """['"]videoid['"]\s*:\s*['"]([^'"]+)['"]""")
                .ifBlank { extractJsVar(html, "videoid") }

            val adbn = extractJsVar(html, "adbn")
            val secure = extractJsVar(html, "secure", "0")
            val htoken = extractJsVar(html, "htoken")
            val gtr = extractJsVar(html, "gtr")
            val embedFrm = extractJsVar(html, "embedfrm", "0")

            if (videoKey.isBlank() || videoId.isBlank() || adbn.isBlank()) return false

            val cookieHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

            val ajaxHeaders = mapOf(
                "User-Agent" to UA,
                "Referer" to realEmbed,
                "Origin" to base,
                "X-Requested-With" to "XMLHttpRequest",
                "Content-Type" to "application/json",
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "Cookie" to cookieHeader
            )

            val imageBody =
                """{"videoid":"${encodeJson(videoId)}","videokey":"${encodeJson(videoKey)}","width":407,"height":400}"""

            val imageText = app.post(
                "$base/player/get_player_image.php",
                referer = realEmbed,
                headers = ajaxHeaders,
                requestBody = imageBody.toRequestBody(jsonType)
            ).text

            val imageJson = JsonParser.parseString(imageText).asJsonObject
            if (imageJson.has("try_again") && imageJson["try_again"].asString == "1") return false

            val hashImage = imageJson.get("hash_image")?.asString ?: return false
            val encodedHash = urlEncode(hashImage)

            val coords = linkedSetOf<Pair<Int, Int>>()
            coords.add(203 to 200)
            coords.add(203 to 220)
            coords.add(203 to 180)
            coords.add(190 to 200)
            coords.add(215 to 200)
            coords.add(478 to 114)
            coords.add(841 to 179)
            coords.add(169 to 332)

            for ((clickX, clickY) in coords) {
                val md5Body = """
                    {
                      "htoken":"${encodeJson(htoken)}",
                      "sh":"${randomSha1()}",
                      "ver":"4",
                      "secure":"${encodeJson(secure)}",
                      "adb":"${encodeJson(adbn)}",
                      "v":"${encodeJson(videoKey)}",
                      "token":"",
                      "gt":"${encodeJson(gtr)}",
                      "embed_from":"${encodeJson(embedFrm)}",
                      "wasmcheck":1,
                      "adscore":"",
                      "click_hash":"${encodeJson(encodedHash)}",
                      "clickx":$clickX,
                      "clicky":$clickY
                    }
                """.trimIndent()

                val md5Text = app.post(
                    "$base/player/get_md5.php",
                    referer = realEmbed,
                    headers = ajaxHeaders,
                    requestBody = md5Body.toRequestBody(jsonType)
                ).text

                val md5Json = JsonParser.parseString(md5Text).asJsonObject

                if (
                    (md5Json.has("try_again") && md5Json["try_again"].asString == "1") ||
                    (md5Json.has("need_captcha") && md5Json["need_captcha"].asString == "1") ||
                    (md5Json.has("wrong_recaptcha") && md5Json["wrong_recaptcha"].asString == "1")
                ) continue

                val rawUrl = md5Json.get("html5_file")?.asString ?: md5Json.get("link")?.asString
                val streamUrl = if (!rawUrl.isNullOrBlank()) {
                    normalizeHqqStream(rawUrl)
                } else {
                    val obf = md5Json.get("obf_link")?.asString ?: continue
                    val decoded = decodeHqqObfLink(obf)
                    if (decoded.isBlank()) continue
                    normalizeHqqStream(decoded)
                }

                callback.invoke(
                    newExtractorLink("HQQ", "HQQ", streamUrl, ExtractorLinkType.M3U8) {
                        this.referer = base
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }

            false
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun tryGeneric(
        url: String,
        referer: String,
        sourceName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val res = app.get(url, referer = referer, headers = makeHeaders(referer))
            var searchText = res.text

            if (searchText.contains("eval(function(p,a,c,k,e")) {
                val unpacker = JsUnpacker(searchText)
                if (unpacker.detect()) {
                    unpacker.unpack()?.let { searchText = it }
                }
            }

            val found = linkedSetOf<String>()

            Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
                .findAll(searchText)
                .forEach { found.add(cleanFoundUrl(it.value)) }

            Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*""")
                .findAll(searchText)
                .forEach { found.add(cleanFoundUrl(it.value)) }

            Regex("""file\s*:\s*["']([^"']+)""")
                .findAll(searchText)
                .forEach { found.add(cleanFoundUrl(it.groupValues[1])) }

            Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*["']([^"']+)""")
                .findAll(searchText)
                .forEach { found.add(cleanFoundUrl(it.groupValues[1])) }

            Regex("""["']?src["']?\s*:\s*["']([^"']+)""")
                .findAll(searchText)
                .forEach { found.add(cleanFoundUrl(it.groupValues[1])) }

            var success = false
            found.forEach { videoUrl ->
                if (!videoUrl.startsWith("http")) return@forEach
                callback.invoke(
                    newExtractorLink(
                        sourceName,
                        sourceName,
                        videoUrl,
                        if (videoUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else INFER_TYPE
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
                success = true
            }
            success
        } catch (_: Exception) {
            false
        }
    }

    private fun absolutize(value: String, pageUrl: String): String {
        return when {
            value.startsWith("http://") || value.startsWith("https://") -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> {
                val parsed = URL(pageUrl)
                "${parsed.protocol}://${parsed.host}$value"
            }
            else -> value
        }
    }

    private fun safeHost(url: String): String {
        return try {
            URI(url).host ?: url
        } catch (_: Exception) {
            url
        }
    }

    private fun extractJsVar(html: String, name: String, default: String = ""): String {
        return Regex("""(?:var\s+)?${Regex.escape(name)}\s*=\s*['"]([^'"]*)['"]""")
            .find(html)?.groupValues?.get(1) ?: default
    }

    private fun extractLiteral(html: String, pattern: String, default: String = ""): String {
        return Regex(pattern, RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1) ?: default
    }

    private fun randomSha1(): String {
        val chars = "0123456789abcdef"
        return (1..40).map { chars.random() }.joinToString("")
    }

    private fun encodeJson(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "")
            .replace("\r", "")
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun decodeHqqObfLink(value: String?): String {
        if (value.isNullOrBlank() || value == "#") return ""
        if (value.contains(".")) return value

        val clean = value.drop(1)
        val out = StringBuilder()
        var i = 0
        while (i + 3 <= clean.length) {
            val chunk = clean.substring(i, i + 3)
            val code = chunk.toIntOrNull(16) ?: return ""
            out.append(code.toChar())
            i += 3
        }
        return out.toString()
    }

    private fun normalizeHqqStream(obf: String): String {
        var url = obf.replace("\\/", "/").trim()
        if (url.startsWith("//")) url = "https:$url"
        else if (!url.startsWith("http")) url = "https:$url"

        if (!url.contains(".m3u8") && !url.contains(".mp4")) {
            url += ".mp4.m3u8"
        }
        return url
    }

    data class DetailsResponse(val embed_frame_url: String?)
    data class PlaybackResponse(val playback: PlaybackData?)
    data class PlaybackData(val iv: String, val payload: String, val key_parts: List<String>)
    data class DecryptedPlayback(val sources: List<DecryptedSource>?)
    data class DecryptedSource(val url: String?)

    class JsUnpacker(packedJS: String?) {
        private var packedJS: String? = packedJS

        fun detect(): Boolean {
            val js = packedJS?.replace(" ", "") ?: return false
            val p = java.util.regex.Pattern.compile("eval\\(function\\(p,a,c,k,e,[rd]")
            val m = p.matcher(js)
            return m.find()
        }

        fun unpack(): String? {
            val js = packedJS ?: return null
            return try {
                var p = java.util.regex.Pattern.compile(
                    """\}\s*\('(.*)',\s*(.*?),\s*(\d+),\s*'(.*?)'\.split\('\|'\)""",
                    java.util.regex.Pattern.DOTALL
                )
                var m = p.matcher(js)
                if (m.find() && m.groupCount() == 4) {
                    val payload = m.group(1).replace("\\'", "'")
                    val radixStr = m.group(2)
                    val countStr = m.group(3)
                    val symtab = m.group(4).split("\\|".toRegex()).toTypedArray()

                    val radix = radixStr.toIntOrNull() ?: 36
                    val count = countStr.toIntOrNull() ?: 0
                    if (symtab.size != count) return null

                    val unbase = Unbase(radix)
                    p = java.util.regex.Pattern.compile("\\b\\w+\\b")
                    m = p.matcher(payload)
                    val decoded = StringBuilder(payload)
                    var replaceOffset = 0

                    while (m.find()) {
                        val word = m.group(0)
                        val x = try {
                            unbase.unbase(word)
                        } catch (_: Exception) {
                            break
                        }

                        val value = if (x < symtab.size && x >= 0) symtab[x] else null
                        if (!value.isNullOrEmpty()) {
                            decoded.replace(m.start() + replaceOffset, m.end() + replaceOffset, value)
                            replaceOffset += value.length - word.length
                        }
                    }
                    decoded.toString()
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
        }

        private inner class Unbase(private val radix: Int) {
            private val alphabet62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
            private val alphabet95 =
                " !\"#$%&\\'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\\\]^_`abcdefghijklmnopqrstuvwxyz{|}~"

            private var alphabet: String? = null
            private var dictionary: HashMap<String, Int>? = null

            fun unbase(str: String): Int {
                var ret = 0
                if (alphabet == null) {
                    ret = str.toInt(radix)
                } else {
                    val tmp = StringBuilder(str).reverse().toString()
                    for (i in tmp.indices) {
                        ret += (radix.toDouble().pow(i.toDouble()) * dictionary!![tmp.substring(i, i + 1)]!!).toInt()
                    }
                }
                return ret
            }

            init {
                if (radix > 36) {
                    when {
                        radix < 62 -> alphabet = alphabet62.substring(0, radix)
                        radix in 63..94 -> alphabet = alphabet95.substring(0, radix)
                        radix == 62 -> alphabet = alphabet62
                        radix == 95 -> alphabet = alphabet95
                    }

                    dictionary = HashMap(95)
                    for (i in 0 until alphabet!!.length) {
                        dictionary!![alphabet!!.substring(i, i + 1)] = i
                    }
                }
            }
        }
    }
}