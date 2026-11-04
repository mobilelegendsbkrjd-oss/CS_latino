package com.mundodonghua

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class MdRumble : ExtractorApi() {
    override var name = "Rumble"
    override var mainUrl = "https://rumble.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // 1. Buscar el video ID de la URL (v79dq2e)
        val embedId = url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
        if (embedId.isEmpty()) return

        // 2. Hacer la petición a la API oficial de Rumble con el User-Agent de CloudStream
        val apiUrl = "$mainUrl/embedJS/u3/?request=video&ver=2&v=$embedId"
        val response = try {
            app.get(apiUrl, headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "application/json, text/plain, */*"
            )).text
        } catch (e: Exception) {
            println("MD_DEBUG[RUMBLE_ERR] ${e.message}")
            return
        }

        // 3. El JSON contiene las fuentes en "ua" (hls, mp4, tar) y en "u" (hls general)
        try {
            val json = org.json.JSONObject(response)

            // Extraer la calidad HLS general (suele ser la mejor)
            val generalHls = json.optJSONObject("u")?.optJSONObject("hls")?.optString("url")
            if (!generalHls.isNullOrBlank()) {
                callback(
                    newExtractorLink(name, name, generalHls, ExtractorLinkType.M3U8) {
                        this.referer = "$mainUrl/"
                        this.headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to "$mainUrl/"
                        )
                    }
                )
            }

            // Extraer los formatos MP4 / TAR alternativos (ej: 720, 480, 360)
            val ua = json.optJSONObject("ua")
            if (ua != null) {
                val keys = ua.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val mediaObj = ua.optJSONObject(key) ?: continue

                    // A veces los mp4 están directo, a veces dentro de un objeto "tar"
                    val tarObj = mediaObj.optJSONObject("tar") ?: mediaObj
                    val videoUrl = tarObj.optString("url")
                    if (videoUrl.startsWith("http")) {
                        val quality = key.toIntOrNull() ?: 0
                        callback(
                            newExtractorLink(name, "$name ${key}p", videoUrl, ExtractorLinkType.VIDEO) {
                                this.referer = "$mainUrl/"
                                this.quality = getQualityFromName("${quality}p")
                            }
                        )
                    }
                }
            }
        } catch (e: Exception) {
            println("MD_DEBUG[RUMBLE_JSON_ERR] ${e.message}")
        }
    }
}