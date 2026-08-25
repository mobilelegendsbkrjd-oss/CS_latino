package com.sololatino

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

object PelisSeriesHoyExtractor {

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"

    private const val BASE = "https://player.pelisserieshoy.com"

    suspend fun load(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val page = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer
                )
            )
            val html = page.text

            // 1. Extracción de tokens homóloga al script de Python
            val tok = Regex("""tok["'\s:=]+["']([a-f0-9]{32})["']""")
                .find(html)?.groupValues?.getOrNull(1)
                ?: Regex("""['"]([a-f0-9]{32})['"]""")
                    .findAll(html)
                    .map { it.groupValues[1] }
                    .firstOrNull()
                ?: return

            val rt = Regex("""rt["'\s:=]+["']([A-Za-z0-9+/=._-]{20,})["']""")
                .find(html)?.groupValues?.getOrNull(1)
                ?: Regex("""dHQw[A-Za-z0-9+/=._-]{20,}""")
                    .find(html)?.value
                ?: ""

            val tk = Regex("""tk["'\s:=]+["']([a-f0-9]{6,})["']""")
                .find(html)?.groupValues?.getOrNull(1)
                ?: ""

            val headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to url,
                "Origin" to BASE,
                "Content-Type" to "application/x-www-form-urlencoded;charset=UTF-8"
            )

            // 2. Simulación del click inicial si el token 'tk' existe (idéntico a Python)
            if (tk.isNotBlank()) {
                try {
                    app.post(
                        "$BASE/s.php",
                        data = mapOf(
                            "a" to "click",
                            "tok" to tok,
                            "rt" to rt,
                            "tk" to tk
                        ),
                        headers = headers
                    )
                } catch (_: Exception) {}
            }

            // 3. Petición de servidores (Fase a=1)
            val scanRes = app.post(
                "$BASE/s.php",
                data = mapOf(
                    "a" to "1",
                    "tok" to tok,
                    "rt" to rt
                ),
                headers = headers
            ).text

            val scanJson = JSONObject(scanRes)
            if (!scanJson.has("s")) return

            val servers = scanJson.getJSONArray("s")

            for (i in 0 until servers.length()) {
                try {
                    val item = servers.getJSONArray(i)
                    if (item.length() < 2) continue

                    val serverName = item.getString(0)
                    val serverId = item.getString(1)

                    // Filtros de servidores bloqueados o no soportados (equivalentes al filtrado estricto en Python)
                    val srvLower = serverName.lowercase()
                    if (srvLower.contains("1fichier") ||
                        srvLower.contains("plustream") ||
                        srvLower.contains("embedsito") ||
                        srvLower.contains("disable") ||
                        srvLower.contains("xupalace") ||
                        srvLower.contains("uploadfox") ||
                        srvLower == "download" ||
                        srvLower == "up2box" ||
                        srvLower.contains("hydrax")) {
                        continue
                    }

                    // 4. Resolución de la URL del reproductor (Fase a=2)
                    val resolveRes = app.post(
                        "$BASE/s.php",
                        data = mapOf(
                            "a" to "2",
                            "v" to serverId,
                            "tok" to tok,
                            "rt" to rt,
                            "tk" to tk
                        ),
                        headers = headers
                    ).text

                    val resolveJson = JSONObject(resolveRes)
                    val u = resolveJson.optString("u")
                    if (u.isBlank()) continue

                    val playerUrl = if (u.startsWith("http")) u else "$BASE$u"

                    // Si el servidor apunta a plataformas internas no soportadas directamente según Python, saltar
                    if (playerUrl.contains("/hydrax.") ||
                        playerUrl.contains("/xupalace.") ||
                        playerUrl.contains("/uploadfox.") ||
                        playerUrl.contains("/embed69.") ||
                        playerUrl.contains("/pelisplay.")) {
                        // Intentamos extraer enlaces directos o pasarlo al gestor secundario si procede
                    }

                    val playerHtml = app.get(
                        playerUrl,
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to url
                        )
                    ).text

                    var found = false

                    // 5. Búsqueda de enlaces de video directos (.m3u8 / .mp4)
                    Regex("""https?://[^\s"'<>]+?\.(m3u8|mp4)[^\s"'<>]*""")
                        .findAll(playerHtml)
                        .forEach { match ->
                            found = true
                            val link = match.value
                            val type = if (link.contains(".m3u8")) {
                                ExtractorLinkType.M3U8
                            } else {
                                ExtractorLinkType.VIDEO
                            }
                            callback.invoke(
                                newExtractorLink(
                                    source = "SoloLatino",
                                    name = serverName,
                                    url = link,
                                    type = type
                                ) {
                                    this.referer = playerUrl
                                }
                            )
                        }

                    // 6. Si no hay enlaces directos, evaluamos la estructura tipo "go_to_playerVast" que maneja el Player 2 en Python
                    if (!found) {
                        val matchesVast = Regex("""go_to_playerVast\s*\(\s*['"]([^'"]+)['"]([^)]*)\)""").findAll(playerHtml)
                        for5@ for (vastMatch in matchesVast) {
                            val matchx = vastMatch.groupValues[1]
                            val restox = vastMatch.groupValues[2]

                            if (matchx.contains("embedsito") || matchx.contains("player-cdn") ||
                                matchx.contains("1fichier") || matchx.contains("hydrax") ||
                                matchx.contains("xupalace") || matchx.contains("uploadfox")) {
                                continue
                            }

                            found = true
                            loadExtractor(matchx, url, subtitleCallback, callback)
                        }
                    }

                    // Si aún contodo no encontró nada de forma interna, recurrimos al genérico de Cloudstream
                    if (!found) {
                        loadExtractor(playerUrl, url, subtitleCallback, callback)
                    }

                } catch (_: Exception) {}
            }

        } catch (_: Exception) {}
    }
}