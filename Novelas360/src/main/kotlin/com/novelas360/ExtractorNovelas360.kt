package com.novelas360

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class ExtractorNovelas360 : ExtractorApi() {

    override val name = "Novelas360 Player"
    override val mainUrl = "https://novelas360.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val fixedReferer = referer ?: mainUrl

        println("Extrayendo de: $url (referer: $fixedReferer)")

        val doc = app.get(url, referer = fixedReferer).document

        val iframeSrc = doc.selectFirst("iframe[src*='novelas360.cyou/e/']")?.attr("abs:src")
            ?: doc.selectFirst("div.player iframe")?.attr("abs:src")
            ?: doc.selectFirst(".embed-responsive iframe")?.attr("abs:src")
            ?: run {
                println("No encontré iframe con /e/")
                return null
            }

        println("Iframe encontrado: $iframeSrc")

        val videoKey = iframeSrc.substringAfterLast("/e/").takeIf { it.isNotBlank() } ?: return null

        // Intentamos setear cookies visitando primero trace y luego iframe (para uid)
        app.get("https://novelas360.cyou/cdn-cgi/trace", referer = url)
        app.get(iframeSrc, referer = url, headers = mapOf(
            "Referer" to url,
            "Origin" to mainUrl,
            "X-Requested-With" to "XMLHttpRequest"
        ))

        // POST headers lo más cercanos posibles
        val postHeaders = mapOf(
            "Origin" to "https://novelas360.cyou",
            "Referer" to iframeSrc,
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type" to "application/json",
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        )

        // Body con params de tus curls + vacíos para probar
        val postBody = mapOf(
            "v" to videoKey,
            "secure" to "0",
            "ver" to "4",
            "adb" to "0",
            "wasmcheck" to "0",
            "embed_from" to "0",
            "token" to "",
            "htoken" to "",
            "gt" to "",
            "adscore" to "",
            "click_hash" to ""  // si falla, esto puede ser clave, pero sin JS es difícil
        )

        println("Enviando POST a https://novelas360.cyou/player/get_md5.php con v=$videoKey")

        val res = app.post(
            "https://novelas360.cyou/player/get_md5.php",
            data = postBody,
            headers = postHeaders,
            allowRedirects = true
        )

        println("Respuesta POST status: ${res.code} | body: ${res.text.take(200)}")

        val json = res.parsedSafe<Map<String, String>>() ?: run {
            println("No parseó JSON")
            return null
        }

        val file = json["file"]?.takeIf { it.isNotBlank() && it.startsWith("http") }
            ?: run {
                println("No hay 'file' válido en JSON: $json")
                // Fallback: busca cualquier m3u8 en el HTML del iframe (raro pero por si acaso)
                val iframeDoc = app.get(iframeSrc, referer = url).document
                val fallbackM3u8 = iframeDoc.select("source[src*='.m3u8'], video[src*='.m3u8']").firstOrNull()?.attr("abs:src")
                if (fallbackM3u8 != null) {
                    println("Fallback m3u8 encontrado: $fallbackM3u8")
                    fallbackM3u8
                } else null
            } ?: return null

        println("¡File encontrado! $file")

        return listOf(
            newExtractorLink(
                this.name,
                "Novelas360 Stream",
                file,
                referer = iframeSrc,
                quality = Qualities.Unknown.value,
                isM3u8 = file.contains(".m3u8") || file.contains("master")
            ).apply {
                this.headers["Referer"] = iframeSrc
                this.headers["Origin"] = "https://novelas360.cyou"
            }
        )
    }
}
