package com.hackstore

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

object HackStoreApi {
    private const val api = "https://hackstore2.com"

    suspend fun search(type: String, query: String, page: Int): SearchResult {
        // CORRECCIÓN: La API correcta es /api/rest/posts o /api/rest/search
        val url = if (query.isEmpty()) {
            "$api/api/rest/posts?type=$type&page=$page"
        } else {
            "$api/api/rest/search?query=$query"
        }

        // Añadimos el User-Agent para que el servidor no bloquee la petición
        val response = app.get(url, headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        ))

        return response.parsedSafe<SearchResult>() ?: SearchResult(emptyList())
    }

    suspend fun detail(id: String): HackItem? {
        // La web ahora usa /api/rest/post/id
        return app.get("$api/api/rest/post/$id").parsedSafe<HackItem>()
    }

    // Nota: Si los endpoints de episodes/player también cambiaron,
    // tendrías que cambiar la URL aquí a "$api/api/rest/episodes/..."
    // basándote en la estructura que veas en la pestaña Network de tu navegador.
    suspend fun episodes(id: String): List<Episode> {
        return app.get("$api/api/rest/episodes/$id").parsedSafe<List<Episode>>() ?: emptyList()
    }

    suspend fun player(id: String): List<Player> {
        return app.get("$api/api/rest/player/$id").parsedSafe<List<Player>>() ?: emptyList()
    }
}