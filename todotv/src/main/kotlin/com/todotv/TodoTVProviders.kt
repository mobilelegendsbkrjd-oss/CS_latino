package com.todotv

data class TodoProvider(
    val name: String,
    val baseUrl: String,
    val dynamic: Boolean
)

object TodoTVProviders {
    val providers = listOf(
        TodoProvider("TvporinternetHD", "https://www.tvporinternet2.com", true),
        TodoProvider("Tv Libre Futbol", "https://www.librefutbol2.com", true),
        TodoProvider("CableVisionHD", "https://www.cablevisionhd.com", true),
        TodoProvider("Teveplus", "https://www.tvplusgratis2.com/", true),
        TodoProvider("Telegratis", "https://www.telegratishd.com/", true),
        TodoProvider("VerCableHD", "https://www.vertvcable.com/", false),
        TodoProvider("SinTelevisor", "https://www.thesintelevisor.com/", false)
    )
}
