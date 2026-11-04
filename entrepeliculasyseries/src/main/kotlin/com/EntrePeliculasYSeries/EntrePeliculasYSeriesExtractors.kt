package com.EntrePeliculasYSeries

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import org.json.JSONObject

// ==================== VOe (Adaptado de GnulaHD) ====================

open class EntrePeliculasVoe : ExtractorApi() {
    override val name = "Voe"
    override var mainUrl = "https://voe.sx"
    override val requiresReferer = true
    private val redirectRegex = Regex("""window\.location\.href\s*=\s*'([^']+)'""")

    override suspend fun getUrl(
        url: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var currentUrl = url
        var res = app.get(currentUrl, referer = referer)
        val redirectMatch = redirectRegex.find(res.document.data())
        if (redirectMatch != null) {
            val redirectUrl = redirectMatch.groupValues[1]
            res = app.get(redirectUrl, referer = referer)
            currentUrl = redirectUrl
        }
        val encodedString = res.document.selectFirst("script[type=application/json]")?.data()?.trim()
            ?.substringAfter("[\"")?.substringBeforeLast("\"]") ?: return
        val json = decryptF7(encodedString)
        val m3u8 = json.optString("source")
        val mp4 = json.optString("direct_access_url")
        if (m3u8.isNotEmpty()) {
            M3u8Helper.generateM3u8(name, m3u8, "$mainUrl/", headers = mapOf("Origin" to "$mainUrl/")).forEach(callback)
        }
        if (mp4.isNotEmpty()) {
            callback.invoke(
                ExtractorLink(
                    source = "$name MP4",
                    name = "$name MP4",
                    url = mp4,
                    referer = currentUrl,
                    quality = Qualities.Unknown.value,
                    type = ExtractorLinkType.VIDEO
                )
            )
        }
    }

    private fun decryptF7(p8: String): JSONObject = try {
        val vF = rot13(p8)
        val vF2 = replacePatterns(vF)
        val vF3 = removeUnderscores(vF2)
        val vF4 = base64Decode(vF3)
        val vF5 = charShift(vF4, 3)
        val vF6 = reverse(vF5)
        JSONObject(base64Decode(vF6))
    } catch (e: Exception) { JSONObject() }

    private fun rot13(input: String) = input.map { c ->
        when (c) {
            in 'A'..'Z' -> ((c - 'A' + 13) % 26 + 'A'.code).toChar()
            in 'a'..'z' -> ((c - 'a' + 13) % 26 + 'a'.code).toChar()
            else -> c
        }
    }.joinToString("")

    private fun replacePatterns(input: String): String {
        var r = input
        for (p in listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&")) r = r.replace(p, "_")
        return r
    }
    private fun removeUnderscores(i: String) = i.replace("_", "")
    private fun charShift(i: String, s: Int) = i.map { (it.code - s).toChar() }.joinToString("")
    private fun reverse(i: String) = i.reversed()
}

// ==================== Vidhide (Adaptado del JS) ====================

open class EntrePeliculasVidhide : ExtractorApi() {
    override val name = "Vidhide"
    override val mainUrl = "https://vidhidepro.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var targetUrl = url
        if (targetUrl.contains("/d/")) targetUrl = targetUrl.replace("/d/", "/v/")

        val res = app.get(
            targetUrl,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Referer" to (referer ?: mainUrl),
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
        )
        if (!res.isSuccessful) return
        val html = res.text
        var streamUrl: String? = null

        val marker = "eval(function(p,a,c,k,e,d)"
        val markerStart = html.indexOf(marker)
        if (markerStart != -1) {
            val end = html.indexOf(".split('|')))", markerStart)
            if (end != -1) {
                val block = html.substring(markerStart, end + 13)
                val unpacked = unpackDeanEdwards(block)
                val m3u8Match = Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""").find(unpacked)
                if (m3u8Match != null) streamUrl = m3u8Match.value
            }
        }

        if (streamUrl == null) {
            val rawMatch = Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""").find(html)
            if (rawMatch != null) streamUrl = rawMatch.value
        }

        if (streamUrl != null) {
            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = name,
                    url = streamUrl,
                    referer = targetUrl,
                    quality = Qualities.Unknown.value,
                    type = ExtractorLinkType.M3U8
                )
            )
        }
    }

    private fun unpackDeanEdwards(packed: String): String {
        return try {
            val match = Regex("""\}\s*\(\s*(['"][\s\S]*?['"])\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(['"][\s\S]*?['"])\.split\(['"]\|['"]\)""").find(packed)
                ?: return ""
            var p = match.groupValues[1].substring(1, match.groupValues[1].length - 1)
            p = p.replace("\\'", "'").replace("\\\\", "\\")
            val a = match.groupValues[2].toInt()
            val k = match.groupValues[4].substring(1, match.groupValues[4].length - 1).split("|")

            val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

            val unbase = fun(valStr: String, base: Int): Int {
                if (base <= 36) {
                    return valStr.toIntOrNull(base) ?: -1
                }
                var result = 0
                for (c in valStr) {
                    val idx = chars.indexOf(c)
                    if (idx == -1) return -1
                    result = result * base + idx
                }
                return result
            }

            Regex("""\b\w+\b""").replace(p) { m ->
                val idx = unbase(m.value, a)
                if (idx >= 0 && idx < k.size && k[idx].isNotEmpty()) k[idx] else m.value
            }
        } catch (e: Exception) { "" }
    }
}

// ==================== StreamWish (Adaptado del JS) ====================

open class EntrePeliculasStreamWish : ExtractorApi() {
    override val name = "StreamWish"
    override val mainUrl = "https://streamwish.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var targetUrl = url
        if (targetUrl.contains("shannoncoldalready")) {
            val code = targetUrl.substringAfterLast("/").substringBefore("?")
            targetUrl = "https://streamwish.to/e/$code"
        }

        val res = app.get(
            targetUrl,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Referer" to (referer ?: "https://entrepeliculasyseries.nz/")
            )
        )
        if (!res.isSuccessful) return
        val html = res.text

        val m3u8Match = Regex("""(?:file|source)\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(html)
        val m3u8Url = m3u8Match?.groupValues?.get(1)
            ?: Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""").find(html)?.value

        if (m3u8Url != null) {
            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = name,
                    url = m3u8Url.replace("\\/", "/"),
                    referer = targetUrl,
                    quality = Qualities.Unknown.value,
                    type = ExtractorLinkType.M3U8
                )
            )
        }
    }
}