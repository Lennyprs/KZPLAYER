package com.kzplayer.app

import android.content.Context

// Petit garde-fou pour eviter les faux "licence inactive" / "en attente d'activation"
// lors d'un hoquet reseau ou d'un renvoi vide du backend Apps Script (cold start,
// quotas, timeouts...). On memorise la DERNIERE verification reussie ; tant qu'elle
// est recente (< 24 h), on tolere un echec transitoire au lieu de bloquer
// l'utilisateur sur l'ecran licence.
//
// N'entame RIEN de la logique metier : si le backend confirme un jour "active=false"
// apres la fenetre de grace, l'utilisateur repart bien sur l'ecran d'attente.
object LicenseGuard {
    private const val PREFS = "kz_license"
    private const val KEY_LAST_OK_MS = "last_ok_ms"
    private const val KEY_LAST_EXPIRATION = "last_expiration"
    // Fenetre de tolerance : 24 h.
    private const val GRACE_MS = 24L * 3600L * 1000L

    fun rememberOk(ctx: Context, expiration: String?) {
        ctx.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_OK_MS, System.currentTimeMillis())
            .putString(KEY_LAST_EXPIRATION, expiration ?: "")
            .apply()
    }

    fun wasRecentlyActive(ctx: Context): Boolean {
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ms = p.getLong(KEY_LAST_OK_MS, 0L)
        return ms > 0L && System.currentTimeMillis() - ms < GRACE_MS
    }

    fun lastExpiration(ctx: Context): String? {
        val v = ctx.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_EXPIRATION, "")
        return if (v.isNullOrBlank()) null else v
    }

    fun clear(ctx: Context) {
        ctx.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    // Une réponse serveur explicite inactive/bloquée/expirée n'est jamais une panne réseau.
    // Elle révoque immédiatement la session locale et son cache.
    fun revoke(ctx: Context) {
        clear(ctx)
        SessionCache.clear(ctx)
    }
}
