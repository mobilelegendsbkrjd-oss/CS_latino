package com.sololatino

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.JsUnpacker

// =========================
// DOMINIOS FILEMOON
// =========================
class FileMoon2 : FilemoonV2() {
    override var name = "Filemoon"
    override var mainUrl = "https://filemoon.to"
}

class FileMoonIn : FilemoonV2() {
    override var name = "Filemoon"
    override var mainUrl = "https://filemoon.in"
}

class FileMoonSx : FilemoonV2() {
    override var name = "Filemoon"
    override var mainUrl = "https://filemoon.sx"
}

class Bysedikamoum : FilemoonV2() {
    override var name = "Bysedikamoum"
    override var mainUrl = "https://bysedikamoum.com"
}

// =========================
// CLASE PRINCIPAL FILEMOONV2 (CON IDIOMA Y FILTRO LAT)
// =========================
open class FilemoonV2 : ExtractorApi() {
    override var name = "Filemoon"
    override var mainUrl = "https://filemoon.to"
    override val requiresReferer = true

    // ============================================
    // VARIABLE PARA ALMACENAR EL IDIOMA
    // ============================================
    private var language: String = "LAT"

    // ============================================
    // FUNCIÓN PARA ESTABLECER EL IDIOMA
    // ============================================
    fun withLanguage(lang: String): FilemoonV2 {
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
            // ============================================
            // FILTRO: SOLO PROCESAR SI ES LAT
            // ============================================
            if (language != "LAT") {
                println("[FilemoonV2] ❌ Idioma $language no permitido (solo LAT). Saltando...")
                return
            }

            println("[FilemoonV2] === INICIANDO ===")
            println("[FilemoonV2] URL: $url")
            println("[FilemoonV2] Idioma: $language")

            val defaultHeaders = mapOf(
                "Referer" to url,
                "Sec-Fetch-Dest" to "iframe",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "cross-site",
                "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64; rv:137.0) Gecko/20100101 Firefox/137.0"
            )

            val initialResponse = app.get(url, defaultHeaders)
            val iframeSrcUrl = initialResponse.document.selectFirst("iframe")?.attr("src")

            if (iframeSrcUrl.isNullOrEmpty()) {
                println("[FilemoonV2] No se encontró iframe, buscando en script...")
                val fallbackScriptData = initialResponse.document
                    .selectFirst("script:containsData(function(p,a,c,k,e,d))")
                    ?.data().orEmpty()

                val unpackedScript = JsUnpacker(fallbackScriptData).unpack()
                println("[FilemoonV2] Script desempaquetado: ${unpackedScript?.take(200)}")

                val videoUrl = unpackedScript?.let {
                    Regex("""sources:\[\{file:"(.*?)"""").find(it)?.groupValues?.get(1)
                }

                if (!videoUrl.isNullOrEmpty()) {
                    println("[FilemoonV2] Video encontrado en script: $videoUrl")
                    val nameWithLang = "$language[$name]"
                    M3u8Helper.generateM3u8(
                        nameWithLang,
                        videoUrl,
                        mainUrl,
                        headers = defaultHeaders
                    ).forEach(callback)
                }
                return
            }

            println("[FilemoonV2] Iframe encontrado: $iframeSrcUrl")

            val iframeHeaders = defaultHeaders + ("Accept-Language" to "en-US,en;q=0.5")
            val iframeResponse = app.get(iframeSrcUrl, headers = iframeHeaders)

            val iframeScriptData = iframeResponse.document
                .selectFirst("script:containsData(function(p,a,c,k,e,d))")
                ?.data().orEmpty()

            val unpackedScript = JsUnpacker(iframeScriptData).unpack()
            println("[FilemoonV2] Script iframe desempaquetado: ${unpackedScript?.take(200)}")

            var videoUrl = unpackedScript?.let {
                Regex("""sources:\[\{file:"(.*?)"""").find(it)?.groupValues?.get(1)
            }

            if (!videoUrl.isNullOrEmpty()) {
                println("[FilemoonV2] Video encontrado en iframe: $videoUrl")
                val nameWithLang = "$language[$name]"
                M3u8Helper.generateM3u8(
                    nameWithLang,
                    videoUrl,
                    mainUrl,
                    headers = defaultHeaders
                ).forEach(callback)
                return
            }

            // =========================
            // FALLBACK: WEBVIEW RESOLVER
            // =========================
            println("[FilemoonV2] Usando WebViewResolver...")
            val resolver = WebViewResolver(
                interceptUrl = Regex("""(m3u8|master\.txt)"""),
                additionalUrls = listOf(Regex("""(m3u8|master\.txt)""")),
                useOkhttp = false,
                timeout = 15_000L
            )

            val interceptedUrl = app.get(
                iframeSrcUrl,
                referer = referer,
                interceptor = resolver
            ).url

            if (interceptedUrl.isNotEmpty()) {
                println("[FilemoonV2] WebView interceptó: $interceptedUrl")
                val nameWithLang = "$language[$name]"
                M3u8Helper.generateM3u8(
                    nameWithLang,
                    interceptedUrl,
                    mainUrl,
                    headers = defaultHeaders
                ).forEach(callback)
            } else {
                println("[FilemoonV2] No se encontraron enlaces")
            }

        } catch (e: Exception) {
            println("[FilemoonV2] Error: ${e.message}")
        }
    }
}