package com.kzplayer.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import coil.load
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Ecran GUIDE (theme NewTivi) : categories a gauche, grille EPG (chaine + programmes) a droite.
open class NewGuideActivity : NtBase() {
    protected open val navTag: String = "guide"
    protected open val headerTitle: String = "Guide"
    // Si true (ecran TV), le lecteur reduit s'affiche dans l'apercu en haut au lieu d'ouvrir une page.
    protected open val playsInline: Boolean = false
    private var voicePlay: String = ""
    private var voiceTried: Boolean = false
    private var inlinePlayer: ExoPlayer? = null
    private var heroPlayer: PlayerView? = null
    private var playingUrl: String = ""
    private var playingItem: Item? = null
    private lateinit var catRv: RecyclerView
    private lateinit var channelRv: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var msgTv: TextView
    private lateinit var clockTv: TextView
    private lateinit var dateTv: TextView
    private lateinit var sourceTv: TextView
    private lateinit var heroImg: ImageView
    private lateinit var heroTitle: TextView
    private lateinit var heroTime: TextView
    private lateinit var heroDesc: TextView

    private var categories: List<Category> = emptyList()
    private var channels: List<Item> = emptyList()
    private var selectedCat: String = ""
    private var catAdapter: CatAdapter? = null
    private var chAdapter: ChannelAdapter? = null
    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            val now = Date()
            dateTv.text = SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(now)
            clockTv.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
            clockHandler.postDelayed(this, 30000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_new_guide)
        findViewById<TextView?>(R.id.screenTitleTv)?.text = headerTitle
        NavHelper.setup(this, navTag)
        catRv = findViewById(R.id.catRv)
        channelRv = findViewById(R.id.channelRv)
        progress = findViewById(R.id.progress)
        msgTv = findViewById(R.id.msgTv)
        clockTv = findViewById(R.id.clockTv)
        dateTv = findViewById(R.id.dateTv)
        sourceTv = findViewById(R.id.sourceTv)
        findViewById<TextView?>(R.id.optionsBtn)?.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        heroImg = findViewById(R.id.heroImg)
        heroTitle = findViewById(R.id.heroTitle)
        heroTime = findViewById(R.id.heroTime)
        heroDesc = findViewById(R.id.heroDesc)
        heroPlayer = findViewById(R.id.heroPlayer)
        heroPlayer?.useController = false
        catRv.layoutManager = LinearLayoutManager(this)
        channelRv.layoutManager = LinearLayoutManager(this)
        channelRv.setItemViewCacheSize(20)
        clockRunnable.run()
        bindTimeHeader()
        // Commande vocale : lancer directement une chaine dans l'apercu inline (mode TV NewTivi).
        voicePlay = if (playsInline) intent.getStringExtra("voicePlay")?.trim().orEmpty() else ""
        ensureSession { loadCategories() }
    }

    private fun bindTimeHeader() {
        val tf = SimpleDateFormat("HH:mm", Locale.getDefault())
        val nowMs = System.currentTimeMillis()
        val ids = intArrayOf(R.id.timeH1, R.id.timeH2, R.id.timeH3, R.id.timeH4)
        for (i in ids.indices) {
            findViewById<TextView?>(ids[i])?.text = tf.format(Date(nowMs + i * 30L * 60L * 1000L))
        }
    }

    override fun onDestroy() { super.onDestroy(); clockHandler.removeCallbacks(clockRunnable); inlinePlayer?.release(); inlinePlayer = null }
    override fun onStop() { super.onStop(); inlinePlayer?.release(); inlinePlayer = null }
    override fun onStart() {
        super.onStart()
        if (playsInline && inlinePlayer == null && playingUrl.isNotBlank()) {
            heroImg.visibility = View.INVISIBLE; heroPlayer?.visibility = View.VISIBLE; buildAndPlay(playingUrl)
        }
    }

    private fun setLoading(b: Boolean) { progress.visibility = if (b) View.VISIBLE else View.GONE }

    private fun loadCategories() {
        val pl = Session.current ?: run { msgTv.text = "Serveurs indisponibles."; return }
        setLoading(true); msgTv.text = ""
        lifecycleScope.launch {
            try {
                // v391 : mode multiliste (toutes les listes) aussi dans le direct NewTivi.
                val multiLists = MultiListPref.isAll(this@NewGuideActivity) && Session.playlists.size > 1
                val base = if (multiLists) catsAllLive() else when (pl.type) {
                    "m3u" -> Api.m3uCategories(pl, "live")
                    "stalker" -> Api.stalkerCategories(pl, "live")
                    else -> Api.xtreamCategories(pl, "live")
                }
                categories = listOf(Category("__favorites__", "Favoris")) + base.filter { !it.id.startsWith("__") }
                catAdapter = CatAdapter(categories) { selectCategory(it) }
                catRv.adapter = catAdapter
                setLoading(false)
                val firstReal = categories.firstOrNull { !it.id.startsWith("__") } ?: categories.firstOrNull()
                if (firstReal != null) selectCategory(firstReal)
                if (voicePlay.isNotBlank() && !voiceTried) startVoicePlay()
            } catch (e: Exception) {
                setLoading(false); msgTv.text = "Erreur : ${e.message}"
            }
        }
    }

    // v391 : categories direct de TOUTES les listes, id = "<categorie>@@<idListe>".
    private suspend fun catsAllLive(): List<Category> {
        val out = ArrayList<Category>()
        for (p in Session.playlists) {
            val cats = try {
                when (p.type) {
                    "m3u" -> Api.m3uCategories(p, "live")
                    "stalker" -> Api.stalkerCategories(p, "live")
                    else -> Api.xtreamCategories(p, "live")
                }
            } catch (e: Exception) { emptyList<Category>() }
            for (c in cats) {
                if (c.id.startsWith("__")) continue
                out.add(Category(c.id + "@@" + p.id, c.name + "   -   " + p.nom))
            }
        }
        return out
    }

    private fun selectCategory(cat: Category) {
        // v392 : ne rafraichir QUE l ancienne + la nouvelle categorie (pas toute la liste)
        // -> le curseur ne remonte plus quand on choisit une categorie plus bas.
        val prevCat = selectedCat
        selectedCat = cat.id
        // v391 : categorie issue du multiliste -> on bascule sur sa liste.
        val atSep = cat.id.indexOf("@@")
        val realCat = if (atSep > 0) cat.id.substring(0, atSep) else cat.id
        if (atSep > 0) {
            val owner = Session.playlists.firstOrNull { it.id == cat.id.substring(atSep + 2) }
            if (owner != null && owner.id != Session.current?.id) Session.current = owner
        }
        val src = Session.current?.nom?.takeIf { it.isNotBlank() }
        sourceTv.text = if (src != null) "$src  /  ${cat.name}" else cat.name
        val prevIdx = categories.indexOfFirst { it.id == prevCat }
        val newIdx = categories.indexOfFirst { it.id == selectedCat }
        if (prevIdx >= 0) catAdapter?.notifyItemChanged(prevIdx)
        if (newIdx >= 0 && newIdx != prevIdx) catAdapter?.notifyItemChanged(newIdx)
        val pl = Session.current ?: return
        if (cat.id == "__favorites__") {
            channels = Favorites.forKind(this, "live")
            bindChannels()
            msgTv.text = if (channels.isEmpty()) "Aucune cha\u00eene favorite." else ""
            setLoading(false)
            return
        }
        setLoading(true); msgTv.text = ""; channels = emptyList(); bindChannels()
        lifecycleScope.launch {
            try {
                when (pl.type) {
                    "stalker" -> {
                        val acc = ArrayList<Item>()
                        Api.stalkerItemsPaged(pl, "live", realCat) { batch ->
                            withContext(Dispatchers.Main) {
                                acc.addAll(batch); channels = acc.toList(); bindChannels(); setLoading(false)
                            }
                        }
                    }
                    "m3u" -> { channels = Api.m3uItems(pl, "live", realCat); bindChannels(); setLoading(false) }
                    else -> { channels = Api.xtreamItems(pl, "live", realCat); bindChannels(); setLoading(false) }
                }
                if (channels.isEmpty()) msgTv.text = "Aucune cha\u00eene." else msgTv.text = ""
            } catch (e: Exception) { setLoading(false); msgTv.text = "Erreur : ${e.message}" }
        }
    }

    private fun bindChannels() {
        if (chAdapter == null) { chAdapter = ChannelAdapter(); channelRv.adapter = chAdapter }
        chAdapter?.submit(channels)
    }

    // Commande vocale : charge toutes les chaines du serveur, trouve la correspondance (parmi
    // toutes les hypotheses de reconnaissance) et la lance dans l'apercu inline du theme NewTivi.
    private fun startVoicePlay() {
        voiceTried = true
        val pl = Session.current ?: return
        val cands = voicePlay.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        voicePlay = ""
        if (cands.isEmpty()) return
        msgTv.text = "Recherche de la cha\u00eene..."
        lifecycleScope.launch {
            val all = ArrayList<Item>()
            var played = false
            try {
                when (pl.type) {
                    // Stalker : on tente le match AU FIL des lots -> la chaine se lance des qu'elle
                    // apparait, sans attendre le chargement complet du catalogue (bien plus rapide).
                    "stalker" -> Api.stalkerItemsPaged(pl, "live", "__all__") { batch ->
                        withContext(Dispatchers.Main) {
                            if (played) return@withContext
                            all.addAll(batch)
                            val liveNow = all.filter { it.kind == "live" }
                            val m = cands.firstNotNullOfOrNull { findChannel(it, liveNow) }
                            if (m != null) {
                                played = true
                                channels = liveNow
                                bindChannels()
                                msgTv.text = ""
                                playChannel(m)
                            }
                        }
                    }
                    "m3u" -> all.addAll(Api.m3uItems(pl, "live", "__all__"))
                    else -> all.addAll(Api.xtreamItems(pl, "live", "__all__"))
                }
            } catch (e: Exception) {}
            withContext(Dispatchers.Main) {
                if (played) return@withContext
                val live = all.filter { it.kind == "live" }
                val match = cands.firstNotNullOfOrNull { findChannel(it, live) }
                if (match != null) {
                    channels = live
                    bindChannels()
                    msgTv.text = ""
                    playChannel(match)
                } else {
                    msgTv.text = "Cha\u00eene introuvable."
                }
            }
        }
    }

    private fun findChannel(query: String, list: List<Item>): Item? {
        val q = normName(query)
        if (q.isBlank()) return null
        val g = glue(q)
        val toks = q.split(" ").filter { it.isNotBlank() }
        return list.firstOrNull { normName(it.name) == q || glue(normName(it.name)) == g }
            ?: list.firstOrNull { normName(it.name).startsWith(q) || glue(normName(it.name)).startsWith(g) }
            ?: list.firstOrNull { normName(it.name).contains(q) }
            ?: list.firstOrNull { c -> toks.isNotEmpty() && toks.all { normName(c.name).contains(it) } }
    }

    private fun normName(s: String): String =
        java.text.Normalizer.normalize(s.trim().lowercase(java.util.Locale.FRENCH), java.text.Normalizer.Form.NFD)
            .replace("\\p{Mn}".toRegex(), "")
            .replace("[^a-z0-9 ]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()

    private fun glue(s: String): String =
        Regex("\\b([a-z]{1,3}) (\\d+)").replace(s) { it.groupValues[1] + it.groupValues[2] }

    private suspend fun epgFor(pl: Playlist, item: Item): List<EpgEntry> = try {
        when (pl.type) {
            "xtream" -> item.streamId?.let { Api.xtreamFullEpg(pl, it) } ?: emptyList()
            "stalker" -> item.streamId?.let { Api.stalkerShortEpg(pl, it) } ?: emptyList()
            else -> emptyList()
        }
    } catch (e: Exception) { emptyList() }

    private fun updateHero(item: Item, epg: List<EpgEntry>) {
        val nowIdx = epg.indexOfFirst { it.nowPlaying }.let { if (it >= 0) it else 0 }
        val now = epg.getOrNull(nowIdx)
        heroTitle.text = (now?.title ?: "").ifBlank { item.name }
        heroTime.text = now?.time ?: ""
        heroDesc.text = now?.description ?: ""
        heroImg.load(item.logo) { crossfade(true); placeholder(R.drawable.bg_tile); error(R.drawable.ic_live_tv) }
        // Barre de progression du programme en cours (si horaires connus)
        val start = now?.startMs ?: 0L
        val end = epg.getOrNull(nowIdx + 1)?.startMs ?: 0L
        var pct = -1f
        if (start > 0L && end > start) {
            pct = ((System.currentTimeMillis() - start).toFloat() / (end - start).toFloat()).coerceIn(0f, 1f)
        }
        val fill = findViewById<View?>(R.id.heroProgFill)
        val rest = findViewById<View?>(R.id.heroProgRest)
        val pctTv = findViewById<TextView?>(R.id.heroPctTv)
        if (pct >= 0f) {
            (fill?.layoutParams as? LinearLayout.LayoutParams)?.let { it.weight = pct; fill.layoutParams = it }
            (rest?.layoutParams as? LinearLayout.LayoutParams)?.let { it.weight = 1f - pct; rest.layoutParams = it }
            pctTv?.text = "${(pct * 100).toInt()}% \u00e9coul\u00e9"
            pctTv?.visibility = View.VISIBLE
        } else {
            (fill?.layoutParams as? LinearLayout.LayoutParams)?.let { it.weight = 0.12f; fill.layoutParams = it }
            (rest?.layoutParams as? LinearLayout.LayoutParams)?.let { it.weight = 0.88f; rest.layoutParams = it }
            pctTv?.visibility = View.GONE
        }
    }

    private fun playChannel(item: Item) {
        // Ecran GUIDE : on ne lance AUCUNE chaine, on affiche seulement l'EPG.
        if (!playsInline) {
            val pl = Session.current
            if (pl != null) {
                lifecycleScope.launch {
                    val epg = try { epgFor(pl, item) } catch (e: Exception) { emptyList() }
                    updateHero(item, epg)
                }
            } else {
                updateHero(item, emptyList())
            }
            return
        }
        // 2e clic sur la chaine deja en lecture -> plein ecran
        if (isPlayingSame(item) && playingUrl.isNotBlank()) {
            openFullscreen(item.name, playingUrl, item.logo); return
        }
        val pl = Session.current ?: return
        if (pl.type == "stalker") {
            val cmd = item.cmd
            if (cmd.isNullOrBlank()) { msgTv.text = "Flux indisponible."; return }
            setLoading(true)
            lifecycleScope.launch {
                val link = try { Api.stalkerLink(pl, cmd, "live") } catch (e: Exception) { null }
                setLoading(false)
                if (!link.isNullOrBlank()) route(item, link) else msgTv.text = "Impossible d'obtenir le flux."
            }
            return
        }
        val url = item.directUrl ?: return
        route(item, url)
    }

    private fun route(item: Item, url: String) {
        if (playsInline) startInline(item, url) else openPreview(url, item)
    }

    private fun isPlayingSame(item: Item): Boolean {
        val cur = playingItem ?: return false
        return (item.streamId ?: item.name) == (cur.streamId ?: cur.name)
    }

    private fun startInline(item: Item, url: String) {
        val prevItem = playingItem
        playingItem = item
        playingUrl = url
        Session.liveChannels = channels.filter { it.kind == "live" }
        heroImg.visibility = View.INVISIBLE
        heroPlayer?.visibility = View.VISIBLE
        buildAndPlay(url)
        // v392 : ne rafraichit QUE l ancienne + la nouvelle chaine (garde le focus).
        val newIdx = channels.indexOfFirst { (it.streamId ?: it.name) == (item.streamId ?: item.name) }
        val prevIdx = if (prevItem != null) channels.indexOfFirst { (it.streamId ?: it.name) == (prevItem.streamId ?: prevItem.name) } else -1
        if (prevIdx >= 0) chAdapter?.notifyItemChanged(prevIdx)
        if (newIdx >= 0 && newIdx != prevIdx) chAdapter?.notifyItemChanged(newIdx)
        // v392 : positionne la chaine en cours en haut de la liste : quand l utilisateur
        // remonte au lecteur reduit puis redescend, le focus tombe sur la chaine active
        // au lieu de repartir sur les premieres chaines de la categorie.
        if (newIdx >= 0) {
            (channelRv.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(newIdx, 0)
        }
    }

    private fun buildAndPlay(u: String) {
        inlinePlayer?.release(); inlinePlayer = null
        if (u.isBlank()) return
        // v149 : DNS-over-HTTPS applique aussi au hero preview (idem plein ecran / LivePreview).
        val plCur = Session.current
        val streamUa = if (plCur != null && plCur.type == "stalker") {
            Api.stalkerHeaders(plCur)["User-Agent"]?.takeIf { it.isNotBlank() } ?: "VLC/3.0.20 LibVLC/3.0.20"
        } else "VLC/3.0.20 LibVLC/3.0.20"
        val httpFactory = KzHttpDataSource.factory(this, userAgent = streamUa, allowCrossProtocolRedirects = true)
        val extractors = androidx.media3.extractor.DefaultExtractorsFactory().setTsExtractorFlags(
            androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)
        val msf = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(httpFactory, extractors)
        // v148 : meme fix que le plein ecran (decodeur video logiciel prioritaire
        // pour reparer le hero preview qui restait bloque sur la premiere frame).
        val rf = KzRenderersFactory(this)
            .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        val p = ExoPlayer.Builder(this, rf).setMediaSourceFactory(msf).build()
        inlinePlayer = p
        heroPlayer?.player = p
        p.setMediaItem(MediaItem.fromUri(Uri.parse(u)))
        p.playWhenReady = true
        p.prepare(); p.play()
    }

    private fun openFullscreen(t: String, u: String, lg: String) {
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra("url", u).putExtra("title", t).putExtra("logo", lg)
                .putExtra("historyKind", "live").putExtra("mode", "live")
        )
    }

    private fun openPreview(url: String, item: Item) {
        Session.liveChannels = channels.filter { it.kind == "live" }
        startActivity(
            Intent(this, LivePreviewActivity::class.java)
                .putExtra("url", url).putExtra("title", item.name)
                .putExtra("logo", item.logo).putExtra("streamId", item.streamId ?: "")
        )
    }

    inner class CatAdapter(val data: List<Category>, val onClick: (Category) -> Unit) :
        RecyclerView.Adapter<CatAdapter.VH>() {
        inner class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false) as TextView
            return VH(tv)
        }
        override fun getItemCount() = data.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val c = data[position]
            holder.tv.text = c.name
            val sel = c.id == selectedCat
            holder.tv.isSelected = sel
            holder.tv.setTextColor(ContextCompat.getColor(holder.tv.context, if (sel) R.color.text else R.color.muted))
            holder.tv.setOnClickListener { onClick(c) }
        }
    }

    inner class ChannelAdapter : RecyclerView.Adapter<ChannelAdapter.VH>() {
        private val data = ArrayList<Item>()
        // v392 : pour la pagination Stalker (batches successifs) on ajoute les nouvelles
        // lignes SANS reset : on ne perd plus le focus quand on descend dans les chaines
        // pendant que la categorie continue de se charger.
        fun submit(list: List<Item>) {
            val oldSize = data.size
            val isAppend = list.size >= oldSize &&
                oldSize > 0 &&
                (0 until oldSize).all { i ->
                    val a = data[i]; val b = list[i]
                    (a.streamId ?: a.name) == (b.streamId ?: b.name)
                }
            if (isAppend) {
                val added = list.size - oldSize
                if (added > 0) {
                    for (i in oldSize until list.size) data.add(list[i])
                    notifyItemRangeInserted(oldSize, added)
                }
            } else {
                data.clear(); data.addAll(list); notifyDataSetChanged()
            }
        }
        inner class VH(val v: View) : RecyclerView.ViewHolder(v) {
            val logo: ImageView = v.findViewById(R.id.logoIv)
            val name: TextView = v.findViewById(R.id.nameTv)
            val progRow: LinearLayout = v.findViewById(R.id.progRow)
            val number: TextView = v.findViewById(R.id.numberTv)
            val nowBadge: TextView = v.findViewById(R.id.nowBadge)
            val hd: TextView = v.findViewById(R.id.hdBadge)
            var boundStream: String = ""
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_epg_channel, parent, false))
        override fun getItemCount() = data.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = data[position]
            val isPlaying = isPlayingSame(item) && playingUrl.isNotBlank()
            holder.name.text = (if (isPlaying) "\u25b6 " else "") + item.name
            holder.number.text = (position + 1).toString()
            val q = when {
                Regex("(?i)(4k|uhd|2160)").containsMatchIn(item.name) -> "4K"
                Regex("(?i)(fhd|1080)").containsMatchIn(item.name) -> "FHD"
                Regex("(?i)(\\bhd\\b|720)").containsMatchIn(item.name) -> "HD"
                else -> ""
            }
            holder.hd.text = q
            holder.hd.visibility = if (q.isBlank()) View.GONE else View.VISIBLE
            holder.nowBadge.visibility = View.GONE
            holder.logo.load(item.logo) { crossfade(false); placeholder(R.drawable.bg_tile); error(R.drawable.ic_live_tv) }
            holder.progRow.removeAllViews()
            holder.boundStream = item.streamId ?: item.name
            holder.v.setOnClickListener { playChannel(item) }
            holder.v.setOnFocusChangeListener { _, hasFocus ->
                holder.v.animate().scaleX(if (hasFocus) 1.01f else 1f).scaleY(if (hasFocus) 1.01f else 1f).setDuration(80).start()
                holder.v.translationZ = if (hasFocus) 10f else 0f
            }
            // v392 : la chaine en lecture est la cible de focus par defaut du RecyclerView
            // -> quand on redescend depuis le lecteur reduit, on tombe sur la bonne chaine.
            holder.v.isFocusable = true
            holder.v.isFocusableInTouchMode = false
            val pl = Session.current ?: return
            val key = holder.boundStream
            lifecycleScope.launch {
                val epg = epgFor(pl, item)
                if (holder.boundStream != key) return@launch
                holder.nowBadge.visibility = if (epg.any { it.nowPlaying }) View.VISIBLE else View.GONE
                renderProg(holder.progRow, epg)
                if (holder.v.hasFocus() || position == 0) updateHero(item, epg)
            }
        }
        private fun renderProg(row: LinearLayout, epg: List<EpgEntry>) {
            row.removeAllViews()
            val ctx = row.context
            val dens = resources.displayMetrics.density
            fun px(v: Int) = (v * dens).toInt()
            if (epg.isEmpty()) {
                row.addView(TextView(ctx).apply {
                    text = "Pas de guide"
                    setTextColor(ContextCompat.getColor(ctx, R.color.muted))
                    textSize = 12f
                    setPadding(px(10), px(6), px(10), px(6))
                })
                return
            }
            for (e in epg.take(6)) {
                val cell = TextView(ctx).apply {
                    text = (if (e.time.isNotBlank()) e.time + "  " else "") + e.title
                    setTextColor(ContextCompat.getColor(ctx, if (e.nowPlaying) R.color.text else R.color.muted))
                    textSize = 12f
                    maxLines = 1
                    setPadding(px(10), px(6), px(10), px(6))
                    setBackgroundResource(if (e.nowPlaying) R.drawable.bg_epg_now else R.drawable.bg_epg_cell)
                }
                val lp = LinearLayout.LayoutParams(px(150), LinearLayout.LayoutParams.MATCH_PARENT)
                lp.marginEnd = px(6)
                cell.layoutParams = lp
                row.addView(cell)
            }
        }
    }
}
