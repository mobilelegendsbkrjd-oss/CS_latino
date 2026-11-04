package lacartoons

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document

class LACartoons : MainAPI() {
    override var mainUrl = "https://www.lacartoons.com"
    override var name = "LACartoons"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Cartoon,
        TvType.TvSeries
    )

    private fun Document.toSearchResult(): List<SearchResponse> {
        return this.select(".conjuntos-series a, a[href*=/serie/]").mapNotNull { a ->
            val href = a.attr("href").takeIf { it.contains("/serie/") } ?: return@mapNotNull null
            // Evitar links de episodios
            if (href.contains("/capitulo/")) return@mapNotNull null

            val title = a.selectFirst("p.nombre-serie")?.text()?.trim()
                ?: a.selectFirst(".nombre-serie")?.text()?.trim()
                ?: a.attr("title")?.trim()
                ?: a.text().trim()
                ?: return@mapNotNull null

            if (title.isBlank()) return@mapNotNull null

            val img = a.selectFirst("img")?.attr("src")
                ?: a.selectFirst("img")?.attr("data-src")
            val poster = img?.let { fixUrl(it) }

            newTvSeriesSearchResponse(title, fixUrl(href), TvType.Cartoon) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val soup = app.get(mainUrl).document
        val home = soup.toSearchResult()
        return newHomePageResponse(
            listOf(HomePageList("Series", home)),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?utf8=✓&Titulo=${query.replace(" ", "+")}").document
        return doc.toSearchResult()
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h2.text-center, h2.subtitulo-linea, .hbtoonh1")?.text()
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            ?: "Sin título"

        val description = doc.selectFirst(".informacion-serie-seccion p:contains(Reseña), .resena-serie p, .informacion-serie p")
            ?.text()
            ?.substringAfter("Reseña:")
            ?.trim()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")

        val poster = doc.selectFirst(".imagen-serie img, .serie img")?.attr("src")
            ?: doc.selectFirst("img.fondo-serie-seccion")?.attr("src")
        val backposter = doc.selectFirst("img.fondo-serie-seccion")?.attr("src")

        val episodes = mutableListOf<Episode>()

        // Temporadas + episodios
        doc.select(".listas-de-episodion li a, ul.listas-de-episodion a").forEach { a ->
            val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@forEach
            val fullHref = fixUrl(href)

            val rawName = a.text().trim()
            // Extraer número de capítulo
            val epNum = Regex("""Capitulo\s*(\d+)|Capítulo\s*(\d+)|Episodio\s*(\d+)""", RegexOption.IGNORE_CASE)
                .find(rawName)
                ?.groupValues
                ?.drop(1)
                ?.firstOrNull { it.isNotBlank() }
                ?.toIntOrNull()
                ?: Regex("""/capitulo/(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()

            val seasonNum = Regex("""[?&]t=(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                ?: 1

            val cleanName = rawName
                .replace(Regex("""Capitulo\s*\d+\s*-?\s*|Capítulo\s*\d+\s*-?\s*""", RegexOption.IGNORE_CASE), "")
                .trim()
                .ifBlank { "Episodio ${epNum ?: "?"}" }

            episodes.add(
                newEpisode(fullHref) {
                    this.name = cleanName
                    this.season = seasonNum
                    this.episode = epNum
                }
            )
        }

        // Si no hay lista de episodios (estamos en un capítulo individual), crear al menos el actual
        if (episodes.isEmpty() && url.contains("/capitulo/")) {
            val epNum = Regex("""/capitulo/(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull()
            val seasonNum = Regex("""[?&]t=(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            episodes.add(
                newEpisode(url) {
                    name = "Episodio ${epNum ?: 1}"
                    season = seasonNum
                    episode = epNum
                }
            )
        }

        return newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes.sortedWith(compareBy({ it.season }, { it.episode }))) {
            this.posterUrl = poster?.let { fixUrl(it) }
            this.backgroundPosterUrl = backposter?.let { fixUrl(it) }
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        var found = false

        // 1) Selectores específicos del HTML real
        val iframes = doc.select(
            "section.contenedor-video-recomendaciones iframe, " +
                    ".serie-video-informacion iframe, " +
                    ".contenedor-video iframe, " +
                    "iframe[src*='ok.ru'], " +
                    "iframe[src*='videoembed'], " +
                    "iframe[src*='streamtape'], " +
                    "iframe[src*='dood'], " +
                    "iframe"
        ).distinctBy { it.attr("src") + it.attr("data-src") }

        for (iframe in iframes) {
            var link = iframe.attr("src")
                .ifBlank { iframe.attr("data-src") }
                .trim()

            if (link.isBlank() || link.startsWith("about:")) continue

            // Normalizar acortadores
            link = link
                .replace("https://short.ink/", "https://abysscdn.com/?v=")
                .replace("http://", "https://")

            if (!link.startsWith("http")) {
                link = fixUrl(link)
            }

            // ok.ru necesita ir directo al extractor, no como iframe genérico
            if (link.contains("ok.ru")) {
                // Intentar con loadExtractor
                if (loadExtractor(link, data, subtitleCallback, callback)) {
                    found = true
                    continue
                }
                // Fallback manual: extraer el ID y armar URL de embed limpia
                val okId = Regex("""ok\.ru/(?:videoembed|video)/(\d+)""")
                    .find(link)?.groupValues?.get(1)
                if (okId != null) {
                    val cleanUrl = "https://ok.ru/videoembed/$okId"
                    if (loadExtractor(cleanUrl, data, subtitleCallback, callback)) {
                        found = true
                        continue
                    }
                }
            }

            if (loadExtractor(link, data, subtitleCallback, callback)) {
                found = true
            }
        }

        // 2) Fallback: buscar cualquier URL de ok.ru embebida en el HTML crudo
        if (!found) {
            val regex = Regex("""https?://(?:www\.)?ok\.ru/(?:videoembed|video)/(\d+)""")
            regex.findAll(doc.html()).map { it.value }.distinct().forEach { okUrl ->
                val clean = okUrl.replace("/video/", "/videoembed/")
                if (loadExtractor(clean, data, subtitleCallback, callback)) {
                    found = true
                }
            }
        }

        return found
    }
}