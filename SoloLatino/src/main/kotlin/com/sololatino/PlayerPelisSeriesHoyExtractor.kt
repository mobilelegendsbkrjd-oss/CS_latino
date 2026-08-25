package com.sololatino

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import kotlin.text.Regex

object PlayerPelisSeriesHoyExtractor {

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

    private const val BASE = "https://player.pelisserieshoy.com"
    private const val TAG = "PlayerPelis"

    suspend fun load(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        try {
            Log.d(TAG, "=== INICIANDO EXTRACTOR ===")
            Log.d(TAG, "URL: $url")
            Log.d(TAG, "Referer: $referer")

            // ============================================
            // PRIMERO: Obtener el HTML de la página de SOLOLATINO
            // (donde está el iframe) para extraer rt y tk
            // ============================================
            var rt = ""
            var tk = ""

            try {
                val refererHtml = app.get(
                    referer,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to "https://sololatino.net/"
                    )
                ).text

                Log.d(TAG, "Referer HTML length: ${refererHtml.length}")

                // ============================================
                // Buscar el iframe de player.pelisserieshoy.com
                // y extraer rt de la URL del iframe
                // ============================================
                val iframeMatch = Regex("""<iframe[^>]*src=["']([^"']*player\.pelisserieshoy\.com[^"']*)["']""")
                    .find(refererHtml)
                if (iframeMatch != null) {
                    val iframeUrl = iframeMatch.groupValues[1]
                    Log.d(TAG, "🔍 Iframe URL: $iframeUrl")

                    val rtInUrl = Regex("""[?&]rt=([A-Za-z0-9+/=._-]{20,})""").find(iframeUrl)
                    if (rtInUrl != null) {
                        rt = rtInUrl.groupValues[1]
                        Log.d(TAG, "✅ rt encontrado en URL del iframe: $rt")
                    }

                    val tkInUrl = Regex("""[?&]tk=([a-f0-9]{6,})""").find(iframeUrl)
                    if (tkInUrl != null) {
                        tk = tkInUrl.groupValues[1]
                        Log.d(TAG, "✅ tk encontrado en URL del iframe: $tk")
                    }
                }

                // Si no se encontró rt en el iframe, buscar en script
                if (rt.isBlank()) {
                    val rtScriptMatch = Regex("""rt\s*:\s*["']([A-Za-z0-9+/=._-]{20,})["']""")
                        .find(refererHtml)
                    if (rtScriptMatch != null) {
                        rt = rtScriptMatch.groupValues[1]
                        Log.d(TAG, "✅ rt encontrado en script: $rt")
                    }
                }

                // Si no se encontró rt, buscar en atributos data-rt
                if (rt.isBlank()) {
                    val rtDataMatch = Regex("""data-rt=["']([A-Za-z0-9+/=._-]{20,})["']""")
                        .find(refererHtml)
                    if (rtDataMatch != null) {
                        rt = rtDataMatch.groupValues[1]
                        Log.d(TAG, "✅ rt encontrado en data-rt: $rt")
                    }
                }

                // Si no se encontró rt, buscar el patrón dHQw...
                if (rt.isBlank()) {
                    rt = Regex("""dHQw[A-Za-z0-9+/=._-]{20,}""")
                        .find(refererHtml)?.value
                        ?: ""
                    if (rt.isNotBlank()) {
                        Log.d(TAG, "✅ rt encontrado como dHQw: $rt")
                    }
                }

                // Buscar tk en el HTML del referer
                if (tk.isBlank()) {
                    val tkScriptMatch = Regex("""tk\s*:\s*["']([a-f0-9]{6,})["']""")
                        .find(refererHtml)
                    if (tkScriptMatch != null) {
                        tk = tkScriptMatch.groupValues[1]
                        Log.d(TAG, "✅ tk encontrado en script: $tk")
                    }
                }

                if (tk.isBlank()) {
                    tk = Regex("""tk["'\s:=]+["']([a-f0-9]{6,})["']""")
                        .find(refererHtml)?.groupValues?.getOrNull(1)
                        ?: ""
                    if (tk.isNotBlank()) {
                        Log.d(TAG, "✅ tk encontrado: $tk")
                    }
                }

                Log.d(TAG, "rt encontrado: '$rt'")
                Log.d(TAG, "tk encontrado: '$tk'")

            } catch (e: Exception) {
                Log.d(TAG, "Error obteniendo referer: ${e.message}")
            }

            // ============================================
            // SEGUNDO: Obtener el HTML del reproductor
            // ============================================
            val res = app.get(
                url,
                headers = mapOf(
                    "Referer" to referer,
                    "User-Agent" to USER_AGENT
                )
            )

            val html = res.text
            Log.d(TAG, "Player HTML length: ${html.length}")

            // ============================================
            // 1. EXTRAER TOKEN (_t)
            // ============================================
            var tok = Regex("""const\s+_t\s*=\s*'([^']+)'""")
                .find(html)?.groupValues?.getOrNull(1)

            if (tok.isNullOrBlank()) {
                tok = Regex("""let\s+token\s*=\s*'([^']+)'""")
                    .find(html)?.groupValues?.getOrNull(1)
            }

            if (tok.isNullOrBlank()) {
                tok = Regex("""var\s+token\s*=\s*'([^']+)'""")
                    .find(html)?.groupValues?.getOrNull(1)
            }

            if (tok.isNullOrBlank()) {
                tok = Regex("""['"]([a-f0-9]{32})['"]""")
                    .findAll(html)
                    .map { it.groupValues[1] }
                    .firstOrNull()
            }

            if (tok.isNullOrBlank()) {
                Log.e(TAG, "❌ No se encontró token")
                return
            }

            Log.d(TAG, "✅ Token encontrado: $tok")

            // Si no se encontró rt en el referer, buscar en el HTML del reproductor
            if (rt.isBlank()) {
                val rtScriptMatch = Regex("""rt\s*:\s*["']([A-Za-z0-9+/=._-]{20,})["']""")
                    .find(html)
                if (rtScriptMatch != null) {
                    rt = rtScriptMatch.groupValues[1]
                    Log.d(TAG, "rt desde player script: $rt")
                }
            }

            if (rt.isBlank()) {
                rt = Regex("""rt["'\s:=]+["']([A-Za-z0-9+/=._-]{20,})["']""")
                    .find(html)?.groupValues?.getOrNull(1)
                    ?: Regex("""dHQw[A-Za-z0-9+/=._-]{20,}""")
                        .find(html)?.value
                            ?: ""
                Log.d(TAG, "rt desde player HTML: $rt")
            }

            if (tk.isBlank()) {
                tk = Regex("""tk["'\s:=]+["']([a-f0-9]{6,})["']""")
                    .find(html)?.groupValues?.getOrNull(1)
                    ?: ""
                Log.d(TAG, "tk desde player HTML: $tk")
            }

            Log.d(TAG, "rt final: '$rt'")
            Log.d(TAG, "tk final: '$tk'")

            // ============================================
            // 3. HEADERS
            // ============================================
            val headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to url,
                "Origin" to BASE,
                "Content-Type" to "application/x-www-form-urlencoded;charset=UTF-8"
            )

            // ============================================
            // 4. CLICK
            // ============================================
            try {
                val clickData = mutableMapOf(
                    "a" to "click",
                    "tok" to tok
                )
                if (rt.isNotBlank()) clickData["rt"] = rt
                if (tk.isNotBlank()) clickData["tk"] = tk

                Log.d(TAG, "Click data: $clickData")

                val clickResponse = app.post(
                    "$BASE/s.php",
                    data = clickData,
                    headers = headers
                )
                Log.d(TAG, "Click response: ${clickResponse.text}")
            } catch (e: Exception) {
                Log.e(TAG, "Error en click: ${e.message}")
            }

            // ============================================
            // 5. SCAN SERVERS (a=1) - CON Y SIN rt
            // ============================================
            var scanJson: JSONObject? = null
            var scanRes = ""

            // Intentar con rt primero
            if (rt.isNotBlank()) {
                val scanData = mapOf(
                    "a" to "1",
                    "tok" to tok,
                    "rt" to rt
                )
                Log.d(TAG, "Scan data (con rt): $scanData")
                scanRes = app.post(
                    "$BASE/s.php",
                    data = scanData,
                    headers = headers
                ).text
                Log.d(TAG, "Scan response (con rt): $scanRes")
                scanJson = JSONObject(scanRes)
            }

            // Si falló o no hay servidores, intentar sin rt
            if (scanJson == null || (scanJson.has("type") && scanJson.optString("type") == "error")) {
                Log.d(TAG, "Intentando sin rt...")
                val scanData2 = mapOf(
                    "a" to "1",
                    "tok" to tok
                )
                scanRes = app.post(
                    "$BASE/s.php",
                    data = scanData2,
                    headers = headers
                ).text
                Log.d(TAG, "Scan response (sin rt): $scanRes")
                scanJson = JSONObject(scanRes)
            }

            if (scanJson == null) {
                Log.e(TAG, "❌ No se pudo obtener respuesta de scan")
                return
            }

            if (scanJson.has("type") && scanJson.optString("type") == "error") {
                Log.e(TAG, "❌ Error en scan: ${scanJson.optString("msg")}")
                return
            }

            // ============================================
            // 6. PROCESAR SERVIDORES
            // ============================================
            val servers = mutableListOf<Pair<String, String>>()

            // Buscar en "s"
            if (scanJson.has("s")) {
                val array = scanJson.getJSONArray("s")
                Log.d(TAG, "Servidores en 's': ${array.length()}")
                for (i in 0 until array.length()) {
                    val item = array.getJSONArray(i)
                    if (item.length() >= 2) {
                        val serverName = item.getString(0)
                        val serverId = item.getString(1)
                        servers.add(Pair(serverName, serverId))
                        Log.d(TAG, "Servidor (s): $serverName (ID: $serverId)")
                    }
                }
            }

            // Buscar en langs_s
            if (scanJson.has("langs_s")) {
                val langs = scanJson.getJSONObject("langs_s")
                val langKeys = langs.keys()
                while (langKeys.hasNext()) {
                    val langKey = langKeys.next()
                    val langArray = langs.getJSONArray(langKey)
                    Log.d(TAG, "  $langKey: ${langArray.length()} servidores")
                    for (i in 0 until langArray.length()) {
                        val item = langArray.getJSONArray(i)
                        if (item.length() >= 2) {
                            val serverName = item.getString(0)
                            val serverId = item.getString(1)
                            if (!servers.any { it.second == serverId }) {
                                servers.add(Pair(serverName, serverId))
                                Log.d(TAG, "Servidor (langs_s[$langKey]): $serverName (ID: $serverId)")
                            }
                        }
                    }
                }
            }

            if (servers.isEmpty()) {
                Log.e(TAG, "❌ No se encontraron servidores")
                return
            }

            Log.d(TAG, "✅ Total servidores encontrados: ${servers.size}")

            // ============================================
            // 7. PROCESAR CADA SERVIDOR
            // ============================================
            for ((serverName, serverId) in servers) {
                try {
                    Log.d(TAG, "--- Procesando: $serverName (ID: $serverId) ---")

                    val srvLower = serverName.lowercase()
                    if (srvLower.contains("1fichier") ||
                        srvLower.contains("plustream") ||
                        srvLower.contains("embedsito") ||
                        srvLower.contains("disable") ||
                        srvLower.contains("xupalace") ||
                        srvLower.contains("uploadfox") ||
                        srvLower == "up2box") {
                        Log.d(TAG, "Servidor bloqueado: $serverName")
                        continue
                    }

                    fun getLanguage(): String {
                        return when {
                            serverName.contains("LAT", ignoreCase = true) ||
                                    serverName.contains("Latino", ignoreCase = true) ||
                                    serverName.contains("LATINO", ignoreCase = true) -> "LAT"
                            serverName.contains("ESP", ignoreCase = true) ||
                                    serverName.contains("Español", ignoreCase = true) ||
                                    serverName.contains("CAST", ignoreCase = true) -> "ESP"
                            serverName.contains("SUB", ignoreCase = true) ||
                                    serverName.contains("Subtitulado", ignoreCase = true) ||
                                    serverName.contains("VOSE", ignoreCase = true) -> "SUB"
                            else -> "LAT"
                        }
                    }

                    val resolveData = mutableMapOf(
                        "a" to "2",
                        "v" to serverId,
                        "tok" to tok
                    )
                    if (rt.isNotBlank()) resolveData["rt"] = rt
                    if (tk.isNotBlank()) resolveData["tk"] = tk

                    val resolveRes = app.post(
                        "$BASE/s.php",
                        data = resolveData,
                        headers = headers
                    ).text

                    Log.d(TAG, "Resolve response: $resolveRes")

                    val resolveJson = JSONObject(resolveRes)
                    val u = resolveJson.optString("u")

                    if (u.isBlank()) {
                        Log.d(TAG, "URL vacía para $serverName")
                        continue
                    }

                    val playerUrl = if (u.startsWith("http")) u else "$BASE$u"
                    Log.d(TAG, "Player URL: $playerUrl")

                    if (playerUrl.contains("playhydrax") || playerUrl.contains("abyssplayer")) {
                        Log.d(TAG, "✅ Detectado PlayHydrax para $serverName")
                        val lang = getLanguage()
                        PlayHydrax().withLanguage(lang).getUrl(playerUrl, url, subtitleCallback, callback)
                        continue
                    }

                    if (playerUrl.contains(".m3u8") || playerUrl.contains(".mp4")) {
                        val type = if (playerUrl.contains(".m3u8")) {
                            ExtractorLinkType.M3U8
                        } else {
                            ExtractorLinkType.VIDEO
                        }
                        val lang = getLanguage()
                        callback.invoke(
                            newExtractorLink(
                                source = "SoloLatino",
                                name = "$lang[$serverName]",
                                url = playerUrl,
                                type = type
                            ) {
                                this.referer = url
                            }
                        )
                        continue
                    }

                    val playerHtml = app.get(
                        playerUrl,
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to url
                        )
                    ).text

                    var found = false

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
                            val lang = getLanguage()
                            callback.invoke(
                                newExtractorLink(
                                    source = "SoloLatino",
                                    name = "$lang[$serverName]",
                                    url = link,
                                    type = type
                                ) {
                                    this.referer = playerUrl
                                }
                            )
                        }

                    if (!found) {
                        val matchesVast = Regex("""go_to_playerVast\s*\(\s*['"]([^'"]+)['"]([^)]*)\)""")
                            .findAll(playerHtml)

                        for (vastMatch in matchesVast) {
                            val matchx = vastMatch.groupValues[1]
                            if (matchx.contains("embedsito") || matchx.contains("player-cdn") ||
                                matchx.contains("1fichier") || matchx.contains("hydrax") ||
                                matchx.contains("xupalace") || matchx.contains("uploadfox")) {
                                continue
                            }
                            found = true
                            loadExtractor(matchx, url, subtitleCallback, callback)
                        }
                    }

                    if (!found) {
                        if (playerUrl.contains("/embed69.") || playerUrl.contains("/pelisplay.")) {
                            Embed69Extractor.load(playerUrl, url, subtitleCallback, callback)
                        } else if (playerUrl.contains("playhydrax") || playerUrl.contains("abyssplayer")) {
                            Log.d(TAG, "Fallback: Detectado PlayHydrax en $playerUrl")
                            val lang = getLanguage()
                            PlayHydrax().withLanguage(lang).getUrl(playerUrl, url, subtitleCallback, callback)
                        } else {
                            loadExtractor(playerUrl, url, subtitleCallback, callback)
                        }
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Error procesando $serverName: ${e.message}")
                }
            }

            kotlinx.coroutines.delay(1000)

        } catch (e: Exception) {
            Log.e(TAG, "Error general: ${e.message}")
            e.printStackTrace()
        }
    }
}