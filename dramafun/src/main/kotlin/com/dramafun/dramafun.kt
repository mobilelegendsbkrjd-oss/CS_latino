package com.dramafun

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import android.util.Base64
import org.json.JSONObject

class DramaFun : MainAPI() {

    override var mainUrl = "https://ww6.dramafuntv.com"
    override var name = "DramaFun"
    override val hasMainPage = true
    override var lang = "es"

    // Mejor tratar todo como Movie porque no hay series reales
    override val supportedTypes = setOf(TvType.Movie, TvType.Others)

    // ================= HOME =================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val home = mutableListOf<HomePageList>()

        listOf(
            "Últimos" to "$mainUrl/newvideos.php",
            "Top" to "$mainUrl/topvideos.php",
            "Doramas Sub" to "$mainUrl/category.php?cat=Doramas-Sub-Espanol",
            "Novelas Turcas Sub" to "$mainUrl/category.php?cat=Novelas-Turcas-Subtituladas",
            "Películas Latino" to "$mainUrl/category.php?cat=peliculas-audio-espanol-latino"
        ).forEach { (name, url) ->
            val list = getVideoList(url)
            if (list.isNotEmpty()) home.add(HomePageList(name, list))
        }

        return newHomePageResponse(home)
    }

    // ================= PARSER GENÉRICO PARA LISTAS =================
    private suspend fun getVideoList(url: String): List<SearchResponse> {
        val doc = app.get(url, timeout = 30).document
        return parseVideoList(doc)
    }

    private fun parseVideoList(doc: Document): List<SearchResponse> {
        // El sitio usa texto plano con <a href="watch.php?vid=...">[Título]</a>
        // Buscamos todos los <a> que contengan watch.php?vid=
        return doc.select("a[href*=watch.php?vid=]").mapNotNull { a ->
            val href = a.attr("href").let { if (it.startsWith("http")) it else "$mainUrl/$it" }
            val titleRaw = a.ownText().trim().removeSurrounding("[", "]").trim()
            val cleanTitle = titleRaw.replace(Regex("(?i)\\(en\\s*Español\\)|Sub\\s*Español|Completo|HD"), "").trim()

            newMovieSearchResponse(
                name = cleanTitle,
                url = href,
                apiName = name,
                type = TvType.Movie   // o TvType.Others
            ) {
                posterUrl = null      // no hay posters confiables
            }
        }.distinctBy { it.url }
    }

    // ================= SEARCH =================
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val doc = app.get("$mainUrl/search.php?keywords=$query").document
        return parseVideoList(doc)
    }

    // ================= LOAD (solo movie / episodio suelto) =================
    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val titleRaw = doc.selectFirst("h1, h2, .page-title, title")?.text()?.trim() ?: "Sin título"
        val cleanTitle = titleRaw.replace(Regex("(?i)Capítulo.*|\\(en\\s*Español\\)|en\\s*Español"), "").trim()

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst(".pm-video-thumb img, img[src*=poster]")?.attr("abs:src")

        return newMovieLoadResponse(
            name = cleanTitle,
            url = url,
            apiName = name,
            type = TvType.Movie,
            data = url
        ) {
            posterUrl = poster
            plot = doc.selectFirst(".description, .sinopsis, p")?.text()?.trim()
        }
    }

    // ================= LOAD LINKS =================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val doc = app.get(data).document

        // Intento 1: Buscar enlaces directos a enfun.php (clase puede haber cambiado)
        var enfunLinks = doc.select("a[href*=enfun.php?post=]").map { it.attr("abs:href") }

        // Intento 2: Si no hay, busca cualquier a[href] que parezca player o base64
        if (enfunLinks.isEmpty()) {
            enfunLinks = doc.select("a[href*=post=]").map { it.attr("abs:href") }
        }

        enfunLinks.forEach { enfunUrl ->
            val enfunDoc = app.get(enfunUrl, referer = data).document

            // Busca JSON o base64 en scripts
            enfunDoc.select("script").forEach { script ->
                val text = script.data() ?: script.html()
                if ("servers" in text || "post=" in text) {
                    // Intenta extraer base64 o JSON directamente
                    val possibleB64 = Regex("post=([A-Za-z0-9+/=]+)").find(enfunUrl)?.groupValues?.get(1)
                    if (possibleB64 != null) {
                        try {
                            val decoded = String(Base64.decode(possibleB64, Base64.DEFAULT))
                            val json = JSONObject(decoded)
                            if (json.has("servers")) {
                                val servers = json.getJSONObject("servers")
                                servers.keys().forEach { key ->
                                    val serverUrl = servers.getString(key)
                                    loadExtractor(serverUrl, data, subtitleCallback, callback)
                                }
                            }
                        } catch (_: Throwable) { }
                    }
                }
            }

            // Fallback: cualquier iframe o video src
            enfunDoc.select("iframe[src], video source[src]").forEach { el ->
                val src = el.attr("abs:src") ?: return@forEach
                callback(
                    ExtractorLink(
                        this.name,
                        "Embed ${el.attr("src")}",
                        src,
                        referer = enfunUrl,
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.VIDEO
                    )
                )
            }
        }

        // Si nada → prueba extractors directos en la página watch
        loadExtractor(data, data, subtitleCallback, callback)

        return true
    }
}
