package com.sololatino

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

object Embed69Extractor {

    suspend fun load(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        val html = app.get(url, headers = mapOf("Referer" to referer)).text

        // =========================
        // dataLink
        // =========================
        Regex("""dataLink\s*=\s*(\[.*?\]);""")
            .find(html)?.groupValues?.getOrNull(1)?.let { json ->

                val parsed = AppUtils.tryParseJson<List<Map<String, Any>>>(json) ?: return

                parsed.forEach { lang ->
                    val embeds = lang["sortedEmbeds"] as? List<Map<String, Any>> ?: return@forEach

                    embeds.forEach { embed ->

                        val enc = embed["link"] as? String ?: return@forEach
                        val real = decode(enc) ?: return@forEach

                        val fixed = fixHosts(real)

                        // =========================
                        // 🔥 XUPALACE FIX REAL
                        // =========================
                        if (fixed.contains("xupalace")) {

                            try {
                                val htmlX = app.get(
                                    fixed,
                                    headers = mapOf(
                                        "Referer" to url,
                                        "User-Agent" to USER_AGENT
                                    )
                                ).text

                                // 🔥 PARSEO GLOBAL (NO SOLO li)
                                Regex("""go_to_playerVast\(\s*['"]([^'"]+)""")
                                    .findAll(htmlX)
                                    .mapNotNull { it.groupValues.getOrNull(1) }
                                    .forEach { deep ->

                                        val finalLink = fixHosts(deep)

                                        loadExtractor(
                                            finalLink,
                                            fixed, // 👈 IMPORTANTÍSIMO REFERER
                                            subtitleCallback,
                                            callback
                                        )
                                    }

                            } catch (_: Exception) {}

                            return
                        }
                    }
                }
            }

        // =========================
        // fallback iframe directo
        // =========================
        app.get(url).document.selectFirst("iframe")?.attr("src")?.let {
            val fixed = fixHosts(it)
            loadExtractor(fixed, url, subtitleCallback, callback)
        }
    }

    // =========================
    // BASE64 decode
    // =========================
    private fun decode(enc: String): String? {
        return try {
            val parts = enc.split(".")
            if (parts.size != 3) return null

            var payload = parts[1]
            val pad = payload.length % 4
            if (pad != 0) payload += "=".repeat(4 - pad)

            val json = String(Base64.decode(payload, Base64.DEFAULT))

            Regex("\"link\":\"(.*?)\"")
                .find(json)?.groupValues?.getOrNull(1)

        } catch (_: Exception) {
            null
        }
    }

    // =========================
    // HOST FIX (CLAVE)
    // =========================
    private fun fixHosts(url: String): String {
        return url
            .replace("hglink.to", "streamwish.to")
            .replace("swdyu.com", "streamwish.to")
            .replace("cybervynx.com", "streamwish.to")
            .replace("dumbalag.com", "streamwish.to")
            .replace("wishembed.com", "streamwish.to")
            .replace("stwishe.com", "streamwish.to")

            .replace("mivalyo.com", "vidhidepro.com")
            .replace("dinisglows.com", "vidhidepro.com")
            .replace("dhtpre.com", "vidhidepro.com")
            .replace("vidhide.com", "vidhidepro.com")
            .replace("voidboost.net", "vidhidepro.com")

            .replace("filemoon.link", "filemoon.sx")
            .replace("filemoon.lat", "filemoon.sx")

            .replace("uqload.io", "uqload.com")
            .replace("voe.sx", "voe.unblockit.cat")
    }
}