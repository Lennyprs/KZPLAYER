package com.kzplayer.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

// Sous-menu Parametres : choix de la liste de lecture (serveur actif). Marche sur les 2 themes.
class PlaylistSettingsActivity : BaseActivity() {

    // v393 : signature de la liste actuellement affichee (ids + id de la liste active).
    // Sert a eviter les renderPlaylists() inutiles dans onResume() qui remontent le focus.
    private var renderedSignature: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playlist_settings)
        findViewById<View>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<TextView>(R.id.licenseTv).text = DeviceIdentity.licenseCode(this)

        renderPlaylists()
        checkHealth()
        if (Session.playlists.isEmpty()) {
            lifecycleScope.launch {
                try {
                    val res = Api.checkLicense(
                        DeviceIdentity.stableId(this@PlaylistSettingsActivity),
                        DeviceIdentity.licenseCode(this@PlaylistSettingsActivity),
                        Build.MODEL ?: "Android TV", "1.0"
                    )
                    if (res.ok && res.active) {
                        Session.playlists = LocalPlaylists.merge(res.playlists)
                        Session.expiration = res.expiration
                        if (Session.current == null) Session.current = res.playlists.firstOrNull()
                    }
                } catch (e: Exception) {}
                renderPlaylists()
            }
        }
    }

    // v393 : effet de focus DOUX pour les lignes pleine largeur.
    // Le scale 1.07 du FocusFx generique fait sortir le texte de l ecran ("coupe")
    // sur des lignes MATCH_PARENT. On utilise 1.02 + un leger relief translationZ.
    private fun applySoftFocus(v: View) {
        v.isFocusable = true
        v.isClickable = true
        v.setOnFocusChangeListener { view, hasFocus ->
            view.animate()
                .scaleX(if (hasFocus) 1.02f else 1f)
                .scaleY(if (hasFocus) 1.02f else 1f)
                .setDuration(90).start()
            view.translationZ = if (hasFocus) 12f else 0f
        }
    }

    // v393 : signature stable de l etat visible pour eviter les rebuild inutiles.
    private fun computeSignature(): String {
        val cur = Session.current?.id ?: ""
        val ids = Session.playlists.joinToString("|") {
            it.id + ":" + PlaylistHealth.label(this, it.id)
        }
        return "cur=$cur;" + ids
    }

    // Construit la liste cliquable des serveurs. La liste active est marquee d'un point.
    private fun renderPlaylists() {
        val container = findViewById<LinearLayout>(R.id.playlistContainer) ?: return

        // v393 : memorise le tag de la vue actuellement focalisee avant le rebuild,
        // pour restaurer le focus au bon endroit apres removeAllViews().
        val prevFocusTag = (currentFocus?.tag as? String)

        container.removeAllViews()
        // v393 : indispensable pour que le scale de focus deborde sans etre coupe.
        container.clipChildren = false
        container.clipToPadding = false
        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()
        val mb = (10 * d).toInt()

        // v391 : ajout manuel d une liste de lecture depuis l application.
        val addBtn = TextView(this)
        addBtn.text = "+   Ajouter une liste de lecture"
        addBtn.setTextColor(ContextCompat.getColor(this, R.color.text))
        addBtn.textSize = 16f
        addBtn.setPadding(pad, pad, pad, pad)
        addBtn.background = ContextCompat.getDrawable(this, R.drawable.bg_ghost_btn)
        val alp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        alp.bottomMargin = mb
        addBtn.layoutParams = alp
        addBtn.tag = "__add__"
        addBtn.setOnClickListener { startActivity(Intent(this, AddPlaylistActivity::class.java)) }
        container.addView(addBtn)
        // v393 : scale reduit pour ne PAS couper le texte du bouton.
        applySoftFocus(addBtn)

        if (Session.playlists.isEmpty()) {
            val tv = TextView(this)
            tv.text = "Aucune liste de lecture disponible."
            tv.setTextColor(ContextCompat.getColor(this, R.color.muted))
            tv.textSize = 14f
            container.addView(tv)
            renderedSignature = computeSignature()
            return
        }
        for (pl in Session.playlists) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.VERTICAL
            row.setPadding(pad, pad, pad, pad)
            row.background = ContextCompat.getDrawable(this, R.drawable.bg_tile)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = mb
            row.layoutParams = lp
            // v393 : tag stable pour retrouver la ligne apres rebuild.
            row.tag = "pl:" + pl.id

            val name = TextView(this)
            name.text = pl.nom + if (pl.id == Session.current?.id) "   \u25CF" else ""
            name.setTextColor(ContextCompat.getColor(this, R.color.text))
            name.textSize = 17f
            row.addView(name)

            val sub = TextView(this)
            sub.text = pl.type.uppercase()
            sub.setTextColor(ContextCompat.getColor(this, R.color.muted))
            sub.textSize = 12f
            row.addView(sub)


            // v391 : etat de la liste (active / expiree / ne repond plus).
            val healthTxt = PlaylistHealth.label(this, pl.id)
            if (healthTxt.isNotBlank()) {
                val stTv = TextView(this)
                // v393b : tag OBLIGATOIRE pour que updateHealthLabelInPlace() retrouve
                // ce TextView et le MODIFIE au lieu d en creer un 2e (doublon).
                stTv.tag = "__health__"
                stTv.text = healthTxt
                stTv.setTextColor(if (PlaylistHealth.isProblem(this, pl.id)) 0xFFFF6B6B.toInt() else 0xFF4CD07D.toInt())
                stTv.textSize = 12f
                row.addView(stTv)
            }
            if (LocalPlaylists.isLocal(pl.id)) {
                val locTv = TextView(this)
                locTv.text = "Ajout\u00e9e depuis l application \u2022 appui long pour supprimer"
                locTv.setTextColor(ContextCompat.getColor(this, R.color.muted))
                locTv.textSize = 11f
                row.addView(locTv)
                row.setOnLongClickListener {
                    LocalPlaylists.remove(this, pl.id)
                    Session.playlists = Session.playlists.filter { it.id != pl.id }
                    if (Session.current?.id == pl.id) Session.current = Session.playlists.firstOrNull()
                    Toast.makeText(this, "Liste supprim\u00e9e", Toast.LENGTH_SHORT).show()
                    // v393 : ici on doit reellement rebuild puisque la liste a change.
                    renderedSignature = ""
                    renderPlaylists()
                    true
                }
            }
            // v393 : scale reduit pour ne PAS couper le nom des listes.
            applySoftFocus(row)

            row.setOnClickListener { selectPlaylist(pl) }
            container.addView(row)
        }

        renderedSignature = computeSignature()

        // v393 : restauration du focus apres rebuild : evite que le curseur remonte
        // sur le bouton "Ajouter" quand un checkHealth() se termine pendant que
        // l utilisateur navigue plus bas dans la liste.
        if (!prevFocusTag.isNullOrBlank()) {
            container.post {
                for (i in 0 until container.childCount) {
                    val v = container.getChildAt(i)
                    if (v.tag == prevFocusTag) { v.requestFocus(); break }
                }
            }
        }
    }

    // Change la liste active puis relance l'accueil du theme courant pour recharger
    // tout le contenu (chaines, films, series) avec le nouveau serveur.
    private fun selectPlaylist(pl: Playlist) {
        if (pl.id == Session.current?.id) {
            Toast.makeText(this, "Liste d\u00e9j\u00e0 active : ${pl.nom}", Toast.LENGTH_SHORT).show()
            return
        }
        Session.current = pl
        Toast.makeText(this, "Liste active : ${pl.nom}", Toast.LENGTH_SHORT).show()
        val cls = ThemePref.homeClass(this)
        startActivity(Intent(this, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    override fun onResume() {
        super.onResume()
        // v393 : ne rebuild QUE si l etat visible a change (listes ajoutees/supprimees
        // ou label sante different). Evite que le retour depuis AddPlaylistActivity
        // remette le focus en haut.
        if (renderedSignature != computeSignature()) renderPlaylists()
    }

    // v391 : verifie chaque liste (expiree / hors service), le signale dans l app
    // et l envoie au panel utilisateur.
    // v393 : NE rebuild PLUS toute la vue apres CHAQUE health check (c est ce qui
    // ramenait le curseur sur le bouton "Ajouter" quand on descendait). A la place,
    // on met a jour uniquement le label d etat de la ligne concernee.
    private fun checkHealth() {
        val lic = DeviceIdentity.licenseCode(this)
        for (pl in Session.playlists.toList()) {
            lifecycleScope.launch {
                val res = try { Api.playlistHealth(pl) } catch (e: Exception) { Pair(PlaylistHealth.DOWN, "") }
                PlaylistHealth.set(this@PlaylistSettingsActivity, pl.id, res.first, res.second)
                if (res.first != PlaylistHealth.OK) {
                    try { Api.reportPlaylistStatus(lic, pl.id, res.first, res.second) } catch (e: Exception) {}
                    if (pl.id == Session.current?.id) {
                        Toast.makeText(
                            this@PlaylistSettingsActivity,
                            "Attention : " + PlaylistHealth.label(this@PlaylistSettingsActivity, pl.id),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                // v393 : met a jour SEULEMENT le label d etat de la ligne concernee
                // pour ne pas reconstruire toute la vue (et ne pas voler le focus).
                updateHealthLabelInPlace(pl.id)
            }
        }
    }

    // v393 : ajoute / met a jour le petit label "Actif / Hors service / ..." dans la
    // ligne d une playlist, sans rebuild global. Silencieux si la ligne n existe pas.
    private fun updateHealthLabelInPlace(playlistId: String) {
        val container = findViewById<LinearLayout>(R.id.playlistContainer) ?: return
        val tag = "pl:$playlistId"
        for (i in 0 until container.childCount) {
            val v = container.getChildAt(i)
            if (v.tag != tag || v !is LinearLayout) continue
            val healthTxt = PlaylistHealth.label(this, playlistId)
            val problem = PlaylistHealth.isProblem(this, playlistId)
            // On cherche un TextView de sante existant (3e enfant si present : name/sub/health).
            var healthTv: TextView? = null
            for (j in 0 until v.childCount) {
                val c = v.getChildAt(j)
                if (c is TextView && c.tag == "__health__") { healthTv = c; break }
            }
            if (healthTxt.isBlank()) {
                if (healthTv != null) v.removeView(healthTv)
            } else {
                if (healthTv == null) {
                    healthTv = TextView(this)
                    healthTv.tag = "__health__"
                    healthTv.textSize = 12f
                    // Insere apres name+sub (index 2), avant l eventuel label "local".
                    val insertAt = if (v.childCount >= 2) 2 else v.childCount
                    v.addView(healthTv, insertAt)
                }
                healthTv.text = healthTxt
                healthTv.setTextColor(if (problem) 0xFFFF6B6B.toInt() else 0xFF4CD07D.toInt())
            }
            // Rafraichit aussi la signature memorisee pour eviter un rebuild inutile en onResume.
            renderedSignature = computeSignature()
            break
        }
    }
}
