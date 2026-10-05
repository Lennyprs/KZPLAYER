package com.kzplayer.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// v416 : synchronise les categories avec le panel quel que soit le theme, le mode
// simple/multiliste et le type de serveur. L appel reste best-effort et ne bloque jamais l UI.
object CategorySync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val uploadMutex = Mutex()
    private val recent = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun report(ctx: Context, pl: Playlist, kind: String, categories: List<Category>) {
        val names = categories.asSequence()
            .filter { !it.id.startsWith("__") }
            .map { it.name.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .take(400)
            .toList()
        if (names.isEmpty() || pl.id.isBlank()) return
        PlaylistHealth.set(ctx, pl.id, PlaylistHealth.OK, "")
        val realKind = if (kind == "replay") "live" else kind
        val signature = pl.id + "|" + realKind + "|" + names.joinToString("").hashCode()
        val now = System.currentTimeMillis()
        val previous = recent[signature] ?: 0L
        if (now - previous < 5L * 60L * 1000L) return
        recent[signature] = now
        val app = ctx.applicationContext
        scope.launch {
            // Serialize panel uploads, never touch the provider session or Session.current.
            uploadMutex.withLock {
                try {
                    val lic = DeviceIdentity.licenseCode(app)
                    Api.reportCategories(lic, pl.id, realKind, names)
                    Api.reportPlaylistStatus(lic, pl.id, PlaylistHealth.OK,
                        "Catalogue accessible au dernier chargement")
                } catch (_: Exception) { recent.remove(signature) }
            }
        }
    }
}
