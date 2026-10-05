package com.kzplayer.app

import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class PlaylistActivity : BaseActivity() {
    private fun deviceCode(): String = DeviceIdentity.licenseCode(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playlist)
        findViewById<TextView>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<TextView>(R.id.licenseCodeTv).text = "Licence : ${deviceCode()}"
        val container = findViewById<LinearLayout>(R.id.listContainer)
        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()
        val mb = (12 * d).toInt()

        // v391 : ajout manuel d une liste de lecture.
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
        addBtn.isClickable = true
        addBtn.isFocusable = true
        addBtn.setOnClickListener {
            startActivity(android.content.Intent(this, AddPlaylistActivity::class.java))
        }
        container.addView(addBtn)
        FocusFx.apply(addBtn)

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
            row.isClickable = true
            row.isFocusable = true

            val name = TextView(this)
            name.text = pl.nom + if (pl === Session.current) "   \u25CF" else ""
            name.setTextColor(ContextCompat.getColor(this, R.color.text))
            name.textSize = 17f
            row.addView(name)

            val sub = TextView(this)
            sub.text = pl.type.uppercase()
            sub.setTextColor(ContextCompat.getColor(this, R.color.muted))
            sub.textSize = 12f
            row.addView(sub)


            // v391 : etat de la liste.
            val healthTxt = PlaylistHealth.label(this, pl.id)
            if (healthTxt.isNotBlank()) {
                val stTv = TextView(this)
                stTv.text = healthTxt
                stTv.setTextColor(if (PlaylistHealth.isProblem(this, pl.id)) 0xFFFF6B6B.toInt() else 0xFF4CD07D.toInt())
                stTv.textSize = 12f
                row.addView(stTv)
            }
            FocusFx.apply(row)

            row.setOnClickListener {
                Session.current = pl
                SessionCache.save(this)
                finish()
            }
            container.addView(row)
        }
    }
}
