package com.hackstore

data class SearchResult(
    val posts: List<HackItem> = emptyList()
)

data class HackItem(

    val _id: String = "",
    val title: String = "",
    val slug: String = "",
    val type: String = "",
    val overview: String? = null,
    val images: Images? = null

) {

    fun poster(): String? {
        return images?.poster?.let {
            "https://hackstore2.com/wp-content/uploads/$it"
        }
    }

    fun backdrop(): String? {
        return images?.backdrop?.let {
            "https://hackstore2.com/wp-content/uploads/$it"
        }
    }
}

data class Images(

    val poster: String? = null,
    val backdrop: String? = null

)

data class Player(

    val lang: String? = null,
    val url: String = "",
    val quality: String? = null

)

data class Episode(

    val season_number: Int = 1,
    val episode_number: Int = 1,
    val still_path: String? = null

)