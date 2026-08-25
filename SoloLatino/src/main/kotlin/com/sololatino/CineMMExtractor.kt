package com.sololatino

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.fixUrl
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink

// =========================
// MIRRORS DE CINEMM
// =========================
class HgplayCDN : CineMMExtractor() {
    override val name = "CineMM"
    override val mainUrl = "https://hgplaycdn.com"
}

class Habetar : CineMMExtractor() {
    override val name = "CineMM"
    override val mainUrl = "https://habetar.com"
}

class Yuguaab : CineMMExtractor() {
    override val name = "CineMM"
    override val mainUrl = "https://yuguaab.com"
}

class Guxhag : CineMMExtractor() {
    override val name = "CineMM"
    override val mainUrl = "https://guxhag.com"
}

class Auvexiug : CineMMExtractor() {
    override val name = "CineMM"
    override val mainUrl = "https://auvexiug.com"
}

class Xenolyzb : CineMMExtractor() {
    override val name = "CineMM"
    override val mainUrl = "https://xenolyzb.com"
}

class Haxloppd : CineMMExtractor() {
    override val name = "CineMM"
    override val mainUrl = "https://haxloppd.com"
}

class Cavanhabg : CineMMExtractor() {
    override val name = "CineMM"
    override val mainUrl = "https://cavanhabg.com"
}

class DumbalagCineMM : CineMMExtractor() {
    override val name = "CineMM"
    override val mainUrl = "https://dumbalag.com"
}

class Uasopt : CineMMExtractor() {
    override val name = "CineMM"
    override val mainUrl = "https://uasopt.com"
}

// =========================
// REDIRECTORS
// =========================
class Dhcplay : CineMMRedirect() {
    override val name = "Dhcplay"
    override val mainUrl = "https://dhcplay.com"
}

class HglinkToCineMM : CineMMRedirect() {
    override val name = "HglinkTo"
    override val mainUrl = "https://hglink.to"
}

// =========================
// CLASE PRINCIPAL CINEMM
// =========================
open class CineMMExtractor : ExtractorApi() {
    override val name = "CineMM"
    override val mainUrl = "https://hgplaycdn.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            println("[CineMM] Procesando: $url")

            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                "Referer" to mainUrl
            )

            val html = app.get(url, headers = headers).text

            // Buscar el script con var links
            val scriptRegex = Regex("""<script[^>]*>.*?var\s+links\s*=\s*(\{[^}]+\}).*?</script>""", RegexOption.DOT_MATCHES_ALL)
            val scriptMatch = scriptRegex.find(html)

            if (scriptMatch != null) {
                val linksJson = scriptMatch.groupValues[1]
                println("[CineMM] Links encontrados: $linksJson")

                // Buscar URLs de m3u8
                val m3u8Regex = Regex(""""hls\d?"\s*:\s*"(https?://[^\s"]+\.m3u8[^\s"]*)""")
                m3u8Regex.findAll(linksJson).forEach { match ->
                    val m3u8Url = match.groupValues[1]
                    if (m3u8Url.isNotBlank()) {
                        println("[CineMM] M3U8 encontrado: $m3u8Url")
                        M3u8Helper.generateM3u8(
                            name,
                            fixUrl(m3u8Url),
                            referer = "$mainUrl/",
                            headers = headers
                        ).forEach(callback)
                    }
                }

                // Buscar URLs de mp4
                val mp4Regex = Regex(""""file"\s*:\s*"(https?://[^\s"]+\.mp4[^\s"]*)""")
                mp4Regex.findAll(linksJson).forEach { match ->
                    val mp4Url = match.groupValues[1]
                    if (mp4Url.isNotBlank()) {
                        println("[CineMM] MP4 encontrado: $mp4Url")
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = name,
                                url = mp4Url,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "$mainUrl/"
                                this.headers = headers
                            }
                        )
                    }
                }
                return
            }

            // Fallback: buscar enlaces directos
            println("[CineMM] No se encontró script, buscando enlaces directos")
            Regex("""https?://[^\s"'<>]+\.(m3u8|mp4)[^\s"'<>]*""")
                .findAll(html)
                .forEach { match ->
                    val link = match.value
                    val type = if (link.contains(".m3u8")) {
                        ExtractorLinkType.M3U8
                    } else {
                        ExtractorLinkType.VIDEO
                    }
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = link,
                            type = type
                        ) {
                            this.referer = "$mainUrl/"
                            this.headers = headers
                        }
                    )
                }

        } catch (e: Exception) {
            println("[CineMM] Error: ${e.message}")
            loadExtractor(url, referer, subtitleCallback, callback)
        }
    }
}

// =========================
// REDIRECTORS
// =========================
abstract class CineMMRedirect : ExtractorApi() {
    override val name = "CineMMRedirect"
    override val requiresReferer = false

    // Lista de mirrors de CineMM
    private val mirrors = arrayOf(
        "hgplaycdn.com",
        "habetar.com",
        "yuguaab.com",
        "guxhag.com",
        "auvexiug.com",
        "xenolyzb.com",
        "haxloppd.com",
        "cavanhabg.com",
        "dumbalag.com",
        "uasopt.com"
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Extraer el ID del video
            val videoId = url.substringAfter("/e/")
            if (videoId.isBlank()) {
                println("[CineMMRedirect] No se pudo extraer el ID del video")
                loadExtractor(url, referer, subtitleCallback, callback)
                return
            }

            // Elegir un mirror aleatorio
            val mirror = mirrors.random()
            val mirrorUrl = "https://$mirror/e/$videoId"

            println("[CineMMRedirect] Redirigiendo a: $mirrorUrl")

            // Usar el extractor de CineMM con el mirror elegido
            when (mirror) {
                "hgplaycdn.com" -> HgplayCDN().getUrl(mirrorUrl, referer, subtitleCallback, callback)
                "habetar.com" -> Habetar().getUrl(mirrorUrl, referer, subtitleCallback, callback)
                "yuguaab.com" -> Yuguaab().getUrl(mirrorUrl, referer, subtitleCallback, callback)
                "guxhag.com" -> Guxhag().getUrl(mirrorUrl, referer, subtitleCallback, callback)
                "auvexiug.com" -> Auvexiug().getUrl(mirrorUrl, referer, subtitleCallback, callback)
                "xenolyzb.com" -> Xenolyzb().getUrl(mirrorUrl, referer, subtitleCallback, callback)
                "haxloppd.com" -> Haxloppd().getUrl(mirrorUrl, referer, subtitleCallback, callback)
                "cavanhabg.com" -> Cavanhabg().getUrl(mirrorUrl, referer, subtitleCallback, callback)
                "dumbalag.com" -> DumbalagCineMM().getUrl(mirrorUrl, referer, subtitleCallback, callback)
                "uasopt.com" -> Uasopt().getUrl(mirrorUrl, referer, subtitleCallback, callback)
                else -> loadExtractor(mirrorUrl, referer, subtitleCallback, callback)
            }

        } catch (e: Exception) {
            println("[CineMMRedirect] Error: ${e.message}")
            loadExtractor(url, referer, subtitleCallback, callback)
        }
    }
}