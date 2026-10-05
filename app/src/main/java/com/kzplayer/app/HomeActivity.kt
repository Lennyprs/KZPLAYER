package com.kzplayer.app

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class HomeActivity : BaseActivity() {
    private lateinit var moviesCard: View
    private lateinit var seriesCard: View
    private lateinit var expTv: TextView

    private fun deviceId(): String = DeviceIdentity.stableId(this)

    private fun deviceCode(): String = DeviceIdentity.licenseCode(this)

    private fun reloadPlaylists() {
        expTv.text = "Rechargement..."
        lifecycleScope.launch {
            try {
                val res = Api.checkLicense(deviceId(), deviceCode(), android.os.Build.MODEL ?: "Android TV", "1.0")
                if (res.ok && res.active) {
                    LicenseGuard.rememberOk(this@HomeActivity, res.expiration)
                    Session.playlists = LocalPlaylists.merge(res.playlists)
                    Session.expiration = res.expiration
                    val selectedId = Session.current?.id
                    Session.current = Session.playlists.firstOrNull { it.id == selectedId }
                        ?: Session.playlists.firstOrNull()
                    SessionCache.save(this@HomeActivity)
                    expTv.text = Session.expiration?.let { "Expiration : $it" } ?: "Licence illimit\u00e9e"
                } else if (LicenseGuard.wasRecentlyActive(this@HomeActivity)) {
                    // Fenetre de grace : on ne montre pas "Licence inactive" a tort quand le
                    // backend a juste hoquete. La derniere expiration connue reste affichee.
                    val exp = LicenseGuard.lastExpiration(this@HomeActivity) ?: Session.expiration
                    expTv.text = exp?.let { "Expiration : $it" } ?: "Licence active"
                } else {
                    expTv.text = res.message.ifBlank { "Licence inactive" }
                }
            } catch (e: Exception) {
                expTv.text = "Erreur rechargement"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        val liveCard = findViewById<View>(R.id.liveCard)
        moviesCard = findViewById(R.id.moviesCard)
        seriesCard = findViewById(R.id.seriesCard)
        expTv = findViewById(R.id.expTv)

        if (Session.current == null && Session.playlists.isNotEmpty()) {
            Session.current = Session.playlists[0]
        }
        refreshCards()
        expTv.text = Session.expiration?.let { "Expiration : $it" } ?: ""
        // Auto-reparation : si l'appli a ete relancee (process tue), Session est vide.
        // On recharge alors licence + serveurs pour que les boutons refonctionnent.
        if (Session.playlists.isEmpty()) reloadPlaylists()

        liveCard.setOnClickListener { open("live", "TV") }
        moviesCard.setOnClickListener { open("movie", "Films") }
        seriesCard.setOnClickListener { open("series", "Series") }
        findViewById<View>(R.id.catchupCard).visibility = View.GONE
        findViewById<View>(R.id.favorisCard).setOnClickListener { open("favorites", "Favoris") }
        findViewById<View>(R.id.reloadCard).setOnClickListener { reloadPlaylists() }
        // v361 : bouton Replay -> page replay directe (plusieurs jours en arriere).
        findViewById<View>(R.id.replayCard).setOnClickListener {
            startActivity(Intent(this, ReplayHubActivity::class.java))
        }
        findViewById<View>(R.id.settingsCard).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        // Bouton micro (commande vocale) : present dans le layout mais non branche -> on le rebranche.
        val playlistBtn = findViewById<View>(R.id.playlistBtn)
        playlistBtn.setOnClickListener { startActivity(Intent(this, PlaylistSettingsActivity::class.java)) }
        val micBtn = findViewById<View>(R.id.micBtn)
        micBtn.setOnClickListener { startActivity(Intent(this, VoiceActivity::class.java)) }

        // ---------- Adaptation boitier / TV Android ----------
        applyTvOverscan()
        val focusables = listOf<View>(
            playlistBtn, micBtn, liveCard, moviesCard, seriesCard,
            findViewById(R.id.favorisCard),
            findViewById(R.id.reloadCard), findViewById(R.id.replayCard),
            findViewById(R.id.settingsCard), findViewById(R.id.subCard)
        )
        focusables.forEach { addFocusBounce(it) }
        // Donne le focus a la premiere carte des l'ouverture (utile a la telecommande)
        liveCard.requestFocus()
    }

    private fun isTvDevice(): Boolean {
        val ui = (getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType
        if (ui == Configuration.UI_MODE_TYPE_TELEVISION) return true
        return !packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN) ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    /** Ajoute une marge de securite (overscan) sur les bords et agrandit les cartes sur TV. */
    private fun applyTvOverscan() {
        if (!isTvDevice()) return
        val dm = resources.displayMetrics
        val root = findViewById<View>(R.id.homeRoot)
        val padH = (dm.widthPixels * 0.045f).toInt()
        val padV = (dm.heightPixels * 0.045f).toInt()
        root.setPadding(padH, padV, padH, padV)
        // Les cartes remplissent l'espace disponible via leur poids (layout_weight),
        // donc pas besoin de forcer une hauteur ici.
    }

    /** Effet visuel de focus a la telecommande : la carte selectionnee grossit et passe au premier plan. */
    /**
     * Effet de focus propre a la telecommande : la carte ciblee passe au premier plan,
     * se souleve (ombre) et grossit legerement. Pas de contour blanc.
     */
    private fun addFocusBounce(v: View) {
        v.setOnFocusChangeListener { view, hasFocus ->
            val scale = if (hasFocus) 1.035f else 1f
            // NE PAS appeler bringToFront() : dans une LinearLayout cela reordonne les enfants
            // et fait "sauter"/interchanger les cartes. La mise en avant est geree par l'elevation.
            view.animate()
                .scaleX(scale).scaleY(scale)
                .translationZ(if (hasFocus) 16f else 0f)
                .setDuration(120).start()
            view.elevation = if (hasFocus) 28f else 0f
        }
    }

    override fun onResume() {
        super.onResume()
        refreshCards()
        expTv.text = Session.expiration?.let { "Expiration : $it" } ?: ""
    }

    private fun refreshCards() {
        // Films & Series disponibles pour tous les types : xtream, stalker et m3u (classe).
        moviesCard.visibility = View.VISIBLE
        seriesCard.visibility = View.VISIBLE
    }

    private fun open(kind: String, title: String) {
        if (Session.current == null) {
            // Plutot que de laisser un bouton "mort", on recharge les serveurs.
            expTv.text = "Rechargement..."
            reloadPlaylists()
            return
        }
        Session.browseTitle = title
        startActivity(Intent(this, BrowseActivity::class.java).putExtra("kind", kind))
    }
}
