package com.kzplayer.app

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
        loadCategories()
    }

    // Retour : si on est dans une categorie, on revient a la liste des categories.
    override fun onBackPressed() {
        if (selCat != null) { backToCategories(); return }
        super.onBackPressed()
    }

    private fun backToCategories() {
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
        // v367 : les portails Stalker/MAG ne donnent pas d archive exploitable.
        if (pl.type == "stalker") {
            cats = emptyList()
            channels = emptyList()
            shown = emptyList()
            progs = emptyList()
            chRv.adapter?.notifyDataSetChanged()
            progRv.adapter?.notifyDataSetChanged()
            leftTitle.text = "Replay"
            progress.visibility = View.GONE
            searchEt.visibility = View.GONE
            msgTv.text = "Le Replay est indisponible sur ce type de code. " +
                "Veuillez l'utiliser via un Xtream ou un M3U"
            return
        }
        if (pl.type != "xtream") {
            msgTv.text = "Le Replay est indisponible sur ce type de code. " +
                "Veuillez l'utiliser via un Xtream ou un M3U"
            return
        }
        leftTitle.text = "Cat\u00e9gories"
        progress.visibility = View.VISIBLE
        msgTv.text = "Chargement des cat\u00e9gories..."
        lifecycleScope.launch {
            val base = try {
                if (pl.type == "stalker") Api.stalkerCategories(pl, "live")
                else Api.xtreamCategories(pl, "live")
            } catch (e: Exception) { emptyList<Category>() }
            CategorySync.report(this@ReplayHubActivity, pl, "live", base)
            cats = base.filter { !it.id.startsWith("__") }
            progress.visibility = View.GONE
            chRv.adapter?.notifyDataSetChanged()
            msgTv.text = if (cats.isEmpty()) "Aucune cat\u00e9gorie sur ce serveur."
                else "Choisis une cat\u00e9gorie."
        }
    }

    // Etape 2 : les chaines de la categorie, au meme endroit que les categories.
    private fun openCategory(cat: Category) {
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
    // on propose des tranches de 30 minutes (ca marche sur tous les serveurs).
    private fun loadProgs() {
        val pl = Session.current ?: return
        val ch = selCh ?: return
        progs = emptyList()
        progRv.adapter?.notifyDataSetChanged()
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val all = try { ReplayApi.programs(pl, ch.streamId ?: "") } catch (e: Exception) { emptyList() }
            val ofDay = ReplayApi.filterDay(all, dayOffset)
            progs = if (ofDay.isNotEmpty()) ofDay else ReplayApi.slotsForDay(dayOffset)
            progress.visibility = View.GONE
            msgTv.text = ch.name + "   -   " + ReplayApi.dayLabel(dayOffset) +
                (if (ofDay.isEmpty()) "   (guide indisponible : tranches horaires)" else "")
            progRv.adapter?.notifyDataSetChanged()
        }
    }

    private fun playProg(p: ReplayApi.Prog) {
        val pl = Session.current ?: return
        val ch = selCh ?: return
        if (pl.type == "stalker") {
            msgTv.text = "Le Replay est indisponible sur ce type de code. " +
                "Veuillez l'utiliser via un Xtream ou un M3U"
            return
        }
        progress.visibility = View.VISIBLE
        msgTv.text = "Recherche de l archive..."
        lifecycleScope.launch {
            val url = try {
                ReplayApi.archiveUrl(pl, ch.streamId ?: "", ch.cmd ?: "", p)
            } catch (e: Exception) { "" }
            progress.visibility = View.GONE
            if (url.isBlank()) {
                msgTv.text = "Archive introuvable pour ce programme sur ce serveur. " +
                    "Essaie un autre horaire ou une autre chaine."
                if (AdminMode.diagEnabled(this@ReplayHubActivity)) {
                    msgTv.text = "Archive indisponible." + System.lineSeparator() +
                        ReplayApi.lastArchiveLog + System.lineSeparator() +
                        Api.lastArchiveStalkerLog
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
                    .putExtra("mime", ReplayApi.lastArchiveMime)
                    .putExtra("forceUa", "IPTVSmartersPro")
            )
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
            holder.v.setOnFocusChangeListener { view, has ->
                view.animate().scaleX(if (has) 1.02f else 1f).scaleY(if (has) 1.02f else 1f)
                    .setDuration(100).start()
            }
        }
    }
}
