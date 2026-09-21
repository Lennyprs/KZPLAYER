package com.kzplayer.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

// v369 : ECRAN TELECHARGEMENTS.
// En haut : les telechargements EN COURS avec le pourcentage, la taille deja
// recue et un bouton Annuler (rafraichi tout seul toutes les 1,5 s).
// En dessous : les titres TERMINES, lisibles hors ligne, avec un bouton Supprimer.
class DownloadsActivity : BaseActivity() {

    private lateinit var rv: RecyclerView
    private lateinit var msgTv: TextView
    private lateinit var subTv: TextView
    private var files: List<File> = emptyList()
    private var tasks: List<Downloads.Task> = emptyList()
    private val ui = Handler(Looper.getMainLooper())
    private var ticking = false

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            if (tasks.isNotEmpty()) ui.postDelayed(this, 1500L) else ticking = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)
        findViewById<View>(R.id.backBtn).setOnClickListener { finish() }
        msgTv = findViewById(R.id.msgTv)
        subTv = findViewById(R.id.subTv)
        rv = findViewById(R.id.dlRv)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = DlAdapter()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        if (!ticking && tasks.isNotEmpty()) { ticking = true; ui.postDelayed(tick, 1500L) }
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
        ticking = false
    }

    private fun refresh() {
        // v372 : un telechargement en echec (jeton du serveur expire, coupure reseau)
        // est relance tout seul avec un lien frais, jusqu a 3 essais par titre.
        try { Downloads.autoRetryFailed(this) } catch (e: Exception) {}
        tasks = Downloads.pending(this)
        // Un fichier encore en telechargement ne doit pas apparaitre comme termine.
        val busy = tasks.map { it.fileName }.toSet()
        files = Downloads.list(this).filter { !busy.contains(it.name) }
        rv.adapter?.notifyDataSetChanged()
        if (files.isEmpty() && tasks.isEmpty()) {
            msgTv.visibility = View.VISIBLE
            msgTv.text = "Aucun t\u00e9l\u00e9chargement pour le moment. " +
                "Utilise le bouton T\u00e9l\u00e9charger sur un film, un \u00e9pisode ou un replay."
        } else {
            msgTv.visibility = View.GONE
        }
        val n = files.size
        val head = if (n == 0) "Aucun titre pr\u00eat"
            else n.toString() + (if (n > 1) " titres pr\u00eats" else " titre pr\u00eat") +
                "  \u2022  " + Downloads.human(files.sumOf { it.length() })
        subTv.text = if (tasks.isEmpty()) head + "  \u2022  hors ligne"
            else head + "  \u2022  " + tasks.size.toString() + " en cours"
        if (tasks.isNotEmpty() && !ticking) { ticking = true; ui.postDelayed(tick, 1500L) }
    }

    private fun label(f: File): String {
        val n = f.name
        val dot = n.lastIndexOf(46.toChar())
        return if (dot > 0) n.substring(0, dot) else n
    }

    private fun play(f: File) {
        if (!f.exists() || f.length() < 512L * 1024L) {
            Toast.makeText(
                this,
                "Ce titre n est pas encore t\u00e9l\u00e9charg\u00e9 en entier. " +
                    "Attends la fin du t\u00e9l\u00e9chargement.",
                Toast.LENGTH_LONG
            ).show()
            refresh()
            return
        }
        val i = Intent(this, PlayerActivity::class.java)
        i.putExtra("title", label(f))
        i.putExtra("url", Uri.fromFile(f).toString())
        i.putExtra("mode", "vod")
        i.putExtra("historyKind", "movie")
        startActivity(i)
    }

    private fun confirmDelete(f: File) {
        AlertDialog.Builder(this)
            .setTitle("Supprimer")
            .setMessage(
                label(f) + NLQ + "Ce fichier sera supprim\u00e9 de l appareil et son " +
                    "t\u00e9l\u00e9chargement annul\u00e9 s il n est pas termin\u00e9."
            )
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Supprimer") { _, _ ->
                // v370 : annule aussi le telechargement pour que le fichier ne revienne pas.
                Downloads.removeAll(this, f)
                refresh()
            }
            .show()
    }

    private fun confirmCancel(t: Downloads.Task) {
        AlertDialog.Builder(this)
            .setTitle("Annuler le t\u00e9l\u00e9chargement")
            .setMessage(t.name + NLQ + "Le fichier en cours sera supprim\u00e9.")
            .setNegativeButton("Continuer", null)
            .setPositiveButton("Annuler le t\u00e9l\u00e9chargement") { _, _ ->
                Downloads.cancelTask(this, t)
                refresh()
            }
            .show()
    }

    private inner class DlAdapter : RecyclerView.Adapter<DlVh>() {
        override fun getItemCount(): Int = tasks.size + files.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DlVh {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_download, parent, false)
            return DlVh(v)
        }
        override fun onBindViewHolder(h: DlVh, position: Int) {
            if (position < tasks.size) {
                val t = tasks[position]
                h.nameTv.text = t.name
                h.infoTv.text = Downloads.statusText(t)
                h.progBar.visibility = View.VISIBLE
                h.progBar.isIndeterminate = t.total <= 0L
                if (t.total > 0L) h.progBar.progress = Downloads.percent(t)
                val echec = t.status == android.app.DownloadManager.STATUS_FAILED
                h.playBtn.text = if (echec) "Relancer" else "En cours"
                h.playBtn.setOnClickListener {
                    if (echec) {
                        // v372 : relance manuelle avec un lien tout neuf.
                        Downloads.retryTask(this@DownloadsActivity, t, notify = true)
                        ui.postDelayed({ refresh() }, 1500L)
                    } else {
                        Toast.makeText(
                            this@DownloadsActivity,
                            "T\u00e9l\u00e9chargement en cours : " + Downloads.percent(t).toString() + " %",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                h.playRow.setOnClickListener { }
                h.delBtn.text = "Annuler"
                h.delBtn.setOnClickListener { confirmCancel(t) }
                return
            }
            val f = files[position - tasks.size]
            h.nameTv.text = label(f)
            h.infoTv.text = Downloads.human(f.length()) + "  \u2022  " +
                java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.FRANCE)
                    .format(java.util.Date(f.lastModified()))
            h.progBar.visibility = View.GONE
            h.playBtn.text = "Lire"
            h.playRow.setOnClickListener { play(f) }
            h.playBtn.setOnClickListener { play(f) }
            h.delBtn.text = "Supprimer"
            h.delBtn.setOnClickListener { confirmDelete(f) }
        }
    }

    private inner class DlVh(v: View) : RecyclerView.ViewHolder(v) {
        val nameTv: TextView = v.findViewById(R.id.nameTv)
        val infoTv: TextView = v.findViewById(R.id.infoTv)
        val progBar: ProgressBar = v.findViewById(R.id.progBar)
        val playRow: View = v.findViewById(R.id.playRow)
        val playBtn: TextView = v.findViewById(R.id.playBtn)
        val delBtn: TextView = v.findViewById(R.id.delBtn)
    }

    companion object {
        private val NLQ: String = System.lineSeparator() + System.lineSeparator()
    }
}
