package com.kzplayer.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// v416 : synchronise les categories avec le panel quel que soit le theme, le mode
// simple/multiliste et le type de serveur. L appel reste best-effort et ne bloque jamais l UI.
object CategorySync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
        val realKind = if (kind == "replay") "live" else kind
        val signature = pl.id + "|" + realKind + "|" + names.joinToString("").hashCode()
        val now = System.currentTimeMillis()
        val previous = recent[signature] ?: 0L
        if (now - previous < 5L * 60L * 1000L) return
        recent[signature] = now
        val app = ctx.applicationContext
        scope.launch {
            try { Api.reportCategories(DeviceIdentity.licenseCode(app), pl.id, realKind, names) }
            catch (_: Throwable) {}
        }
    }
}
