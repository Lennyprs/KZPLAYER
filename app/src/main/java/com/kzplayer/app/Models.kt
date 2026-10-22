package com.kzplayer.app

data class Playlist(
    val id: String,
    val type: String,        // xtream | m3u | stalker
    val nom: String,
    val serverUrl: String,
    val username: String,
    val password: String,
    val mac: String,
    val m3uUrl: String,
    // Champs MAG/Stalker optionnels pour les portails stricts qui exigent un SN/profil exact.
    val stalkerSn: String = "",
    val stalkerDeviceId: String = "",
    val stalkerDeviceId2: String = "",
    val stalkerSignature: String = "",
    val stalkerMetrics: String = "",
    val stalkerHwVersion2: String = "",
    val stalkerTimestamp: String = "",
    val stalkerPrehash: String = "",
    val stalkerApiSignature: String = "",
    val stalkerImageVersion: String = "",
    val stalkerVer: String = "",
    val hiddenCategories: List<String> = emptyList(),
    val shownByKind: Map<String, List<String>> = emptyMap()
)

data class Category(val id: String, val name: String)

data class Item(
    val name: String,
    val logo: String = "",
    val kind: String = "live",      // live | movie | series | channel
    val directUrl: String? = null,  // URL prete a lire (lecture directe)
    val streamId: String? = null,
    val containerExt: String? = null,
    val seriesId: String? = null,
    val cmd: String? = null,        // commande Stalker (a resoudre via create_link)
    val description: String = "",   // resume (films) / liste episodes (saisons stalker)
    val duration: String = "",      // duree du film (affichee dans la fiche)
    val added: Long = 0L,           // date d'ajout (pour le tri)
    val season: Int = 0,            // numero de saison (kind = "season")
    val summary: String = "",       // resume propre a l'episode / film quand fourni
    val catchup: Boolean = false,   // chaine disponible en replay / catch-up
    val serverLabel: String = "",   // recherche multi-serveurs : "Serveur 1 / Serveur 2"
    val ownerPlaylistId: String = "" // serveur d'origine de l'item (pour basculer dessus)
) : java.io.Serializable

data class VodMeta(val plot: String, val duration: String)

// Resultat de recherche globale : un item + le serveur (playlist) ou il a ete trouve.
data class SearchHit(val item: Item, val playlist: Playlist)


// Resultat de la verification de mise a jour (panel admin).
data class UpdateInfo(
    val ok: Boolean,
    val hasUpdate: Boolean,
    val latestVersion: String,
    val currentVersion: String,
    val downloadUrl: String,
    val notes: String,
    val message: String
)

data class EpgEntry(
    val title: String,
    val time: String = "",
    val description: String = "",
    val nowPlaying: Boolean = false,
    val startMs: Long = 0L,
    val endMs: Long = 0L
)
