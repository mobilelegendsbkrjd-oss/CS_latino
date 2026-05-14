package com.sololatino

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class DoodExtractor : ExtractorApi() {

    override val name = "Dood"
    override val mainUrl = "https://dood.la"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        println("[Dood] Procesando: $url")

        try {
            val safeUrl = url
                .replace("/d/", "/e/")
                .replace("doodstream.com", "dood.la")

            val response = app.get(safeUrl, referer = referer ?: mainUrl)
            val html = response.text

            val serverUrls = mutableListOf<String>()

            // 🔥 Buscar pass_md5 (clave real de dood)
            val md5Match = Regex("""/pass_md5/[^'"]+""").find(html)

            if (md5Match != null) {
                val base = Regex("""https://[^/]+""")
                    .find(response.url.toString())
                    ?.value ?: mainUrl

                val md5Url = base + md5Match.value

                println("[Dood] md5Url: $md5Url")

                val prefix = app.get(md5Url, referer = safeUrl).text
                val token = md5Url.substringAfterLast("/")

                val finalUrl = prefix + randomString(10) + "?token=$token"

                println("[Dood] final: $finalUrl")

                // 🔥 mismo estilo que xupalace: usar loadExtractor
                loadExtractor(finalUrl, safeUrl, subtitleCallback, callback)
                return
            }

            // 🔥 fallback si no encuentra md5
            println("[Dood] fallback a loadExtractor")
            loadExtractor(safeUrl, referer, subtitleCallback, callback)

        } catch (e: Exception) {
            println("[Dood] ERROR: ${e.message}")
            loadExtractor(url, referer, subtitleCallback, callback)
        }
    }

    private fun randomString(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length).map { chars.random() }.joinToString("")
    }
}