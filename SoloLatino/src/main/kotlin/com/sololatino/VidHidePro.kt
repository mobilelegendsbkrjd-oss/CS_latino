package com.sololatino

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.Qualities

// =========================
// DOMINIOS VIDHIDE
// =========================
class DhtpreCom : VidHidePro() {
    override val name = "DhtpreCom"
    override val mainUrl = "https://dhtpre.com"
}

class DingtezuniCom : VidHidePro() {
    override val name = "DingtezuniCom"
    override val mainUrl = "https://dingtezuni.com"
}

class MinochinosExtractorV2 : VidHidePro() {
    override val name = "Minochinos"
    override val mainUrl = "https://minochinos.com"
}

class Ryderjet : VidHidePro() {
    override var name = "Ryderjet"
    override var mainUrl = "https://ryderjet.com"
}

class VidHideHub : VidHidePro() {
    override var name = "VidHideHub"
    override var mainUrl = "https://vidhidehub.com"
}

class VidHidePro1 : VidHidePro() {
    override var name = "FilelionsLive"
    override var mainUrl = "https://filelions.live"
}

class VidHidePro2 : VidHidePro() {
    override var name = "FilelionsOnline"
    override var mainUrl = "https://filelions.online"
}

class VidHidePro3 : VidHidePro() {
    override var name = "FilelionsTo"
    override var mainUrl = "https://filelions.to"
}

class VidHidePro4 : VidHidePro() {
    override val name = "KinogerBe"
    override val mainUrl = "https://kinoger.be"
}

class VidHidePro5 : VidHidePro() {
    override val name = "VidHideVip"
    override val mainUrl = "https://vidhidevip.com"
}

class VidHidePro6 : VidHidePro() {
    override val name = "VidHidePre"
    override val mainUrl = "https://vidhidepre.com"
}

class Smoothpre : VidHidePro() {
    override var name = "Smoothpre"
    override var mainUrl = "https://smoothpre.com"
}

class Peytonepre : VidHidePro() {
    override var name = "Peytonepre"
    override var mainUrl = "https://peytonepre.com"
}

// =========================
// CLASE PRINCIPAL VIDHIDEPRO (MEJORADA CON CALIDAD)
// =========================
open class VidHidePro : ExtractorApi() {
    override val name = "VidHidePro"
    override val mainUrl = "https://vidhidepro.com"
    override val requiresReferer = true

    private var language: String = "LAT"

    fun withLanguage(lang: String): VidHidePro {
        this.language = lang
        return this
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            if (language != "LAT") {
                println("[VidHidePro] ❌ Idioma $language no permitido (solo LAT). Saltando...")
                return
            }

            val headers = mapOf(
                "Sec-Fetch-Dest" to "empty",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "cross-site",
                "Origin" to mainUrl,
                "User-Agent" to USER_AGENT,
            )

            val embedUrl = getEmbedUrl(url)
            println("[VidHidePro] Procesando: $embedUrl (Idioma: $language)")

            val response = app.get(embedUrl, referer = referer ?: mainUrl)
            val html = response.text

            var script = ""
            if (!getPacked(html).isNullOrEmpty()) {
                var result = getAndUnpack(html)
                if (result.contains("var links")) {
                    result = result.substringAfter("var links")
                }
                script = result
                println("[VidHidePro] Script desempaquetado: ${script.take(200)}")
            } else {
                val scriptElement = response.document.selectFirst("script:containsData(sources:)")
                if (scriptElement != null) {
                    script = scriptElement.data()
                    println("[VidHidePro] Script encontrado en elemento")
                }
            }

            if (script.isBlank()) {
                println("[VidHidePro] No se encontró script, buscando enlaces directos")
                extractDirectLinks(html, embedUrl, callback)
                return
            }

            // ============================================
            // EXTRAER M3U8 CON CALIDAD
            // ============================================
            val m3u8Patterns = listOf(
                Regex(""""file"\s*:\s*"(https?://[^\s"]+\.m3u8[^\s"]*)"""),
                Regex(""""hls2"\s*:\s*"(https?://[^\s"]+\.m3u8[^\s"]*)"""),
                Regex(""""hls3"\s*:\s*"(https?://[^\s"]+\.m3u8[^\s"]*)"""),
                Regex(""""hls4"\s*:\s*"(https?://[^\s"]+\.m3u8[^\s"]*)"""),
                Regex(""""hls"\s*:\s*"(https?://[^\s"]+\.m3u8[^\s"]*)"""),
                Regex(""":\s*"(https?://[^\s"]+\.m3u8[^\s"]*)""")
            )

            var foundLinks = false

            for (pattern in m3u8Patterns) {
                pattern.findAll(script).forEach { match ->
                    val m3u8Url = match.groupValues[1]
                    if (m3u8Url.isNotBlank()) {
                        println("[VidHidePro] M3U8 encontrado: $m3u8Url")
                        foundLinks = true

                        // 🔥 EXTRAER CALIDAD DE LA URL
                        val quality = extractQualityFromUrl(m3u8Url)

                        // Generar M3U8 con calidad
                        M3u8Helper.generateM3u8(
                            "$language[$name]",
                            fixUrl(m3u8Url),
                            referer = "$mainUrl/",
                            headers = headers
                        ).forEach { link ->
                            // Si tiene calidad, asignarla
                            if (quality != Qualities.Unknown.value) {
                                val newLink = ExtractorLink(
                                    source = link.source,
                                    name = if (quality != Qualities.Unknown.value) {
                                        val qualityName = when (quality) {
                                            Qualities.P1080.value -> "1080p"
                                            Qualities.P720.value -> "720p"
                                            Qualities.P480.value -> "480p"
                                            Qualities.P360.value -> "360p"
                                            Qualities.P2160.value -> "4K"
                                            else -> ""
                                        }
                                        if (qualityName.isNotBlank()) {
                                            "$language[$name-$qualityName]"
                                        } else {
                                            link.name
                                        }
                                    } else {
                                        link.name
                                    },
                                    url = link.url,
                                    type = link.type,
                                    quality = quality,
                                    referer = link.referer,
                                    headers = link.headers,
                                    extractorData = link.extractorData
                                )
                                callback.invoke(newLink)
                            } else {
                                callback.invoke(link)
                            }
                        }
                    }
                }
            }

            // ============================================
            // MP4 CON CALIDAD
            // ============================================
            if (!foundLinks) {
                val mp4Patterns = listOf(
                    Regex(""""file"\s*:\s*"(https?://[^\s"]+\.mp4[^\s"]*)"""),
                    Regex("""src\s*=\s*"(https?://[^\s"]+\.mp4[^\s"]*)""")
                )
                for (pattern in mp4Patterns) {
                    pattern.findAll(script).forEach { match ->
                        val mp4Url = match.groupValues[1]
                        if (mp4Url.isNotBlank()) {
                            println("[VidHidePro] MP4 encontrado: $mp4Url")
                            foundLinks = true

                            val quality = extractQualityFromUrl(mp4Url)
                            val qualityName = when (quality) {
                                Qualities.P1080.value -> "1080p"
                                Qualities.P720.value -> "720p"
                                Qualities.P480.value -> "480p"
                                Qualities.P360.value -> "360p"
                                Qualities.P2160.value -> "4K"
                                else -> ""
                            }

                            val finalName = if (qualityName.isNotBlank()) {
                                "$language[$name-$qualityName]"
                            } else {
                                "$language[$name]"
                            }

                            callback.invoke(
                                newExtractorLink(
                                    source = "SoloLatino",
                                    name = finalName,
                                    url = mp4Url,
                                ) {
                                    this.referer = "$mainUrl/"
                                    this.headers = headers
                                    this.type = ExtractorLinkType.VIDEO
                                    this.quality = quality
                                }
                            )
                        }
                    }
                }
            }

            if (!foundLinks) {
                println("[VidHidePro] No se encontraron enlaces, usando loadExtractor")
                com.lagradost.cloudstream3.utils.loadExtractor(url, referer, subtitleCallback, callback)
            }

        } catch (e: Exception) {
            println("[VidHidePro] Error: ${e.message}")
            com.lagradost.cloudstream3.utils.loadExtractor(url, referer, subtitleCallback, callback)
        }
    }

    // =========================
    // FUNCIÓN PARA EXTRAER CALIDAD DE LA URL
    // =========================
    private fun extractQualityFromUrl(url: String): Int {
        return when {
            url.contains("4K") || url.contains("2160") -> Qualities.P2160.value
            url.contains("1080") || url.contains("1080p") || url.contains("1080") -> Qualities.P1080.value
            url.contains("720") || url.contains("720p") -> Qualities.P720.value
            url.contains("480") || url.contains("480p") -> Qualities.P480.value
            url.contains("360") || url.contains("360p") -> Qualities.P360.value
            else -> {
                // Buscar patrones en la URL
                val qualityMatch = Regex("""[_-](\d{3,4})p""").find(url)
                if (qualityMatch != null) {
                    val q = qualityMatch.groupValues[1].toIntOrNull()
                    when (q) {
                        2160 -> Qualities.P2160.value
                        1080 -> Qualities.P1080.value
                        720 -> Qualities.P720.value
                        480 -> Qualities.P480.value
                        360 -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    }
                } else {
                    Qualities.Unknown.value
                }
            }
        }
    }

    private suspend fun extractDirectLinks(
        html: String,
        url: String,
        callback: (ExtractorLink) -> Unit
    ) {
        println("[VidHidePro] Extrayendo enlaces directos...")

        Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
            .findAll(html)
            .forEach { match ->
                val link = match.value
                println("[VidHidePro] Enlace directo M3U8: $link")

                val quality = extractQualityFromUrl(link)
                val qualityName = when (quality) {
                    Qualities.P1080.value -> "1080p"
                    Qualities.P720.value -> "720p"
                    Qualities.P480.value -> "480p"
                    Qualities.P360.value -> "360p"
                    Qualities.P2160.value -> "4K"
                    else -> ""
                }

                val finalName = if (qualityName.isNotBlank()) {
                    "$language[$name-$qualityName]"
                } else {
                    "$language[$name]"
                }

                callback.invoke(
                    newExtractorLink(
                        source = "SoloLatino",
                        name = finalName,
                        url = link,
                    ) {
                        this.referer = url
                        this.type = ExtractorLinkType.M3U8
                        this.quality = quality
                    }
                )
            }

        Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*""")
            .findAll(html)
            .forEach { match ->
                val link = match.value
                println("[VidHidePro] Enlace directo MP4: $link")

                val quality = extractQualityFromUrl(link)
                val qualityName = when (quality) {
                    Qualities.P1080.value -> "1080p"
                    Qualities.P720.value -> "720p"
                    Qualities.P480.value -> "480p"
                    Qualities.P360.value -> "360p"
                    Qualities.P2160.value -> "4K"
                    else -> ""
                }

                val finalName = if (qualityName.isNotBlank()) {
                    "$language[$name-$qualityName]"
                } else {
                    "$language[$name]"
                }

                callback.invoke(
                    newExtractorLink(
                        source = "SoloLatino",
                        name = finalName,
                        url = link,
                    ) {
                        this.referer = url
                        this.type = ExtractorLinkType.VIDEO
                        this.quality = quality
                    }
                )
            }
    }

    private fun getEmbedUrl(url: String): String {
        return when {
            url.contains("/d/") -> url.replace("/d/", "/v/")
            url.contains("/download/") -> url.replace("/download/", "/v/")
            url.contains("/file/") -> url.replace("/file/", "/v/")
            else -> url.replace("/f/", "/v/")
        }
    }
}