package com.sololatino

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import kotlinx.coroutines.delay

class PlayHydrax : ExtractorApi() {

    override var name = "PlayHydrax"
    override var mainUrl = "https://playhydrax.com"
    override val requiresReferer = true

    private var language: String = "LAT"

    fun withLanguage(lang: String): PlayHydrax {
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
            Log.d("PlayHydrax", "=== INICIANDO ===")
            Log.d("PlayHydrax", "URL: $url")
            Log.d("PlayHydrax", "Idioma: $language")

            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
                "Origin" to "https://playhydrax.com",
                "Referer" to "https://playhydrax.com/"
            )

            // Obtener el HTML
            val response = app.get(url, headers = headers)
            val html = response.text
            Log.d("PlayHydrax", "HTML length: ${html.length}")

            // Buscar el contenido encriptado
            val encryptedRegex = Regex("""const\s+datas\s*=\s*"([^"]*)"""")
            val encryptedMatch = encryptedRegex.find(html)

            if (encryptedMatch == null) {
                Log.e("PlayHydrax", "No se encontró 'const datas'")
                return
            }

            val encrypted = encryptedMatch.groupValues[1]
            Log.d("PlayHydrax", "Encrypted encontrado: ${encrypted.take(50)}...")

            // Crear el RequestBody
            val jsonBody = """{"text": "$encrypted"}""".trimIndent()
            val requestBody = jsonBody.toRequestBody("application/json".toMediaType())

            // Llamar a la API
            val apiResponse = app.post(
                url = "https://enc-dec.app/api/dec-abyss",
                headers = headers,
                requestBody = requestBody
            )

            Log.d("PlayHydrax", "Response code: ${apiResponse.code}")

            if (apiResponse.code != 200) {
                Log.e("PlayHydrax", "API error: code ${apiResponse.code}")
                return
            }

            val json = JSONObject(apiResponse.text)
            Log.d("PlayHydrax", "Response: ${apiResponse.text.take(200)}")

            val status = json.optLong("status", 0)
            if (status != 200L) {
                Log.e("PlayHydrax", "API error: status $status")
                return
            }

            val result = json.optJSONObject("result")
            if (result == null) {
                Log.e("PlayHydrax", "No se encontró 'result'")
                return
            }

            val sourcesArray = result.optJSONArray("sources")
            if (sourcesArray == null) {
                Log.e("PlayHydrax", "No se encontró 'sources'")
                return
            }

            Log.d("PlayHydrax", "Fuentes encontradas: ${sourcesArray.length()}")

            var found = false

            for (i in 0 until sourcesArray.length()) {
                val source = sourcesArray.getJSONObject(i)
                val statusSource = source.optBoolean("status", false)

                if (!statusSource) {
                    Log.d("PlayHydrax", "Fuente ${i+1} no disponible")
                    continue
                }

                val videoUrl = source.optString("url")
                val type = source.optString("type")
                val codec = source.optString("codec")

                if (videoUrl.isBlank()) {
                    Log.d("PlayHydrax", "Fuente ${i+1} URL vacía")
                    continue
                }

                Log.d("PlayHydrax", "✅ Fuente ${i+1}: type=$type, codec=$codec")

                val quality = getQualityFromName(type)

                val nameStr = if (codec.isNotBlank()) {
                    "$language[$name-${codec.uppercase()}]"
                } else {
                    "$language[$name]"
                }

                callback.invoke(
                    newExtractorLink(
                        source = "SoloLatino",
                        name = nameStr,
                        url = videoUrl,
                        type = INFER_TYPE
                    ) {
                        this.quality = quality
                        this.headers = mapOf(
                            "Referer" to "https://playhydrax.com/"
                        )
                    }
                )
                found = true
                Log.d("PlayHydrax", "✅ Link agregado: $nameStr")
            }

            if (!found) {
                Log.w("PlayHydrax", "No se encontraron fuentes disponibles")
            }

            delay(500)

        } catch (e: Exception) {
            Log.e("PlayHydrax", "Error: ${e.message}")
            e.printStackTrace()
        }
    }
}