package com.latanime

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.random.Random

/**
 * Normaliza dominios (estilo SoloLatino fixHostsLinks).
 * Importante: NO romper dominios que el CORE reconoce (voe.sx, mxdrop.to, filemoon.sx).
 */
fun fixHostsLinks(url: String): String {
    var fixed = url.trim()
    if (fixed.startsWith("//")) fixed = "https:$fixed"

    return fixed
        .replace("\\/", "/")
        .replace("&amp;", "&")
        // Dood family -> dood.la (nuestro extractor)
        .replace("https://doodstream.com", "https://dood.la")
        .replace("https://do7go.com", "https://dood.la")
        .replace("https://dsvplay.com", "https://dood.la")
        .replace("https://playmogo.com", "https://dood.la")
        .replace("https://myvidplay.com", "https://dood.la")
        .replace("https://vide0.net", "https://dood.la")
        .replace("https://ds2play.com", "https://dood.la")
        .replace("doodstream.com", "dood.la")
        .replace("do7go.com", "dood.la")
        .replace("dsvplay.com", "dood.la")
        .replace("playmogo.com", "dood.la")
        .replace("myvidplay.com", "dood.la")
        .replace("vide0.net", "dood.la")
        .replace("ds2play.com", "dood.la")
        // Filemoon aliases
        .replace("https://filemoon.link", "https://filemoon.sx")
        .replace("https://filemoon.lat", "https://filemoon.sx")
        .replace("filemoon.link", "filemoon.sx")
        .replace("filemoon.lat", "filemoon.sx")
        // Lulu
        .replace("https://lulu.st", "https://lulustream.com")
        .replace("https://luluvid.com", "https://lulustream.com")
        .replace("lulu.st", "lulustream.com")
        .replace("luluvid.com", "lulustream.com")
        .replace("lulu.st", "lulustream.com")
        .replace("https://lulu.st", "https://lulustream.com")
        // Streamwish / Vidhide
        .replace("https://hglink.to", "https://streamwish.to")
        .replace("https://swdyu.com", "https://streamwish.to")
        .replace("https://wishembed.com", "https://streamwish.to")
        .replace("https://cybervynx.com", "https://streamwish.to")
        .replace("https://dumbalag.com", "https://streamwish.to")
        .replace("https://mivalyo.com", "https://vidhidepro.com")
        .replace("https://dinisglows.com", "https://vidhidepro.com")
        .replace("https://dhtpre.com", "https://vidhidepro.com")
        .replace("https://vidhide.com", "https://vidhidepro.com")
        .replace("https://uqload.io", "https://uqload.com")
        .replace("https://sblona.com", "https://watchsb.com")
        .replace("https://sbfull.com", "https://watchsb.com")
}

object LatanimeExternalExtractor {

    suspend fun resolve(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        preferredName: String? = null
    ): Boolean {
        val fixed = fixHostsLinks(url)
        val lower = fixed.lowercase()
        var found = false

        fun emit(link: ExtractorLink) {
            found = true
            if (preferredName.isNullOrBlank()) {
                callback.invoke(link)
                return
            }
            // Re-emite con nombre LAT[Servidor]
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    callback.invoke(
                        newExtractorLink(
                            source = link.source,
                            name = preferredName,
                            url = link.url,
                            type = link.type
                        ) {
                            this.referer = link.referer
                            this.quality = link.quality
                            this.headers = link.headers
                        }
                    )
                } catch (_: Exception) {
                    callback.invoke(link)
                }
            }
        }

        // 1) Extractores propios primero
        try {
            when {
                isDood(lower) -> {
                    DoodLatanime().getUrl(fixed, referer, subtitleCallback) { emit(it) }
                }
                lower.contains("bysekoze") || lower.contains("byse.") -> {
                    BysekozeLatanime().getUrl(fixed, referer, subtitleCallback) { emit(it) }
                }
                lower.contains("mixdrop") || lower.contains("mxdrop") -> {
                    MixDropLatanime().getUrl(fixed, referer, subtitleCallback) { emit(it) }
                }
                lower.contains("voe.") -> {
                    VoeLatanime().getUrl(fixed, referer, subtitleCallback) { emit(it) }
                }
                lower.contains("listeamed") || lower.contains("vidguard") ||
                        lower.contains("vgfplay") || lower.contains("vembed.") ||
                        lower.contains("bembed.") -> {
                    VidGuardLatanime().getUrl(fixed, referer, subtitleCallback) { emit(it) }
                }
                lower.contains("filemoon.") -> {
                    FilemoonLatanime().getUrl(fixed, referer, subtitleCallback) { emit(it) }
                }
                lower.contains("lulu.") || lower.contains("lulustream") || lower.contains("luluvdo") -> {
                    LuluLatanime().getUrl(fixed, referer, subtitleCallback) { emit(it) }
                }
                lower.contains("savefiles") || lower.contains("streamhls") -> {
                    SaveFilesLatanime().getUrl(fixed, referer, subtitleCallback) { emit(it) }
                }
                lower.contains("hexload") -> {
                    HexloadLatanime().getUrl(fixed, referer, subtitleCallback) { emit(it) }
                }
                lower.contains("mp4upload") -> {
                    val ok = Mp4UploadLatanime().getUrl(fixed, referer, subtitleCallback) { emit(it) }
                    if (!ok) {
                        val normalized = normalizeMp4Upload(fixed)
                        loadExtractor(normalized, referer, subtitleCallback) { emit(it) }
                    }
                }
                lower.contains("upns.online") || lower.contains("vidstack") -> {
                    VidStack().getUrl(fixed, referer, subtitleCallback) { emit(it) }
                }
            }
        } catch (e: Exception) {
            println("LATANIME RESOLVE ERROR -> ${e.message}")
        }

        // 2) Siempre intentar el CORE como refuerzo (aunque el propio haya fallado)
        if (!found) {
            try {
                loadExtractor(fixed, referer, subtitleCallback) { emit(it) }
            } catch (e: Exception) {
                println("LATANIME CORE EXTRACTOR ERROR -> ${e.message}")
            }
        }

        // 3) Genérico último recurso
        if (!found) {
            try {
                found = resolveGeneric(fixed, referer) { emit(it) }
            } catch (_: Exception) {
            }
        }

        return found
    }

    private fun isDood(lower: String): Boolean {
        return lower.contains("dood.") || lower.contains("doodstream") ||
                lower.contains("do7go") || lower.contains("dsvplay") ||
                lower.contains("playmogo") || lower.contains("myvidplay") ||
                lower.contains("vide0.net") || lower.contains("ds2play")
    }

    private suspend fun resolveGeneric(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            var text = app.get(
                url,
                headers = mapOf("Referer" to referer, "User-Agent" to USER_AGENT)
            ).text

            if (text.contains("eval(function(p,a,c,k,e,d")) {
                JsUnpacker(text).unpack()?.let { text = it }
            }

            val links = Regex("""https?://[^\s"'<>\\]+?\.(m3u8|mp4)[^\s"'<>\\]*""")
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
                        type = if (video.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
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
            .find(url)?.groupValues?.getOrNull(1)
        return if (!id.isNullOrBlank()) "https://www.mp4upload.com/embed-$id.html" else url
    }
}

// ===================== MIXDROP =====================
class MixDropLatanime {
    private val name = "MixDrop"
    private val srcRegex = Regex("""wurl\s*=\s*"(.*?)";""")
    private val srcRegex2 = Regex("""wurl.*?=.*?"(.*?)";""")

    suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val embedUrl = url.replaceFirst("/f/", "/e/")
            val html = app.get(
                embedUrl,
                referer = referer,
                headers = mapOf("User-Agent" to USER_AGENT)
            ).text

            var unpacked = html
            try {
                unpacked = getAndUnpack(html)
            } catch (_: Exception) {
                if (html.contains("eval(function(p,a,c,k,e,d")) {
                    JsUnpacker(html).unpack()?.let { unpacked = it }
                }
            }

            val raw = srcRegex.find(unpacked)?.groupValues?.getOrNull(1)
                ?: srcRegex2.find(unpacked)?.groupValues?.getOrNull(1)
                ?: return false

            val finalUrl = httpsify(raw.replace("\\/", "/"))

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = finalUrl,
                    type = if (finalUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = embedUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            true
        } catch (e: Exception) {
            println("MIXDROP ERROR -> ${e.message}")
            false
        }
    }
}

// ===================== VOE =====================
class VoeLatanime {
    private val name = "Voe"
    private val mainUrl = "https://voe.sx"
    private val redirectRegex = Regex("""window\.location\.href\s*=\s*['"]([^'"]+)['"]""")

    suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            var currentUrl = url
            var res = app.get(currentUrl, referer = referer, headers = mapOf("User-Agent" to USER_AGENT))

            redirectRegex.find(res.text)?.groupValues?.getOrNull(1)?.let { redir ->
                res = app.get(redir, referer = referer, headers = mapOf("User-Agent" to USER_AGENT))
                currentUrl = redir
            }

            // Método 1: JSON application/json cifrado
            val encodedString = res.document
                .selectFirst("script[type=application/json]")
                ?.data()
                ?.trim()
                ?.substringAfter("[\"")
                ?.substringBeforeLast("\"]")

            var ok = false

            if (!encodedString.isNullOrBlank()) {
                val json = decryptF7(encodedString)
                val m3u8 = json.optString("source")
                val mp4 = json.optString("direct_access_url")

                if (m3u8.isNotEmpty()) {
                    try {
                        M3u8Helper.generateM3u8(
                            name, m3u8, "$mainUrl/",
                            headers = mapOf("Origin" to "$mainUrl/")
                        ).forEach {
                            callback.invoke(it)
                            ok = true
                        }
                    } catch (_: Exception) {
                        callback.invoke(
                            newExtractorLink(name, name, m3u8, ExtractorLinkType.M3U8) {
                                this.referer = currentUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        ok = true
                    }
                }
                if (mp4.isNotEmpty()) {
                    callback.invoke(
                        newExtractorLink("$name MP4", "$name MP4", mp4, ExtractorLinkType.VIDEO) {
                            this.referer = currentUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    ok = true
                }
            }

            // Método 2: buscar m3u8/mp4 en HTML / scripts
            if (!ok) {
                var text = res.text
                if (text.contains("eval(function(p,a,c,k,e,d")) {
                    JsUnpacker(text).unpack()?.let { text = it }
                }
                Regex("""https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)[^\s"'<>\\]*""")
                    .findAll(text)
                    .map { it.value.replace("\\/", "/") }
                    .distinct()
                    .forEach { video ->
                        callback.invoke(
                            newExtractorLink(name, name, video,
                                if (video.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = currentUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        ok = true
                    }
            }
            ok
        } catch (e: Exception) {
            println("VOE ERROR -> ${e.message}")
            false
        }
    }

    private fun decryptF7(p8: String): JSONObject {
        return try {
            val vF = rot13(p8)
            val vF2 = listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&").fold(vF) { acc, p -> acc.replace(p, "_") }
            val vF3 = vF2.replace("_", "")
            val vF4 = base64Decode(vF3)
            val vF5 = vF4.map { (it.code - 3).toChar() }.joinToString("")
            val vF6 = vF5.reversed()
            JSONObject(base64Decode(vF6))
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun rot13(input: String): String = input.map { c ->
        when (c) {
            in 'A'..'Z' -> ((c - 'A' + 13) % 26 + 'A'.code).toChar()
            in 'a'..'z' -> ((c - 'a' + 13) % 26 + 'a'.code).toChar()
            else -> c
        }
    }.joinToString("")
}

// ===================== VIDGUARD / LISTEAMED =====================
class VidGuardLatanime {
    private val name = "VidGuard"
    private val mainUrl = "https://vidguard.to"

    suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val pageHtml = app.get(
                url,
                headers = mapOf(
                    "Referer" to (referer ?: mainUrl),
                    "User-Agent" to USER_AGENT
                )
            ).text

            val after = pageHtml.substringAfter("eval(function(p,a,c,k,e,d)", "")
            if (after.isBlank()) {
                // fallback genérico
                return extractDirect(pageHtml, url, callback)
            }

            val scriptData = ("eval(function(p,a,c,k,e,d)$after").substringBefore("</script>")
            val unpacked = JsUnpacker(scriptData).unpack() ?: return extractDirect(pageHtml, url, callback)

            val urlEncoded = unpacked
                .substringAfter("window.svg={\"stream\":\"", "")
                .substringBefore("\",\"hash")

            if (urlEncoded.isBlank()) {
                return extractDirect(unpacked, url, callback)
            }

            val finalUrl = sigDecode(urlEncoded)
            callback.invoke(
                newExtractorLink(name, name, finalUrl,
                    if (finalUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            true
        } catch (e: Exception) {
            println("VIDGUARD ERROR -> ${e.message}")
            false
        }
    }

    private suspend fun extractDirect(text: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        val links = Regex("""https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)[^\s"'<>\\]*""")
            .findAll(text)
            .map { it.value.replace("\\/", "/") }
            .distinct()
            .toList()
        links.forEach { video ->
            callback.invoke(
                newExtractorLink(name, name, video,
                    if (video.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                }
            )
        }
        return links.isNotEmpty()
    }

    private fun sigDecode(url: String): String {
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
                    if (i + 1 < size) this[i] = this[i + 1].also { this[i + 1] = this[i] }
                }
            }
            .concatToString()
            .dropLast(5)
        return url.replace(sig, decodedSig)
    }
}

// ===================== FILEMOON =====================
class FilemoonLatanime {
    private val name = "Filemoon"

    suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val headers = mapOf(
                "Referer" to (referer ?: url),
                "User-Agent" to USER_AGENT,
                "Sec-Fetch-Dest" to "iframe",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )

            val page = app.get(url, headers = headers)
            var html = page.text
            val doc = page.document

            // Seguir iframe si existe
            val iframe = doc.selectFirst("iframe")?.attr("src")?.trim().orEmpty()
            if (iframe.isNotBlank()) {
                val iframeUrl = when {
                    iframe.startsWith("//") -> "https:$iframe"
                    iframe.startsWith("http") -> iframe
                    else -> java.net.URI(url).resolve(iframe).toString()
                }
                html = app.get(
                    iframeUrl,
                    headers = headers + mapOf("Referer" to url)
                ).text
            }

            // Desempaquetar TODOS los scripts packed
            val scripts = Regex("""eval\(function\(p,a,c,k,e,d\)[\s\S]*?\)\)""")
                .findAll(html)
                .map { it.value }
                .toList()

            val unpackedParts = mutableListOf<String>()
            unpackedParts.add(html)
            for (script in scripts) {
                try {
                    JsUnpacker(script).unpack()?.let { unpackedParts.add(it) }
                } catch (_: Exception) {
                }
            }
            // también getAndUnpack global
            try {
                getAndUnpack(html).let { unpackedParts.add(it) }
            } catch (_: Exception) {
            }

            val blob = unpackedParts.joinToString("\n")
            val candidates = linkedSetOf<String>()

            listOf(
                Regex("""(?:file|src|source)\s*[:=]\s*["'](https?://[^"']+)["']"""),
                Regex("""sources\s*:\s*\[\s*\{\s*(?:file|src)\s*:\s*["']([^"']+)["']"""),
                Regex("""https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)[^\s"'<>\\]*"""),
                Regex("""["'](https?://[^"']+/hls2?/[^"']+)["']"""),
                Regex("""["'](https?://[^"']+master\.m3u8[^"']*)["']""")
            ).forEach { re ->
                re.findAll(blob).forEach { m ->
                    val v = (m.groupValues.getOrNull(1) ?: m.value).replace("\\/", "/")
                    if (v.startsWith("http") && (v.contains(".m3u8") || v.contains(".mp4") || v.contains("/hls"))) {
                        candidates.add(v)
                    }
                }
            }

            if (candidates.isEmpty()) {
                println("FILEMOON: no streams found for $url")
                return false
            }

            candidates.forEach { video ->
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = video,
                        type = if (video.contains(".m3u8") || video.contains("/hls")) {
                            ExtractorLinkType.M3U8
                        } else {
                            ExtractorLinkType.VIDEO
                        }
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf("Referer" to url, "User-Agent" to USER_AGENT)
                    }
                )
            }
            true
        } catch (e: Exception) {
            println("FILEMOON ERROR -> ${e.message}")
            false
        }
    }
}

// ===================== MP4UPLOAD =====================
class Mp4UploadLatanime {
    private val name = "Mp4upload"

    suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val id = Regex("""(?:embed-|/)([A-Za-z0-9]+)(?:\.html)?""")
                .find(url)?.groupValues?.getOrNull(1)
            val embed = if (!id.isNullOrBlank()) {
                "https://www.mp4upload.com/embed-$id.html"
            } else url

            val html = app.get(
                embed,
                headers = mapOf(
                    "Referer" to (referer ?: "https://www.mp4upload.com/"),
                    "User-Agent" to USER_AGENT
                )
            ).text

            var text = html
            if (html.contains("eval(function(p,a,c,k,e,d")) {
                JsUnpacker(html).unpack()?.let { text = it }
            }

            val candidates = linkedSetOf<String>()
            Regex("player\\.src\\(\"([^\"]+)\"")
                .findAll(text)
                .forEach { candidates.add(it.groupValues[1]) }
            Regex("""src\s*:\s*["'](https?://[^"']+\.mp4[^"']*)["']""")
                .findAll(text)
                .forEach { candidates.add(it.groupValues[1]) }
            Regex("""https?://[^\s"'<>\\]+?\.mp4[^\s"'<>\\]*""")
                .findAll(text)
                .forEach { candidates.add(it.value.replace("\\/", "/")) }

            if (candidates.isEmpty()) {
                println("MP4UPLOAD: no streams for $embed")
                return false
            }

            candidates.forEach { video ->
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = video,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = embed
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf(
                            "Referer" to embed,
                            "User-Agent" to USER_AGENT
                        )
                    }
                )
            }
            true
        } catch (e: Exception) {
            println("MP4UPLOAD ERROR -> ${e.message}")
            false
        }
    }
}

// ===================== LULU =====================
/**
 * LuluStream / Lulu.st
 * Estilo Desisins: https://lulustream.com/e/{id}
 * + POST /dl (como el core de Cloudstream)
 * Filtra logos .svg y basura de JWPlayer para evitar ERROR_CODE_PARSING
 */
class LuluLatanime {
    private val name = "LuluStream"
    private val mainUrl = "https://lulustream.com"

    suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val filecode = url.trimEnd('/')
                .substringAfterLast("/")
                .substringBefore("?")
                .substringBefore("#")

            if (filecode.isBlank()) return false

            // 1) Método core-like: POST /dl
            val postHtml = app.post(
                "$mainUrl/dl",
                data = mapOf(
                    "op" to "embed",
                    "file_code" to filecode,
                    "auto" to "1",
                    "referer" to (referer ?: "")
                ),
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to mainUrl
                )
            ).text

            // 2) También la página embed directa
            val embedUrl = "$mainUrl/e/$filecode"
            val embedHtml = try {
                app.get(
                    embedUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to (referer ?: mainUrl)
                    )
                ).text
            } catch (_: Exception) {
                ""
            }

            val texts = mutableListOf(postHtml, embedHtml)
            for (raw in listOf(postHtml, embedHtml)) {
                if (raw.contains("eval(function(p,a,c,k,e,d")) {
                    try {
                        JsUnpacker(raw).unpack()?.let { texts.add(it) }
                    } catch (_: Exception) {
                    }
                }
            }

            val blob = texts.joinToString("\n")
            val candidates = linkedSetOf<String>()

            Regex("""(?:file|src|source)\s*[:=]\s*["'](https?://[^"']+)["']""")
                .findAll(blob)
                .forEach { candidates.add(it.groupValues[1].replace("\\/", "/")) }

            Regex("""https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)[^\s"'<>\\]*""")
                .findAll(blob)
                .forEach { candidates.add(it.value.replace("\\/", "/")) }

            val links = candidates.filter { isRealVideo(it) }.distinct()
            if (links.isEmpty()) {
                println("LULU: no valid streams for $embedUrl")
                return false
            }

            links.forEach { video ->
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = video,
                        type = if (video.contains(".m3u8") || video.contains("/hls")) {
                            ExtractorLinkType.M3U8
                        } else {
                            ExtractorLinkType.VIDEO
                        }
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf(
                            "Referer" to mainUrl,
                            "Origin" to mainUrl,
                            "User-Agent" to USER_AGENT
                        )
                    }
                )
            }
            true
        } catch (e: Exception) {
            println("LULU ERROR -> ${e.message}")
            false
        }
    }

    private fun isRealVideo(url: String): Boolean {
        val lower = url.lowercase()
        if (!lower.startsWith("http")) return false
        if (lower.endsWith(".svg") || lower.endsWith(".png") || lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") || lower.endsWith(".gif") || lower.endsWith(".webp") ||
            lower.endsWith(".js") || lower.endsWith(".css") || lower.endsWith(".woff") ||
            lower.endsWith(".woff2") || lower.endsWith(".ttf")
        ) return false
        if (lower.contains("player-logo") || lower.contains("/logo") ||
            lower.contains("favicon") || lower.contains("jw8/player")
        ) return false
        return lower.contains(".m3u8") || lower.contains(".mp4") ||
                lower.contains("/hls") || lower.contains("/hls2")
    }
}

// ===================== BYSEKOZE =====================
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
        val headers = mapOf("Referer" to (referer ?: mainUrl), "User-Agent" to USER_AGENT)
        try {
            val id = Regex("""/e/([a-zA-Z0-9]+)""").find(url)?.groupValues?.getOrNull(1) ?: return
            val json = JSONObject(app.get("$mainUrl/api/videos/$id/embed/playback", headers = headers).text)
            val sources = json.optJSONArray("sources") ?: return
            for (i in 0 until sources.length()) {
                val link = sources.getJSONObject(i).optString("url")
                if (link.isBlank()) continue
                callback.invoke(
                    newExtractorLink(name, name, link,
                        if (link.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (_: Exception) {
            try {
                val packed = app.get(url, headers = headers).document
                    .selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data().orEmpty()
                JsUnpacker(packed).unpack()?.let { unpacked ->
                    Regex("""sources:\s*\[\{file:\s*["'](.*?)["']""")
                        .find(unpacked)?.groupValues?.getOrNull(1)?.let { link ->
                            callback.invoke(
                                newExtractorLink(name, name, link,
                                    if (link.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
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
}

// ===================== DOOD =====================
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
                .replace("dsvplay.com", "dood.la")
                .replace("playmogo.com", "dood.la")
                .replace("myvidplay.com", "dood.la")
                .replace("vide0.net", "dood.la")
                .replace("ds2play.com", "dood.la")

            val html = app.get(safeUrl, referer = referer).text
            val md5Path = Regex("""/pass_md5/[^"']+""").find(html)?.value ?: return
            val base = safeUrl.substringBefore("/e/")
            val token = Regex("""token=([a-zA-Z0-9]+)""").find(html)?.groupValues?.getOrNull(1).orEmpty()
            val prefix = app.get(
                "$base$md5Path",
                referer = safeUrl,
                headers = mapOf("X-Requested-With" to "XMLHttpRequest", "User-Agent" to USER_AGENT)
            ).text.trim()

            val finalUrl = prefix + randomString(10) + "?token=$token"
            callback.invoke(
                newExtractorLink(name, name, finalUrl, ExtractorLinkType.VIDEO) {
                    this.referer = safeUrl
                    this.quality = Qualities.Unknown.value
                }
            )
        } catch (e: Exception) {
            println("DOOD ERROR -> ${e.message}")
        }
    }

    private fun randomString(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length).map { chars.random(Random) }.joinToString("")
    }
}

// ===================== SAVEFILES =====================
class SaveFilesLatanime {
    private val name = "SaveFiles"

    suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val cleanUrl = url.replace("/e/", "/").replace("/d/", "/")
            val html = app.get(
                cleanUrl,
                headers = mapOf("Referer" to (referer ?: url), "User-Agent" to USER_AGENT)
            ).text

            if (html.contains(Regex("file was locked|file was deleted", RegexOption.IGNORE_CASE))) return false

            val fileMatch = Regex("""file\s*:\s*["'](https?://[^"']+)["']""")
                .find(html)?.groupValues?.getOrNull(1)
                ?: Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""")
                    .find(html)?.groupValues?.getOrNull(1)
                ?: return false

            val height = Regex("""\[(\d{3,})x(\d{3,})""").find(html)?.groupValues?.getOrNull(2)?.toIntOrNull()
            val quality = when {
                height != null && height >= 1080 -> Qualities.P1080.value
                height != null && height >= 720 -> Qualities.P720.value
                height != null && height >= 480 -> Qualities.P480.value
                else -> Qualities.Unknown.value
            }

            callback.invoke(
                newExtractorLink(name, name, fileMatch,
                    if (fileMatch.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = cleanUrl
                    this.quality = quality
                }
            )
            true
        } catch (e: Exception) {
            println("SAVEFILES ERROR -> ${e.message}")
            false
        }
    }
}

// ===================== HEXLOAD =====================
class HexloadLatanime {
    private val name = "Hexload"

    suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            var text = app.get(
                url,
                headers = mapOf("Referer" to (referer ?: url), "User-Agent" to USER_AGENT)
            ).text
            if (text.contains("eval(function(p,a,c,k,e,d")) {
                JsUnpacker(text).unpack()?.let { text = it }
            }
            val candidates = mutableListOf<String>()
            Regex("""(?:file|src|source)\s*:\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""")
                .findAll(text).forEach { candidates.add(it.groupValues[1].replace("\\/", "/")) }
            Regex("""["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""")
                .findAll(text).forEach { candidates.add(it.groupValues[1].replace("\\/", "/")) }
            val links = candidates.distinct()
            links.forEach { video ->
                callback.invoke(
                    newExtractorLink(name, name, video,
                        if (video.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
            links.isNotEmpty()
        } catch (e: Exception) {
            println("HEXLOAD ERROR -> ${e.message}")
            false
        }
    }
}