package com.kzplayer.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object AutoReloader {
    private const val PREF = "kz_auto_reload"
    private const val KEY_LAST = "last_reload_ms"
    private const val TWELVE_HOURS = 12L * 60L * 60L * 1000L

    fun runIfNeeded(ctx: Context, force: Boolean = false, onDone: ((Boolean) -> Unit)? = null) {
        val app = ctx.applicationContext
        val prefs = app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST, 0L)
        if (!force && last > 0L && now - last < TWELVE_HOURS) { onDone?.invoke(false); return }
        CoroutineScope(Dispatchers.IO).launch {
            var ok = false
            try {
                val res = Api.checkLicense(DeviceIdentity.stableId(app), DeviceIdentity.licenseCode(app), android.os.Build.MODEL ?: "Android TV", "3.2.0")
                if (res.ok && res.active) {
                    LicenseGuard.rememberOk(app, res.expiration)
                    Session.playlists = LocalPlaylists.merge(res.playlists)
                    Session.expiration = res.expiration
                    val selectedId = Session.current?.id
                    Session.current = Session.playlists.firstOrNull { it.id == selectedId }
                        ?: Session.playlists.firstOrNull()
                    SessionCache.save(app)
                    prefs.edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
                    ok = true
                }
            } catch (_: Exception) {}
            onDone?.invoke(ok)
        }
    }
}
