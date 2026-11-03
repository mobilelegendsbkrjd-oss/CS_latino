package com.gnulahd

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import org.json.JSONObject
import org.jsoup.Jsoup

// ==================== Voe + alias ====================

open class Voe : ExtractorApi() {
    override val name = "Voe"
    override val mainUrl = "https://voe.sx"
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

class Tubeless : Voe() { override val name = "Tubeless"; override val mainUrl = "https://tubelessceliolymph.com" }
class Simpulumlamerop : Voe() { override val name = "Simplum"; override var mainUrl = "https://simpulumlamerop.com" }
class Urochsunloath : Voe() { override val name = "Uroch"; override var mainUrl = "https://urochsunloath.com" }
class NathanFromSubject : Voe() { override var mainUrl = "https://nathanfromsubject.com" }
class Yipsu : Voe() { override val name = "Yipsu"; override var mainUrl = "https://yip.su" }
class MetaGnathTuggers : Voe() { override val name = "Metagnath"; override val mainUrl = "https://metagnathtuggers.com" }
class Voe1 : Voe() { override var mainUrl = "https://donaldlineelse.com" }
class CrystalTreatmentEast : Voe() { override val name = "CrystalTreatment"; override val mainUrl = "https://crystaltreatmenteast.com" }
class Ralphy : Voe() { override var mainUrl = "https://ralphysuccessfull.org" }
class Bryantenunder : Voe() { override var mainUrl = "https://bryantenunder.com" }
class Johnbeyondnation : Voe() { override var mainUrl = "https://johnbeyondnation.com" }
class Pamelachangemission : Voe() { override var mainUrl = "https://pamelachangemission.com" }
class Teresapoliticallearn : Voe() { override var mainUrl = "https://teresapoliticallearn.com" }

// ==================== Savefiles ====================

open class SaveFilesExtractor : ExtractorApi() {
    override val name = "Savefiles"
    override val mainUrl = "https://savefiles.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fileCode = url.substringAfterLast("/").substringBefore("?").trim()
        if (fileCode.isEmpty()) return
        val baseUrl = "https://savefiles.com"

        val res = app.post(
            "$baseUrl/dl",
            data = mapOf(
                "op" to "embed",
                "file_code" to fileCode,
                "auto" to "0",
                "referer" to ""
            ),
            headers = mapOf(
                "Referer" to url,
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )
        )
        val html = res.text
        val script = Jsoup.parse(html).select("script[type=text/javascript]").firstOrNull {
            it.data().contains("jwplayer") && it.data().contains("sources") && it.data().contains("file")
        }?.data() ?: return
        val m3u8 = Regex("""file\s*:\s*["']([^"']+)["']""").find(script)?.groupValues?.get(1) ?: return

        callback.invoke(
            ExtractorLink(
                source = name,
                name = name,
                url = m3u8,
                referer = "$baseUrl/",
                quality = Qualities.Unknown.value,
                type = ExtractorLinkType.M3U8,
                headers = mapOf("Referer" to "$baseUrl/")
            )
        )
    }
}

class StreamHlsTo : SaveFilesExtractor() {
    override val name = "StreamHlsTo"
    override val mainUrl = "https://streamhls.to"
}

// ==================== Vidaraa ====================
// No necesita extractor propio: en GnulaHD.loadLinks ya se construye la URL del panel como M3U8.