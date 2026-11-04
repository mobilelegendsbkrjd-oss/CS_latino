package com.monoschinos

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.FormBody
import org.json.JSONObject

class NetuExtractor : ExtractorApi() {
    override val name = "Netu"
    override val mainUrl = "https://netu.tv" // Dominio principal, aunque el extractor se activará por alias
    override val requiresReferer = true

    // Alias de dominios donde suele alojarse Netu

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // 1. Normalizar la URL: /f/ o /v/ -> /e/
        val embedUrl = url
            .replace("/watch/", "/e/")
            .replace("/v/", "/e/")
            .replace("/f/", "/e/")
            .replace("/e/e/", "/e/") // Evitar doble conversión

        val baseUrl = embedUrl.substringBefore("/e/") // Extrae el dominio base

        // 2. Obtener el HTML de la página del embed
        val pageHtml = try {
            app.get(embedUrl, referer = referer ?: baseUrl).text
        } catch (e: Exception) {
            println("NetuExtractor: Error al obtener la página -> ${e.message}")
            return
        }

        // 3. Buscar el hash (usando las mismas regex que StreamFlix)
        val hash = Regex("""(?:var|let|const)\s+hash\s*=\s*['"]([^'"]+)['"]""").find(pageHtml)?.groupValues?.get(1)
            ?: Regex("""data-hash\s*=\s*['"]([^'"]+)['"]""").find(pageHtml)?.groupValues?.get(1)
            ?: Regex("""hash\s*:\s*['"]([^'"]+)['"]""").find(pageHtml)?.groupValues?.get(1)

        if (hash.isNullOrBlank()) {
            println("NetuExtractor: No se encontró el hash en la página")
            return
        }

        // 4. Buscar 'iss' (a veces es necesario para la petición POST)
        val iss = Regex("""(?:var|let|const)\s+iss\s*=\s*['"]([^'"]+)['"]""").find(pageHtml)?.groupValues?.get(1)

        // 5. Construir y enviar la petición POST a la API de Netu
        val formBody = FormBody.Builder()
            .add("hash", hash)
            .add("r", embedUrl)
            .apply { if (iss != null) add("iss", iss) }
            .build()

        val postResponse = try {
            app.post(
                "$baseUrl/player/index.php?data=$hash&do=getVideo",
                requestBody = formBody,
                headers = mapOf(
                    "Referer" to embedUrl,
                    "X-Requested-With" to "XMLHttpRequest"
                )
            ).text
        } catch (e: Exception) {
            println("NetuExtractor: Error en la petición POST -> ${e.message}")
            return
        }

        // 6. Parsear la respuesta para obtener el enlace del video
        val videoUrl = try {
            val json = JSONObject(postResponse)
            json.optString("securedLink").ifEmpty { json.optString("videoSource") }
                .ifEmpty { json.optString("file") }.ifEmpty { json.optString("url") }
                .takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            // Fallback: buscar un enlace .m3u8 o .mp4 directamente en la respuesta
            Regex("""(https?://[^"'\s]+\.(?:m3u8|mp4)[^"'\s]*)""").find(postResponse)?.groupValues?.get(1)
        }

        if (videoUrl.isNullOrBlank()) {
            println("NetuExtractor: No se pudo extraer la URL del video de la respuesta")
            return
        }

        // 7. Devolver el enlace al callback de CloudStream
        callback(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = videoUrl,
                type = ExtractorLinkType.M3U8 // Netu suele devolver m3u8
            ) {
                this.referer = embedUrl
                this.quality = Qualities.Unknown.value
            }
        )
    }
}