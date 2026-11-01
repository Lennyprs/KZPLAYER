package com.kzplayer.app

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

// Barre de navigation laterale NewTivi (logo en haut).
// Comportement en 2 temps a la telecommande :
//  - 1er appui GAUCHE (depuis le contenu) : le focus arrive sur la barre = ICONES seules.
//  - 2e appui GAUCHE (deja sur la barre)  : la barre s'agrandit et affiche les NOMS
//    (TV / Guide / Films / Series / Recherche / Reglages).
//  - Quand le focus repart vers le contenu, la barre se replie automatiquement.
object NavHelper {
    private data class NavItem(val container: Int, val img: Int, val label: Int, val tag: String)

    private val ITEMS = listOf(
        NavItem(R.id.navTv, R.id.navTvImg, R.id.navTvLabel, "tv"),
        NavItem(R.id.navGuide, R.id.navGuideImg, R.id.navGuideLabel, "guide"),
        NavItem(R.id.navMovies, R.id.navMoviesImg, R.id.navMoviesLabel, "movies"),
        NavItem(R.id.navSeries, R.id.navSeriesImg, R.id.navSeriesLabel, "series"),
        NavItem(R.id.navReplay, R.id.navReplayImg, R.id.navReplayLabel, "replay"),
        NavItem(R.id.navSearch, R.id.navSearchImg, R.id.navSearchLabel, "search"),
        NavItem(R.id.navMic, R.id.navMicImg, R.id.navMicLabel, "mic"),
        NavItem(R.id.navPlaylist, R.id.navPlaylistImg, R.id.navPlaylistLabel, "playlist"),
        NavItem(R.id.navSettings, R.id.navSettingsImg, R.id.navSettingsLabel, "settings")
    )

    fun setup(act: AppCompatActivity, active: String) {
        wire(act, R.id.navTv, R.id.navTvImg, active == "tv") { go(act, NewLiveActivity::class.java, active, "tv") }
        wire(act, R.id.navGuide, R.id.navGuideImg, active == "guide") { go(act, NewGuideActivity::class.java, active, "guide") }
        wire(act, R.id.navMovies, R.id.navMoviesImg, active == "movies") { go(act, NewMoviesActivity::class.java, active, "movies") }
        wire(act, R.id.navSeries, R.id.navSeriesImg, active == "series") { go(act, NewSeriesActivity::class.java, active, "series") }
        // v361 : Replay -> page replay directe (plusieurs jours en arriere).
        wire(act, R.id.navReplay, R.id.navReplayImg, active == "replay") {
            act.startActivity(Intent(act, ReplayHubActivity::class.java))
        }
        wire(act, R.id.navSearch, R.id.navSearchImg, false) {
            val field=act.findViewById<EditText?>(R.id.searchEt)
            if(field!=null) field.requestFocus()
            else if(active=="tv" || active=="guide") LiveSearch.open(act)
        }
        wire(act, R.id.navMic, R.id.navMicImg, false) {
            act.startActivity(Intent(act, VoiceActivity::class.java))
        }
        wire(act, R.id.navPlaylist, R.id.navPlaylistImg, false) {
            act.startActivity(Intent(act, PlaylistSettingsActivity::class.java))
        }
        wire(act, R.id.navSettings, R.id.navSettingsImg, false) {
            act.startActivity(Intent(act, SettingsActivity::class.java))
        }
        setupRail(act, active)
    }

    private fun setupRail(act: AppCompatActivity, active: String) {
        val rail = act.findViewById<View?>(R.id.ntSidebar) ?: return
        val d = act.resources.displayMetrics.density
        val collapsedW = (88 * d).toInt()
        val expandedW = (206 * d).toInt()
        val handler = Handler(Looper.getMainLooper())

        // Couleur des noms : blanc pour l'onglet actif, gris pour les autres.
        for (it in ITEMS) {
            act.findViewById<TextView?>(it.label)?.setTextColor(
                ContextCompat.getColor(act, if (it.tag == active) R.color.text else R.color.muted)
            )
        }

        var isExpanded = false
        fun apply(exp: Boolean) {
            isExpanded = exp
            val lp = rail.layoutParams ?: return
            lp.width = if (exp) expandedW else collapsedW
            rail.layoutParams = lp
            for (it in ITEMS) act.findViewById<TextView?>(it.label)?.visibility =
                if (exp) View.VISIBLE else View.GONE
        }
        apply(false)

        // Replie la barre des que le focus quitte tous les boutons.
        val fl = View.OnFocusChangeListener { _, _ ->
            handler.post {
                val any = ITEMS.any { act.findViewById<View?>(it.container)?.hasFocus() == true }
                if (!any && isExpanded) apply(false)
            }
        }
        // 2e appui GAUCHE (deja sur la barre) => on affiche les noms.
        val kl = View.OnKeyListener { _, keyCode, ev ->
            if (ev.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT && !isExpanded) {
                apply(true); true
            } else false
        }
        for (it in ITEMS) {
            val c = act.findViewById<View?>(it.container) ?: continue
            c.onFocusChangeListener = fl
            c.setOnKeyListener(kl)
        }
    }

    private fun wire(act: AppCompatActivity, containerId: Int, imgId: Int, activeState: Boolean, onClick: () -> Unit) {
        val c = act.findViewById<View?>(containerId) ?: return
        c.isSelected = activeState
        c.setOnClickListener { onClick() }
        val iv = act.findViewById<ImageView?>(imgId)
        iv?.setColorFilter(ContextCompat.getColor(act, if (activeState) R.color.text else R.color.muted))
    }

    private fun go(act: AppCompatActivity, cls: Class<*>, active: String, tag: String) {
        if (active == tag) return
        act.startActivity(
            Intent(act, cls)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }
}
