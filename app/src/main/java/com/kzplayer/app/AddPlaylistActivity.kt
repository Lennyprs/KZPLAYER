package com.kzplayer.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

// v391 : ajout d une liste de lecture A LA MAIN depuis l application (Xtream / M3U / Stalker).
// La liste est enregistree sur l appareil ET envoyee au panel utilisateur (best-effort),
// puis son etat (active / expiree / hors service) est verifie immediatement.
class AddPlaylistActivity : BaseActivity() {
    private var type = "xtream"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_playlist)
        findViewById<View>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<View>(R.id.typeXtream).setOnClickListener { setType("xtream") }
        findViewById<View>(R.id.typeM3u).setOnClickListener { setType("m3u") }
        findViewById<View>(R.id.typeStalker).setOnClickListener { setType("stalker") }
        findViewById<View>(R.id.saveBtn).setOnClickListener { save() }
        setType("xtream")
        FocusFx.apply(
            findViewById<View>(R.id.typeXtream), findViewById<View>(R.id.typeM3u),
            findViewById<View>(R.id.typeStalker), findViewById<View>(R.id.saveBtn)
        )
        findViewById<View>(R.id.nameEt).requestFocus()
    }

    private fun setType(t: String) {
        type = t
        findViewById<TextView>(R.id.typeXtream).isSelected = t == "xtream"
        findViewById<TextView>(R.id.typeM3u).isSelected = t == "m3u"
        findViewById<TextView>(R.id.typeStalker).isSelected = t == "stalker"
        findViewById<View>(R.id.xtreamBox).visibility = if (t == "xtream") View.VISIBLE else View.GONE
        findViewById<View>(R.id.m3uBox).visibility = if (t == "m3u") View.VISIBLE else View.GONE
        findViewById<View>(R.id.stalkerBox).visibility = if (t == "stalker") View.VISIBLE else View.GONE
    }

    private fun txt(id: Int): String =
        findViewById<EditText>(id).text?.toString()?.trim().orEmpty()

    private fun warn(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }

    private fun save() {
        val nom0 = txt(R.id.nameEt)
        val pl: Playlist
        if (type == "xtream") {
            val server = Api.cleanBase(txt(R.id.serverEt))
            val user = txt(R.id.userEt)
            val pass = txt(R.id.passEt)
            if (server.isBlank() || user.isBlank() || pass.isBlank()) {
                warn("Renseigne l adresse du serveur, l identifiant et le mot de passe."); return
            }
            pl = Playlist(
                id = LocalPlaylists.newId(), type = "xtream",
                nom = if (nom0.isBlank()) server else nom0,
                serverUrl = server, username = user, password = pass, mac = "", m3uUrl = ""
            )
        } else if (type == "m3u") {
            val m3u = txt(R.id.m3uEt)
            if (!m3u.startsWith("http", true)) { warn("Colle l adresse compl\u00e8te de la liste M3U (http...)."); return }
            pl = Playlist(
                id = LocalPlaylists.newId(), type = "m3u",
                nom = if (nom0.isBlank()) "Liste M3U" else nom0,
                serverUrl = "", username = "", password = "", mac = "", m3uUrl = m3u
            )
        } else {
            val portal = Api.cleanBase(txt(R.id.portalEt))
            val mac = txt(R.id.macEt)
            if (portal.isBlank() || mac.isBlank()) { warn("Renseigne le portail et l adresse MAC."); return }
            pl = Playlist(
                id = LocalPlaylists.newId(), type = "stalker",
                nom = if (nom0.isBlank()) portal else nom0,
                serverUrl = portal, username = "", password = "", mac = mac, m3uUrl = ""
            )
        }
        LocalPlaylists.add(this, pl)
        Session.playlists = Session.playlists + pl
        if (Session.current == null) Session.current = pl
        try { SessionCache.save(this) } catch (e: Throwable) {}
        Toast.makeText(this, "Liste ajout\u00e9e : " + pl.nom, Toast.LENGTH_SHORT).show()
        val lic = DeviceIdentity.licenseCode(this)
        lifecycleScope.launch {
            val sent = try { Api.addPlaylistRemote(lic, pl) } catch (e: Exception) { false }
            try {
                val res = Api.playlistHealth(pl)
                PlaylistHealth.set(this@AddPlaylistActivity, pl.id, res.first, res.second)
                Api.reportPlaylistStatus(lic, pl.id, res.first, res.second)
            } catch (e: Exception) {}
            val msg = if (sent) "Liste enregistr\u00e9e et envoy\u00e9e au panel" else "Liste enregistr\u00e9e sur cet appareil (panel non joignable)"
            Toast.makeText(this@AddPlaylistActivity, msg, Toast.LENGTH_LONG).show()
            finish()
        }
    }
}
