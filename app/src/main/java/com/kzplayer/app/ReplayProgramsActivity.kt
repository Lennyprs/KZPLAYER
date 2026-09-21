package com.kzplayer.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

/**
 * v340 : liste des programmes DEJA PASSES d'une chaine replay. Un clic lit l'archive
 * (timeshift) dans le lecteur habituel, en mode VOD pour pouvoir avancer/reculer.
 */
class ReplayProgramsActivity : BaseActivity() {

    private lateinit var rv: RecyclerView
    private lateinit var msgTv: TextView
    private var progs: List<ReplayApi.Prog> = emptyList()
    private var chName = ""
    private var chLogo = ""
    private var streamId = ""
    private var chCmd = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_replay_programs)
        chName = intent.getStringExtra("title").orEmpty()
        chLogo = intent.getStringExtra("logo").orEmpty()
        streamId = intent.getStringExtra("streamId").orEmpty()
        chCmd = intent.getStringExtra("cmd").orEmpty()

        findViewById<TextView>(R.id.titleTv).text = if (chName.isBlank()) "Replay" else "Replay - " + chName
        findViewById<TextView>(R.id.backBtn).setOnClickListener { finish() }
        msgTv = findViewById(R.id.msgTv)
        rv = findViewById(R.id.progRv)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = ProgAdapter()
        load()
    }

    private fun load() {
        val pl = Session.current
        if (pl == null) { msgTv.text = "Aucun serveur actif."; return }
        if (pl.type != "xtream" && pl.type != "stalker") {
            msgTv.text = "Le replay avec archive n'est pas disponible sur ce type de serveur."
            return
        }
        if (pl.type == "stalker") {
            msgTv.text = "Le Replay est indisponible sur ce type de code. " +
                "Veuillez l'utiliser via un Xtream ou un M3U"
            return
        }
        if (pl.type == "stalker" && chCmd.isBlank()) {
            msgTv.text = "Cette chaine ne fournit pas de flux Stalker exploitable."
            return
        }
        if (pl.type == "xtream" && streamId.isBlank()) {
            msgTv.text = "Cette chaine ne fournit pas d'identifiant de flux."
            return
        }
        msgTv.text = "Chargement des programmes..."
        lifecycleScope.launch {
            val list = try { ReplayApi.programs(pl, streamId) } catch (e: Exception) { emptyList() }
            progs = if (list.isNotEmpty()) list else ReplayApi.fallbackSlots(24)
            msgTv.text = if (list.isEmpty()) "Guide indisponible : choisis une tranche horaire." else ""
            rv.adapter?.notifyDataSetChanged()
        }
    }

    private fun playProg(p: ReplayApi.Prog) {
        val pl = Session.current ?: return
        msgTv.text = "Ouverture de l'archive..."
        lifecycleScope.launch {
            val url = try { ReplayApi.archiveUrl(pl, streamId, chCmd, p) } catch (e: Exception) { "" }
            if (url.isBlank()) { msgTv.text = "Archive indisponible pour ce programme."; return@launch }
            msgTv.text = ""
            openPlayer(p, url)
        }
    }

    // v398 : telechargement du replay Xtream dans Parametres > Telechargements.
    private fun downloadProg(p: ReplayApi.Prog) {
        val pl = Session.current ?: return
        if (pl.type != "xtream") {
            Toast.makeText(this, "Le telechargement Replay est disponible pour Xtream.", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Preparation du replay...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val url = try { ReplayApi.archiveDownloadUrl(pl, streamId, chCmd, p) } catch (_: Exception) { "" }
            if (url.isBlank()) {
                Toast.makeText(this@ReplayProgramsActivity,
                    "Ce serveur ne fournit pas ce replay dans un format telechargeable.",
                    Toast.LENGTH_LONG).show()
                return@launch
            }
            val title = listOf(p.title, chName, p.time).filter { it.isNotBlank() }.joinToString(" - ")
            Toast.makeText(this@ReplayProgramsActivity,
                Downloads.enqueue(this@ReplayProgramsActivity, title, url),
                Toast.LENGTH_LONG).show()
        }
    }

    private fun openPlayer(p: ReplayApi.Prog, url: String) {
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra("url", url)
                .putExtra("title", p.title + " - " + chName)
                .putExtra("logo", chLogo)
                .putExtra("historyKind", "live")
                .putExtra("mode", "vod")
        )
    }

    inner class ProgAdapter : RecyclerView.Adapter<ProgAdapter.VH>() {
        inner class VH(val v: View) : RecyclerView.ViewHolder(v) {
            val playArea: View = v.findViewById(R.id.replayPlayArea)
            val time: TextView = v.findViewById(R.id.progTime)
            val title: TextView = v.findViewById(R.id.progTitle)
            val desc: TextView = v.findViewById(R.id.progDesc)
            val download: TextView = v.findViewById(R.id.replayDownloadBtn)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_replay_prog, parent, false))
        override fun getItemCount(): Int = progs.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val p = progs[position]
            holder.time.text = p.time
            holder.title.text = p.title
            if (p.desc.isBlank()) holder.desc.visibility = View.GONE
            else { holder.desc.visibility = View.VISIBLE; holder.desc.text = p.desc }
            holder.playArea.setOnClickListener { playProg(p) }
            holder.download.setOnClickListener { downloadProg(p) }
            holder.download.setOnFocusChangeListener { view, has ->
                val scale = if (has) 1.08f else 1f
                view.animate().scaleX(scale).scaleY(scale).setDuration(110).start()
            }
            holder.playArea.setOnFocusChangeListener { view, has ->
                val scale = if (has) 1.02f else 1f
                view.animate().scaleX(scale).scaleY(scale).setDuration(110).start()
            }
        }
    }
}
