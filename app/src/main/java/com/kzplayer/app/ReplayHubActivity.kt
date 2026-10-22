package com.kzplayer.app

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.launch

// v363 : PAGE REPLAY.
// Colonne de gauche : d abord les CATEGORIES, puis (au meme endroit) les CHAINES
// de la categorie choisie. Un bouton Retour ramene aux categories.
// Colonne de droite : les programmes du jour selectionne dans la barre de jours.
class ReplayHubActivity : BaseActivity() {

    private lateinit var dayBar: LinearLayout
    private lateinit var chRv: RecyclerView
    private lateinit var progRv: RecyclerView
    private lateinit var msgTv: TextView
    private lateinit var progress: ProgressBar
    private lateinit var searchEt: EditText
    private lateinit var leftTitle: TextView

    private var cats: List<Category> = emptyList()
    private var channels: List<Item> = emptyList()
    private var shown: List<Item> = emptyList()
    private var progs: List<ReplayApi.Prog> = emptyList()
    private var selCat: Category? = null
    private var selCh: Item? = null
    private var dayOffset = 0
    private val dayCount = 8
    private var openingArchive = false
    private var programsJob: kotlinx.coroutines.Job? = null
    private var programsGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_replay_hub)
        findViewById<TextView>(R.id.backBtn).setOnClickListener { onBackPressed() }
        dayBar = findViewById(R.id.dayBar)
        chRv = findViewById(R.id.chRv)
        progRv = findViewById(R.id.progRv)
        msgTv = findViewById(R.id.msgTv)
        progress = findViewById(R.id.progress)
        searchEt = findViewById(R.id.searchEt)
        leftTitle = findViewById(R.id.leftTitle)
        chRv.layoutManager = LinearLayoutManager(this)
        chRv.adapter = LeftAdapter()
        progRv.layoutManager = LinearLayoutManager(this)
        progRv.adapter = ProgAdapter()
        renderDays()
        searchEt.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { applyFilter() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        ReplaySources.installQuota(this)
        loadCategories()
    }

    // Retour : si on est dans une categorie, on revient a la liste des categories.
    override fun onBackPressed() {
        if (selCat != null) { backToCategories(); return }
        super.onBackPressed()
    }

    private fun backToCategories() {
        programsJob?.cancel()
        programsGeneration++
        selCat = null
        selCh = null
        channels = emptyList()
        shown = emptyList()
        progs = emptyList()
        searchEt.setText("")
        leftTitle.text = "Cat\u00e9gories"
        progRv.adapter?.notifyDataSetChanged()
        chRv.adapter?.notifyDataSetChanged()
        msgTv.text = "Choisis une cat\u00e9gorie."
    }

    // Barre des jours : Aujourd hui, Hier, puis les jours precedents.
    private fun renderDays() {
        dayBar.removeAllViews()
        val dens = resources.displayMetrics.density
        for (i in 0 until dayCount) {
            val tv = TextView(this)
            tv.text = ReplayApi.dayLabel(i)
            tv.textSize = 13f
            tv.setPadding((16 * dens).toInt(), (9 * dens).toInt(), (16 * dens).toInt(), (9 * dens).toInt())
            tv.isFocusable = true
            tv.isClickable = true
            tv.setBackgroundResource(R.drawable.bg_cat)
            tv.isSelected = i == dayOffset
            tv.setTextColor(ContextCompat.getColor(this, if (i == dayOffset) R.color.text else R.color.muted))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = (8 * dens).toInt()
            tv.layoutParams = lp
            val idx = i
            tv.setOnClickListener {
                dayOffset = idx
                renderDays()
                if (selCh != null) loadProgs()
            }
            dayBar.addView(tv)
        }
    }

    // Etape 1 : les categories du serveur actif (chargement immediat, tres rapide).
    private fun loadCategories() {
        val pl = Session.current
        if (pl == null) { msgTv.text = "Aucun serveur actif."; return }
        if (pl.type !in listOf("xtream", "stalker", "m3u")) {
            msgTv.text = "Type de source replay non pris en charge."
            return
        }
        leftTitle.text = "Cat\u00e9gories"
        progress.visibility = View.VISIBLE
        msgTv.text = "Chargement des cat\u00e9gories..."
        lifecycleScope.launch {
            val base = try {
                if (pl.type == "stalker") Api.stalkerCategories(pl, "live")
                else if (pl.type == "m3u") Api.m3uCategories(pl, "live")
                else Api.xtreamCategories(pl, "live")
            } catch (e: Exception) { emptyList<Category>() }
            CategorySync.report(this@ReplayHubActivity, pl, "live", base)
            cats = if (pl.type == "m3u") base else base.filter { !it.id.startsWith("__") }
            progress.visibility = View.GONE
            chRv.adapter?.notifyDataSetChanged()
            msgTv.text = if (cats.isEmpty()) "Aucune cat\u00e9gorie sur ce serveur."
                else "Choisis une cat\u00e9gorie."
        }
    }

    // Etape 2 : les chaines de la categorie, au meme endroit que les categories.
    private fun openCategory(cat: Category) {
        programsJob?.cancel()
        programsGeneration++
        val pl = Session.current ?: return
        selCat = cat
        selCh = null
        channels = emptyList()
        shown = emptyList()
        progs = emptyList()
        progRv.adapter?.notifyDataSetChanged()
        chRv.adapter?.notifyDataSetChanged()
        leftTitle.text = cat.name
        progress.visibility = View.VISIBLE
        msgTv.text = "Chargement des chaines..."
        lifecycleScope.launch {
            val items = try {
                if (pl.type == "stalker") Api.stalkerItems(pl, "live", cat.id)
                else if (pl.type == "m3u") Api.m3uItems(pl, "live", cat.id)
                else Api.xtreamItems(pl, "live", cat.id)
            } catch (e: Exception) { emptyList<Item>() }
            val withCatchup = items.filter { it.catchup }
            channels = if (withCatchup.isNotEmpty()) withCatchup else items
            applyFilter()
            progress.visibility = View.GONE
            msgTv.text = if (channels.isEmpty()) "Aucune chaine dans cette cat\u00e9gorie."
                else if (withCatchup.isEmpty()) "Aucun replay signal\u00e9 : toutes les chaines sont affich\u00e9es."
                else "Choisis une chaine, puis un jour."
        }
    }

    private fun applyFilter() {
        val q = searchEt.text.toString().trim().lowercase()
        shown = if (q.isBlank()) channels else channels.filter { it.name.lowercase().contains(q) }
        chRv.adapter?.notifyDataSetChanged()
    }

    private fun select(ch: Item) {
        selCh = ch
        chRv.adapter?.notifyDataSetChanged()
        loadProgs()
    }

    // Programmes du jour choisi. Si le serveur ne donne pas de guide pour ce jour,
    // on propose des tranches de 30 minutes uniquement lorsque le protocole accepte des tranches sans identifiant EPG.
    private fun loadProgs() {
        programsJob?.cancel()
        val generation = ++programsGeneration
        val requestedDay = dayOffset
        val pl = Session.current ?: return
        val ch = selCh ?: return
        progs = emptyList()
        progRv.adapter?.notifyDataSetChanged()
        progress.visibility = View.VISIBLE
        programsJob = lifecycleScope.launch {
            val all = try { ReplayApi.programs(pl, ch, requestedDay) } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                progress.visibility = View.GONE
                msgTv.text = ReplaySources.failureMessage(e)
                return@launch
            }
            if (generation != programsGeneration || selCh !== ch || Session.current?.id != pl.id) return@launch
            val ofDay = ReplayApi.filterDay(all, requestedDay)
            progs = if (ofDay.isNotEmpty()) ofDay else if (ReplaySources.allowSlots(pl, ch)) ReplayApi.slotsForDay(requestedDay).filter { ReplaySources.availableSlot(pl, ch, it) } else emptyList()
            progress.visibility = View.GONE
            msgTv.text = ch.name + "   -   " + ReplayApi.dayLabel(requestedDay) +
                (if (ofDay.isEmpty()) "   (guide indisponible : tranches horaires)" else "")
            if (progs.isEmpty()) msgTv.text = "Aucune archive exploitable pour ce jour : guide, droits ou fenetre de conservation indisponibles."
            progRv.adapter?.notifyDataSetChanged()
        }
    }

    private fun playProg(p: ReplayApi.Prog) {
        val pl = Session.current ?: return
        val ch = selCh ?: return
        if (openingArchive) return
        val generation = programsGeneration
        openingArchive = true
        progress.visibility = View.VISIBLE
        msgTv.text = "Recherche de l archive..."
        lifecycleScope.launch {
            val archive = try {
                ReplayApi.resolve(pl, ch, p)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                openingArchive = false
                progress.visibility = View.GONE
                msgTv.text = ReplaySources.failureMessage(e)
                return@launch
            }
            val url = archive.url
            openingArchive = false
            progress.visibility = View.GONE
            if (generation != programsGeneration || selCh !== ch || Session.current?.id != pl.id) return@launch
            if (url.isBlank()) {
                msgTv.text = "Archive introuvable pour ce programme sur ce serveur. " +
                    "Essaie un autre horaire ou une autre chaine."
                if (AdminMode.diagEnabled(this@ReplayHubActivity)) {
                    msgTv.text = "Archive indisponible." + System.lineSeparator() +
                        archive.diagnostic
                }
                return@launch
            }
            startActivity(
                Intent(this@ReplayHubActivity, PlayerActivity::class.java)
                    .putExtra("url", url)
                    .putExtra("title", p.title + " - " + ch.name)
                    .putExtra("logo", ch.logo)
                    .putExtra("historyKind", "live")
                    .putExtra("mode", "vod")
                    .putExtra("mime", archive.mime)
                    .putExtra("replay", true)
                    .putExtra("replayDeclaredCatchup", pl.type != "xtream")
                    .putExtra("replayUaLocked", pl.type != "xtream")
                    .putExtra("replayStartMs", p.startMs)
                    .putExtra("replayEndMs", p.endMs)
                    .putStringArrayListExtra("replayCandidates", ArrayList(archive.candidates))
                    .putExtra("forceUa", archive.userAgent)
            )
        }
    }

    // V115 : l'ecran principal Replay partage la carte a deux actions avec
    // ReplayProgramsActivity ; chaque controle enfant doit avoir sa propre action.
    private fun downloadProg(p: ReplayApi.Prog) {
        val pl = Session.current
        val ch = selCh
        if (pl == null || ch == null) {
            msgTv.text = "Choisis un serveur et une chaine avant de telecharger."
            return
        }
        if (pl.type != "xtream") {
            val message = "Le telechargement Replay est disponible pour Xtream."
            msgTv.text = message
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            return
        }
        msgTv.text = "Preparation du telechargement..."
        Toast.makeText(this, "Preparation du replay...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            // Meme moteur et memes URL que l'autre ecran Replay.
            // La creation de la tache n'attend pas de sonde video.
            val urls = try {
                ReplayApi.archiveDownloadUrls(pl, ch.streamId.orEmpty(), p)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList<String>()
            }
            val title = listOf(p.title, ch.name, p.time)
                .filter { it.isNotBlank() }.joinToString(" - ")
            val result = Downloads.enqueueInternalResult(this@ReplayHubActivity, title, urls)
            msgTv.text = result.message
            Toast.makeText(this@ReplayHubActivity, result.message, Toast.LENGTH_LONG).show()
            if (result.started) {
                startActivity(Intent(this@ReplayHubActivity, DownloadsActivity::class.java))
            }
        }
    }

    // Toucher/souris et OK/Entree de la telecommande passent par la meme action.
    private fun bindReplayAction(view: View, action: () -> Unit) {
        view.setOnClickListener { action() }
        view.setOnKeyListener { target, keyCode, event ->
            val activate = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == KeyEvent.KEYCODE_ENTER ||
                keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
                keyCode == KeyEvent.KEYCODE_BUTTON_A
            if (!activate) false
            else {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                    target.performClick()
                }
                true
            }
        }
    }

    // Colonne de gauche : categories (si aucune categorie ouverte) ou chaines.
    inner class LeftAdapter : RecyclerView.Adapter<LeftAdapter.VH>() {
        inner class VH(val v: View) : RecyclerView.ViewHolder(v) {
            val logo: ImageView = v.findViewById(R.id.chLogo)
            val name: TextView = v.findViewById(R.id.chName)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_replay_channel, parent, false))
        override fun getItemCount(): Int = if (selCat == null) cats.size else shown.size + 1
        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.v.setOnFocusChangeListener { view, has ->
                view.animate().scaleX(if (has) 1.02f else 1f).scaleY(if (has) 1.02f else 1f)
                    .setDuration(100).start()
            }
            val cat = selCat
            if (cat == null) {
                val c = cats[position]
                holder.name.text = c.name
                holder.logo.setImageResource(R.drawable.ic_catchup)
                holder.v.isSelected = false
                holder.v.setOnClickListener { openCategory(c) }
                return
            }
            if (position == 0) {
                holder.name.text = "\u25c0  Retour aux cat\u00e9gories"
                holder.logo.setImageResource(R.drawable.ic_chevron)
                holder.v.isSelected = false
                holder.v.setOnClickListener { backToCategories() }
                return
            }
            val ch = shown[position - 1]
            holder.name.text = ch.name
            holder.logo.load(ch.logo) { crossfade(false); placeholder(R.drawable.bg_tile); error(R.drawable.ic_live_tv) }
            holder.v.isSelected = ch === selCh
            holder.v.setOnClickListener { select(ch) }
        }
    }

    inner class ProgAdapter : RecyclerView.Adapter<ProgAdapter.VH>() {
        inner class VH(val v: View) : RecyclerView.ViewHolder(v) {
            val playArea: View = v.findViewById(R.id.replayPlayArea)
            val download: Button = v.findViewById(R.id.replayDownloadBtn)
            val time: TextView = v.findViewById(R.id.progTime)
            val title: TextView = v.findViewById(R.id.progTitle)
            val desc: TextView = v.findViewById(R.id.progDesc)
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
            holder.v.setOnClickListener { playProg(p) }
            bindReplayAction(holder.playArea) { playProg(p) }
            bindReplayAction(holder.download) { downloadProg(p) }
            holder.playArea.nextFocusRightId = R.id.replayDownloadBtn
            holder.download.nextFocusLeftId = R.id.replayPlayArea
            holder.playArea.setOnFocusChangeListener { view, has ->
                view.animate().scaleX(if (has) 1.02f else 1f).scaleY(if (has) 1.02f else 1f)
                    .setDuration(100).start()
            }
            holder.download.setOnFocusChangeListener { view, has ->
                view.animate().scaleX(if (has) 1.08f else 1f).scaleY(if (has) 1.08f else 1f)
                    .setDuration(110).start()
            }
        }
    }
}
