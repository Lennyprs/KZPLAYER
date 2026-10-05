package com.kzplayer.app

import android.content.Context

// v416 : etat FIABLE des listes. Une panne reseau ou une reponse vide devient UNKNOWN,
// jamais "expiree". EXPIRED/INACTIVE ne sont utilises que si le fournisseur le confirme.
object PlaylistHealth {
    const val OK = "ok"
    const val EXPIRED = "expired"
    const val INACTIVE = "inactive"
    const val DOWN = "down" // compat anciennes donnees ; affiche comme statut non confirme
    const val UNKNOWN = "unknown"
    private const val PREFS = "kz_pl_health"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Invalide une seule fois les anciens diagnostics negatifs non confirmes.
    fun resetLegacy(ctx: Context) {
        val p = prefs(ctx)
        if (p.getBoolean("observation_v417", false)) return
        val e = p.edit()
        for ((key, value) in p.all) {
            if (key.startsWith("s_") && value != OK) {
                val id = key.removePrefix("s_")
                e.remove(key).remove("m_" + id).remove("t_" + id)
            }
        }
        e.putBoolean("observation_v417", true).apply()
    }

    fun set(ctx: Context, id: String, status: String, message: String) {
        val safe = when (status) {
            OK, EXPIRED, INACTIVE, UNKNOWN -> status
            else -> UNKNOWN
        }
        try {
            if (safe == UNKNOWN && PlaylistHealth.status(ctx, id) == OK) return
            prefs(ctx).edit()
                .putString("s_" + id, safe)
                .putString("m_" + id, message)
                .putLong("t_" + id, System.currentTimeMillis())
                .apply()
        } catch (_: Throwable) {}
    }

    fun status(ctx: Context, id: String): String =
        try { prefs(ctx).getString("s_" + id, "").orEmpty() } catch (_: Throwable) { "" }

    fun message(ctx: Context, id: String): String =
        try { prefs(ctx).getString("m_" + id, "").orEmpty() } catch (_: Throwable) { "" }

    fun isProblem(ctx: Context, id: String): Boolean {
        val s = status(ctx, id)
        return s == EXPIRED || s == INACTIVE
    }

    fun label(ctx: Context, id: String): String {
        val msg = message(ctx, id)
        val suffix = if (msg.isBlank()) "" else "  •  " + msg
        return when (status(ctx, id)) {
            OK -> "Catalogue accessible au dernier chargement" + suffix
            EXPIRED -> "Liste expirée" + suffix
            INACTIVE -> "Liste inactive" + suffix
            UNKNOWN, DOWN -> "Vérification en attente — accès à la liste autorisé"
            else -> ""
        }
    }
}
