package com.verpeliculasonline.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class OpuxaExtractor : ExtractorApi() {

    override val name = "Opuxa"
    override val mainUrl = "https://opuxa.lat"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?
    ): List<ExtractorLink>? {

        val links = mutableListOf<ExtractorLink>()
        val headers = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.5",
            "Accept-Encoding" to "gzip, deflate, br",
            "DNT" to "1",
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1",
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "cross-site",
            "Pragma" to "no-cache",
            "Cache-Control" to "no-cache"
        )

        try {
            // Obtener la página con headers
            val doc = app.get(url, referer = referer, headers = headers).document
            
            // Buscar el iframe
            val iframe = doc.selectFirst("iframe[src]") ?: return null
            
            var iframeUrl = iframe.attr("src")
            if (iframeUrl.startsWith("/")) {
                iframeUrl = "$mainUrl$iframeUrl"
            }
            
            // Agregar parámetros importantes si no están
            if (!iframeUrl.contains("http_referer")) {
                iframeUrl += if (iframeUrl.contains("?")) {
                    "&http_referer=${referer?.encodeURL()}"
                } else {
                    "?http_referer=${referer?.encodeURL()}"
                }
            }
            
            if (!iframeUrl.contains("autoplay")) {
                iframeUrl += "&autoplay=yes"
            }
            
            println("DEBUG: Iframe URL: $iframeUrl")
            
            // Intentar cargar el extractor con esta URL
            loadExtractor(
                iframeUrl,
                referer ?: url,
                { /* sin subs */ },
                headers = headers
            ) { link ->
                println("DEBUG: Found link: ${link.url}")
                links.add(link)
            }
            
            // Si no funcionó, intentar métodos alternativos
            if (links.isEmpty()) {
                // Buscar en scripts para URLs de video
                doc.select("script").forEach { script ->
                    val scriptContent = script.html()
                    
                    // Buscar URLs comunes
                    val patterns = listOf(
                        """src\s*:\s*["']([^"']+)["']""".toRegex(),
                        """file\s*:\s*["']([^"']+)["']""".toRegex(),
                        """video_url\s*:\s*["']([^"']+)["']""".toRegex(),
                        """["'](https?://[^"'\s]+\.(?:m3u8|mp4|mkv|avi))["']""".toRegex(),
                        """embed/[^"']+["']""".toRegex()
                    )
                    
                    patterns.forEach { pattern ->
                        pattern.findAll(scriptContent).forEach { match ->
                            var foundUrl = match.groups[1]?.value ?: match.value
                            foundUrl = foundUrl.trim('\'', '"', ' ')
                            
                            if (foundUrl.isNotBlank() && foundUrl.startsWith("http")) {
                                println("DEBUG: Found video URL in script: $foundUrl")
                                
                                loadExtractor(
                                    fixHostsLinks(foundUrl),
                                    referer ?: url,
                                    { /* sin subs */ },
                                    headers = headers
                                ) { link ->
                                    links.add(link)
                                }
                            }
                        }
                    }
                }
            }
            
        } catch (e: Exception) {
            println("DEBUG: Error in Opuxa extractor: ${e.message}")
            e.printStackTrace()
        }
        
        return if (links.isEmpty()) null else links
    }

    private fun String.encodeURL(): String = 
        java.net.URLEncoder.encode(this, "UTF-8")

    private fun fixHostsLinks(url: String): String {
        return url
            .replace("https://hglink.to", "https://streamwish.to")
            .replace("https://swdyu.com", "https://streamwish.to")
            .replace("https://cybervynx.com", "https://streamwish.to")
            .replace("https://dumbalag.com", "https://streamwish.to")
            .replace("https://mivalyo.com", "https://vidhidepro.com")
            .replace("https://dinisglows.com", "https://vidhidepro.com")
            .replace("https://dhtpre.com", "https://vidhidepro.com")
            .replace("https://filemoon.link", "https://filemoon.sx")
            .replace("https://sblona.com", "https://watchsb.com")
            .replace("https://lulu.st", "https://lulustream.com")
            .replace("https://uqload.io", "https://uqload.com")
            .replace("https://do7go.com", "https://dood.la")
            .replace("https://dooood.com", "https://dood.la")
            .replace("https://dood.so", "https://dood.la")
            .replace("https://dood.ws", "https://dood.la")
            .replace("https://dood.to", "https://dood.la")
    }
}