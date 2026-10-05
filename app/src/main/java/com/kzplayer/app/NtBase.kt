package com.kzplayer.app

import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

// Base commune aux ecrans NewTivi : garantit que la Session (licence + serveurs) est prete.
// Si l'appli a ete relancee (process tue), on recharge licence + serveurs avant d'afficher.
abstract class NtBase : BaseActivity() {
    protected fun ensureSession(onReady: () -> Unit) {
        if (Session.current == null) SessionCache.restore(this)
        if (Session.current != null) { onReady(); return }
        val existing = Session.playlists.firstOrNull()
        if (existing != null) { Session.current = existing; onReady(); return }
        lifecycleScope.launch {
            try {
                val res = Api.checkLicense(
                    DeviceIdentity.stableId(this@NtBase),
                    DeviceIdentity.licenseCode(this@NtBase),
                    Build.MODEL ?: "Android TV", "1.0"
                )
                if (res.ok && res.active) {
                    LicenseGuard.rememberOk(this@NtBase, res.expiration)
                    Session.playlists = LocalPlaylists.merge(res.playlists)
                    Session.expiration = res.expiration
                    Session.current = Session.playlists.firstOrNull()
                    SessionCache.save(this@NtBase)
                }
            } catch (e: Exception) {}
            onReady()
        }
    }

    // Retour NewTivi : si le focus n'est pas deja sur la barre laterale, on l'y ramene
    // (categories TV / Films / Series...) au lieu de quitter l'ecran. Un 2e retour quitte.
    override fun onBackPressed() {
        val sidebar = findViewById<android.view.View?>(R.id.ntSidebar)
        val focused = currentFocus
        if (sidebar != null && !isInsideSidebar(focused, sidebar)) {
            if (sidebar.requestFocus()) return
        }
        super.onBackPressed()
    }

    private fun isInsideSidebar(view: android.view.View?, sidebar: android.view.View): Boolean {
        var p: android.view.ViewParent? = view?.parent
        while (p != null) {
            if (p === sidebar) return true
            p = p.parent
        }
        return false
    }
}
