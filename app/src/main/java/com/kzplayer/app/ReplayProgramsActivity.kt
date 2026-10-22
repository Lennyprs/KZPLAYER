package com.kzplayer.app

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
    private var openingArchive = false
    private var replayChannel: Item? = null

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
        @Suppress("DEPRECATION")
        val supplied = intent.getSerializableExtra("replayChannel") as? Item
        replayChannel = supplied ?: Item(name = chName, logo = chLogo, streamId = streamId, cmd = chCmd)
        ReplaySources.installQuota(this)
        load()
    }

    private fun load() {
        val pl = Session.current
        if (pl == null) { msgTv.text = "Aucun serveur actif."; return }
        if (pl.type !in listOf("xtream", "stalker", "m3u")) {
            msgTv.text = "Source replay non prise en charge."
            return
        }
        if (pl.type == "xtream" && streamId.isBlank()) {
            msgTv.text = "Cette chaine ne fournit pas d'identifiant de flux."
            return
        }
        msgTv.text = "Chargement des programmes..."
        lifecycleScope.launch {
            val channel = replayChannel ?: return@launch
            val list = try { ReplayApi.programs(pl, channel, 0) } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                msgTv.text = ReplaySources.failureMessage(e)
                return@launch
            }
            progs = if (list.isNotEmpty()) list else if (ReplaySources.allowSlots(pl, channel)) ReplayApi.fallbackSlots(24).filter { ReplaySources.availableSlot(pl, channel, it) } else emptyList()
            msgTv.text = if (progs.isEmpty()) "Aucune archive exploitable : guide, droits ou fenetre de conservation indisponibles." else if (list.isEmpty()) "Guide indisponible : choisis une tranche horaire." else ""
            rv.adapter?.notifyDataSetChanged()
            // v415 : le premier choix au D-pad est toujours REGARDER, pas Telecharger.
            rv.post {
                rv.findViewHolderForAdapterPosition(0)?.itemView
                    ?.findViewById<View>(R.id.replayPlayArea)?.requestFocus()
            }
        }
    }

    private fun playProg(p: ReplayApi.Prog) {
        val pl = Session.current ?: return
        if (openingArchive) return
        openingArchive = true
        msgTv.text = "Ouverture de l'archive..."
        lifecycleScope.launch {
            val archive = try { ReplayApi.resolve(pl, replayChannel ?: return@launch, p) } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                openingArchive = false
                msgTv.text = ReplaySources.failureMessage(e)
                return@launch
            }
            val url = archive.url
            openingArchive = false
            if (url.isBlank()) { msgTv.text = "Archive indisponible pour ce programme."; return@launch }
            if (Session.current?.id != pl.id) return@launch
            msgTv.text = ""
            openPlayer(p, archive)
        }
    }

    // v398 : telechargement du replay Xtream dans Parametres > Telechargements.
    private fun downloadProg(p: ReplayApi.Prog) {
        val pl = Session.current ?: return
        if (pl.type != "xtream") {
            Toast.makeText(this, "Le telechargement Replay est disponible pour Xtream.", Toast.LENGTH_LONG).show()
            return
        }
        msgTv.text = "Preparation du telechargement..."
        Toast.makeText(this, "Preparation du replay...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            // v406 : aucune sonde reseau avant d ajouter la tache. Elle apparait
            // immediatement, puis le moteur essaie plusieurs URL Xtream tout seul.
            val urls = try { ReplayApi.archiveDownloadUrls(pl, streamId, p) } catch (_: Throwable) { emptyList() }
            val title = listOf(p.title, chName, p.time).filter { it.isNotBlank() }.joinToString(" - ")
            val result = Downloads.enqueueInternalResult(this@ReplayProgramsActivity, title, urls)
            msgTv.text = result.message
            Toast.makeText(this@ReplayProgramsActivity, result.message, Toast.LENGTH_LONG).show()
            if (result.started) {
                startActivity(Intent(this@ReplayProgramsActivity, DownloadsActivity::class.java))
            }
        }
    }

    private fun openPlayer(p: ReplayApi.Prog, archive: ReplayApi.Archive) {
        val url = archive.url
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra("url", url)
                .putExtra("title", p.title + " - " + chName)
                .putExtra("logo", chLogo)
                .putExtra("historyKind", "live")
                .putExtra("mode", "vod")
                .putExtra("replay", true)
                .putExtra("replayDeclaredCatchup", Session.current?.type != "xtream")
                .putExtra("replayUaLocked", Session.current?.type != "xtream")
                .putExtra("replayStartMs", p.startMs)
                .putExtra("replayEndMs", p.endMs)
                .putExtra("mime", archive.mime)
                .putStringArrayListExtra("replayCandidates", ArrayList(archive.candidates))
                .putExtra("forceUa", archive.userAgent)
        )
    }

    inner class ProgAdapter : RecyclerView.Adapter<ProgAdapter.VH>() {
        inner class VH(val v: View) : RecyclerView.ViewHolder(v) {
            val row: View = v.findViewById(R.id.replayRow)
            val playArea: View = v.findViewById(R.id.replayPlayArea)
            val time: TextView = v.findViewById(R.id.progTime)
            val title: TextView = v.findViewById(R.id.progTitle)
            val desc: TextView = v.findViewById(R.id.progDesc)
            val download: Button = v.findViewById(R.id.replayDownloadBtn)
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
            // Deux actions strictement separees : toute la fiche/zone gauche lit le replay,
            // seul le bouton de droite lance le telechargement.
            holder.row.setOnClickListener { playProg(p) }
            holder.playArea.setOnClickListener { playProg(p) }
            holder.download.setOnClickListener { downloadProg(p) }
            holder.playArea.nextFocusRightId = R.id.replayDownloadBtn
            holder.download.nextFocusLeftId = R.id.replayPlayArea
            holder.playArea.setOnKeyListener { _, keyCode, event ->
                val activate = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
                    keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || keyCode == KeyEvent.KEYCODE_BUTTON_A
                if (activate && event.action == KeyEvent.ACTION_UP) { playProg(p); true } else activate
            }
            holder.download.setOnKeyListener { _, keyCode, event ->
                val activate = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
                    keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || keyCode == KeyEvent.KEYCODE_BUTTON_A
                if (activate && event.action == KeyEvent.ACTION_UP) { downloadProg(p); true } else activate
            }
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
