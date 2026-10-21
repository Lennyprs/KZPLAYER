package com.kzplayer.app

// Etat global partage entre les ecrans (simple et suffisant pour cette app).
object Session {
    var playlists: List<Playlist> = emptyList()
    var current: Playlist? = null
    var expiration: String? = null
    var browseTitle: String = ""
    var detailItem: Item? = null
    var seriesItem: Item? = null
    var liveChannels: List<Item> = emptyList()
    // v353 : liste EXACTE des chaines de la categorie affichee, et position de la
    // chaine lancee dedans. Sert au zapping fleche haut / bas dans le lecteur.
    var zapChannels: List<Item> = emptyList()
    var zapIndex: Int = -1
    // File d'attente des episodes pour la lecture a la suite (fonctionne sur les 2 themes).
    var episodeQueue: List<Item> = emptyList()
    var episodeIndex: Int = -1
    fun clear() {
        playlists = emptyList()
        current = null
        expiration = null
        browseTitle = ""
        detailItem = null
        seriesItem = null
        liveChannels = emptyList()
        zapChannels = emptyList()
        zapIndex = -1
        episodeQueue = emptyList()
        episodeIndex = -1
    }
}

