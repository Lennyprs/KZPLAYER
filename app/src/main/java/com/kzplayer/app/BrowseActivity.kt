package com.kzplayer.app

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.widget.ImageView
import coil.load
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer

class BrowseActivity : BaseActivity() {
    private lateinit var kind: String
    // v341 : passe a true quand aucune chaine Stalker n'est marquee catch-up en mode replay.
    private var replayShowAll = false
    private var categories: List<Category> = emptyList()
    private var items: List<Item> = emptyList()
    private var filtered: List<Item> = emptyList()
    private var selectedCat: String = ""
    private var sortMode: Int = 0
    private var loadingAllForSearch: Boolean = false
    private var searchJob: Job? = null
    private var multiMode: Boolean = false
    // v359 : separateur utilise pour retenir la liste d origine d une categorie
    // quand toutes les listes sont affichees en meme temps.
    private val catSep = "@@"
    // v363 : mode toutes les listes = menu deroulant serveur > categories.
    // Les categories d un serveur ne sont chargees qu au moment ou on le deplie,
    // donc l ouverture de l ecran est immediate.
    private var srvOpen: String = ""
    private val srvCatCache = HashMap<String, List<Category>>()
    // v414 : categories COMPLETES par serveur pour le bouton Categories en multiliste.
    // srvCatCache contient la version deja filtree pour le menu de gauche ; ce cache-ci
    // conserve toutes les categories afin de pouvoir les afficher/masquer a nouveau.
    private val srvManageCatCache = HashMap<String, List<Category>>()
    private var allKind: String = "live"
    private var voicePlay: String = ""
    private var voiceTriedPlay = false

    private lateinit var catRv: RecyclerView
    private lateinit var itemRv: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var msgTv: TextView
    private lateinit var searchEt: EditText
    private lateinit var sortBtn: TextView
    private lateinit var viewBtn: TextView
    private lateinit var glm: GridLayoutManager
    private var liveListMode: Boolean = false
    private var catAdapter: CatAdapter? = null
    private var itemAdapter: ItemAdapter? = null
    private var lastItemFocusPos: Int = 0
    private var focusItemsAfterLoad: Boolean = false
    private var ownPlaylistId: String = ""
    private var searchEpoch = 0
    private var lastRealCategories: List<Category> = emptyList()
    private var lastBaseCategories: List<Category> = emptyList()
    private var didWhitelistRefresh = false
    private var didWarmSearch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browse)
        kind = intent.getStringExtra("kind") ?: "live"
        findViewById<TextView>(R.id.titleTv).text = Session.browseTitle

        catRv = findViewById(R.id.catRv)
        itemRv = findViewById(R.id.itemRv)
        progress = findViewById(R.id.progress)
        msgTv = findViewById(R.id.msgTv)
        searchEt = findViewById(R.id.searchEt)
        sortBtn = findViewById(R.id.sortBtn)
        viewBtn = findViewById(R.id.viewBtn)
        updateSortLabel()
        updateViewLabel()
        viewBtn.visibility = if (kind == "live") View.VISIBLE else View.GONE
        viewBtn.setOnClickListener {
            liveListMode = !liveListMode
            updateViewLabel()
            applyLayoutMode()
            itemAdapter?.notifyDataSetChanged()
        }
        sortBtn.setOnClickListener {
            sortMode = (sortMode + 1) % 3
            updateSortLabel()
            if (multiMode) { filtered = applySort(filtered); itemAdapter?.submit(filtered) }
            else applyFilter()
        }

        val catBtn = findViewById<TextView>(R.id.catBtn)
        catBtn.visibility = if (kind == "favorites") View.GONE else View.VISIBLE
        catBtn.setOnClickListener { showManageCategoriesDialog() }
        // v391 : curseur bien visible sur les boutons Tri / Vue / Categories.
        FocusFx.apply(sortBtn, viewBtn, catBtn)

        catRv.layoutManager = LinearLayoutManager(this)
        catRv.setHasFixedSize(true)
        catRv.itemAnimator = null
        glm = GridLayoutManager(this, computeSpan())
        glm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (filtered.getOrNull(position)?.kind == "header") glm.spanCount else 1
            }
        }
        itemRv.layoutManager = glm
        itemRv.setHasFixedSize(true)
        itemRv.itemAnimator = null
        itemRv.setItemViewCacheSize(24)
        itemAdapter = ItemAdapter(filtered) { openItem(it) }
        itemRv.adapter = itemAdapter

        searchEt.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val q = searchEt.text.toString().trim()
                // Films/Series : la recherche classique cherche sur TOUS les serveurs.
                if ((kind == "movie" || kind == "series") && q.length >= 2) {
                    runMultiServerSearch(q)
                } else {
                    multiMode = false
                    searchJob?.cancel()
                    ensureAllLoadedForSearch()
                    applyFilter()
                }
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        loadCategories()

        // Commande vocale : "voicePlay" = lancer directement une chaine (traite apres le
        // chargement des categories, cf. maybeStartVoicePlay) ; "voiceQuery" = pre-remplir la recherche.
        voicePlay = intent.getStringExtra("voicePlay")?.trim().orEmpty()
        if (voicePlay.isBlank() || kind != "live") {
            intent.getStringExtra("voiceQuery")?.takeIf { it.isNotBlank() }?.let { q ->
                searchEt.setText(q)
                searchEt.setSelection(q.length)
            }
        }
    }

    private fun loadCategories() {
        // v375 : plus jamais "Chargement des serveurs...". Si la session est vide
        // (process relance par Android), on la restaure instantanement depuis le
        // cache local, sans reseau et sans ecran d'attente.
        if (Session.current == null && Session.playlists.isEmpty()) SessionCache.restore(this)
        val existing = Session.current ?: Session.playlists.firstOrNull()?.also { Session.current = it }
        if (existing == null) {
            // Session vide (appli relancee / process tue / retour tardif) : on recharge licence +
            // serveurs puis on reessaie, au lieu de laisser un ecran sans aucune categorie.
            setLoading(true)
            msgTv.text = ""
            lifecycleScope.launch {
                try {
                    val res = Api.checkLicense(
                        DeviceIdentity.stableId(this@BrowseActivity),
                        DeviceIdentity.licenseCode(this@BrowseActivity),
                        android.os.Build.MODEL ?: "Android TV", "1.0"
                    )
                    if (res.ok && res.active) {
                        Session.playlists = LocalPlaylists.merge(res.playlists)
                        Session.expiration = res.expiration
                        Session.current = Session.playlists.firstOrNull()
                        SessionCache.save(this@BrowseActivity)
                    }
                } catch (e: Exception) {}
                if (Session.current != null) loadCategories()
                else { setLoading(false); msgTv.text = "Serveurs indisponibles. Reviens a l'accueil puis reessaie." }
            }
            return
        }
        val pl = existing
        if (ownPlaylistId.isBlank()) ownPlaylistId = pl.id
        val realKind = if (kind == "replay") "live" else kind
        // v359 : mode "toutes les listes en meme temps" (facon TiviMate).
        if (MultiListPref.isAll(this) && Session.playlists.size > 1 &&
            kind != "favorites" && !multiMode) {
            loadCategoriesAllLists(realKind)
            return
        }
        setLoading(true)
        msgTv.text = ""
        lifecycleScope.launch {
            try {
                if (kind == "favorites") {
                    categories = listOf(
                        Category("__fav_movie__", "Films favoris"),
                        Category("__fav_series__", "S\u00e9ries favorites"),
                        Category("__fav_live__", "Cha\u00eenes favorites")
                    )
                    bindCategories()
                    setLoading(false)
                    selectCategory(categories[0])
                    return@launch
                }
                when (pl.type) {
                    "m3u" -> {
                        categories = prepareCategories(Api.m3uCategories(pl, realKind), pl)
                        bindCategories()
                        if (categories.size > 1) {
                            setLoading(false)
                            msgTv.text = if (kind == "replay") "Choisis une cat\u00e9gorie replay \u00e0 gauche." else "Choisis une categorie a gauche."
                            selectCategory(categories[0])
                        } else if (categories.isNotEmpty()) {
                            selectCategory(categories[0])
                        } else {
                            setLoading(false)
                            msgTv.text = "Aucun contenu trouve."
                        }
                    }
                    "stalker" -> {
                        categories = prepareCategories(Api.stalkerCategories(pl, realKind), pl)
                        bindCategories()
                        setLoading(false)
                        // v356 : le journal technique n est visible que pour un administrateur.
                        msgTv.text = if (categories.size <= 1 && Api.lastStalkerLog.isNotBlank()) {
                            if (AdminMode.diagEnabled(this@BrowseActivity))
                                "Aucune categorie Stalker.\n\n${Api.lastStalkerLog}"
                            else
                                "Aucune cat\u00e9gorie disponible sur ce serveur."
                        } else {
                            if (kind == "replay") "Choisis une cat\u00e9gorie replay \u00e0 gauche." else "Choisis une categorie a gauche."
                        }
                    }
                    else -> {
                        categories = prepareCategories(Api.xtreamCategories(pl, realKind), pl)
                        bindCategories()
                        setLoading(false)
                        msgTv.text = if (kind == "replay") "Choisis une cat\u00e9gorie replay \u00e0 gauche." else "Choisis une categorie a gauche."
                    }
                }
                maybeStartVoicePlay()
                autoSyncWhitelist(pl.id)
                // Prechauffe la recherche multi-serveurs en arriere-plan (Films/Series) pour
                // des resultats quasi instantanes des que l'utilisateur commence a taper.
                maybeWarmSearch()
            } catch (e: Exception) {
                msgTv.text = "Erreur de chargement : ${e.message}"
                setLoading(false)
            }
        }
    }

    // v359 : fusionne les categories de TOUTES les listes actives. L identifiant de
    // chaque categorie devient "<idCategorie>@@<idListe>" pour savoir sur quel serveur
    // aller chercher le contenu au moment du clic. Les listes sont ajoutees au fur et a
    // mesure : l ecran est utilisable des la premiere liste chargee, et un serveur lent
    // ou en panne ne bloque jamais les autres.
    private fun loadCategoriesAllLists(realKind: String) {
        allKind = realKind
        setLoading(false)
        categories = serverRows()
        bindCategories()
        msgTv.text = "Choisis un serveur, puis une cat\u00e9gorie."
        maybeWarmSearch()
    }

    // Menu : un serveur par ligne. Le serveur ouvert affiche ses categories dessous.
    private fun serverRows(): List<Category> {
        val out = ArrayList<Category>()
        out.addAll(withSpecialCategories(emptyList()))
        for (pl in Session.playlists) {
            val open = srvOpen == pl.id
            val arrow = if (open) "\u25be  " else "\u25b8  "
            out.add(Category("__srv__" + pl.id, arrow + pl.nom.uppercase()))
            if (open) {
                for (c in srvCatCache[pl.id] ?: emptyList()) {
                    out.add(Category(c.id + catSep + pl.id, "      " + c.name))
                }
            }
        }
        return out
    }

    // Clic sur un serveur : on deplie (chargement de ses categories une seule fois) ou on replie.
    private fun toggleServer(plId: String) {
        if (srvOpen == plId) {
            srvOpen = ""
            categories = serverRows()
            bindCategories()
            return
        }
        srvOpen = plId
        val pl = Session.playlists.firstOrNull { it.id == plId } ?: return
        Session.current = pl
        if (srvCatCache.containsKey(plId)) {
            categories = serverRows()
            bindCategories()
            return
        }
        setLoading(true)
        msgTv.text = "Chargement de " + pl.nom + "..."
        categories = serverRows()
        bindCategories()
        lifecycleScope.launch {
            val base = try {
                kotlinx.coroutines.withTimeoutOrNull(20000L) {
                    when (pl.type) {
                        "m3u" -> Api.m3uCategories(pl, allKind)
                        "stalker" -> Api.stalkerCategories(pl, allKind)
                        else -> Api.xtreamCategories(pl, allKind)
                    }
                } ?: emptyList()
            } catch (e: Exception) { emptyList<Category>() }
            CategorySync.report(this@BrowseActivity, pl, allKind, base)
            val visible = filterHiddenCategories(base, pl).filter { !it.id.startsWith("__") }
            srvCatCache[plId] = visible
            if (srvOpen != plId) return@launch
            setLoading(false)
            categories = serverRows()
            bindCategories()
            msgTv.text = if (visible.isEmpty()) "Aucune cat\u00e9gorie sur " + pl.nom + "." else ""
        }
    }

    // Applique automatiquement les listes "a afficher" configurees sur le PANEL, sans avoir a
    // appuyer sur Recharger : on relit la licence en arriere-plan puis on re-filtre le menu
    // (sans re-telecharger les categories). Une seule fois par ouverture d'ecran.
    private fun autoSyncWhitelist(plId: String) {
        if (didWhitelistRefresh) return
        didWhitelistRefresh = true
        lifecycleScope.launch {
            val res = try {
                Api.checkLicense(
                    DeviceIdentity.stableId(this@BrowseActivity),
                    DeviceIdentity.licenseCode(this@BrowseActivity),
                    android.os.Build.MODEL ?: "Android TV", "1.0"
                )
            } catch (e: Exception) { null }
            if (res != null && res.ok && res.active && res.playlists.isNotEmpty()) {
                val cur = res.playlists.firstOrNull { it.id == plId }
                if (cur != null) {
                    Session.playlists = LocalPlaylists.merge(res.playlists)
                    if (Session.current?.id == plId) Session.current = cur
                    if (!multiMode && !MultiListPref.isAll(this@BrowseActivity) &&
                        Session.current?.id == plId && lastBaseCategories.isNotEmpty()) {
                        categories = filterHiddenCategories(withSpecialCategories(lastBaseCategories), cur)
                        bindCategories()
                    }
                }
            }
        }
    }

    private fun withSpecialCategories(base: List<Category>): List<Category> {
        if (kind == "movie" || kind == "series") {
            return listOf(Category("__favorites__", "Favoris"), Category("__recent__", "Vu r\u00e9cemment")) + base
        }
        if (kind == "live") return listOf(Category("__favorites__", "Favoris")) + base
        return base
    }

    // Prepare la liste affichee a gauche : memorise les vraies categories (pour le dialogue de
    // gestion), ajoute les entrees speciales, puis retire les categories masquees.
    private fun prepareCategories(base: List<Category>, pl: Playlist): List<Category> {
        lastRealCategories = base.filter { !it.id.startsWith("__") }
        lastBaseCategories = base
        // On transmet la liste des categories de ce serveur au backend pour que le panel puisse
        // les afficher en cases a cocher (best-effort, sans bloquer l'UI).
        CategorySync.report(this, pl, kind, base)
        return filterHiddenCategories(withSpecialCategories(base), pl)
    }

    // Retire les categories masquees : localement (choix de l'utilisateur dans l'app) + celles
    // imposees par le panel (champ hidden_categories). Ne touche jamais aux entrees speciales
    // (Favoris, Vu recemment, Tout).
    // Liste blanche EFFECTIVE de cette section : si l'utilisateur a configure dans l'app on prend
    // ce choix (source la plus recente), sinon celui du panel. UNE seule liste, pas de combinaison.
    private fun effectiveShown(pl: Playlist): Set<String> {
        if (ShownCategories.has(this, pl.id, kind)) return ShownCategories.shownNames(this, pl.id, kind)
        val realK = if (kind == "replay") "live" else kind
        return (pl.shownByKind[realK] ?: emptyList()).map { it.lowercase().trim() }.filter { it.isNotEmpty() }.toSet()
    }

    private fun filterHiddenCategories(list: List<Category>, pl: Playlist): List<Category> {
        // STRICT : si une liste "a afficher" existe, on n'affiche QUE ces categories.
        // Liste vide = aucune restriction => tout est affiche (defaut).
        val shown = effectiveShown(pl)
        if (shown.isEmpty()) return list
        return list.filter { c ->
            if (c.id.startsWith("__")) return@filter true
            c.name.lowercase().trim() in shown
        }
    }

    // Dialogue de gestion : coche = categorie affichee, decoche = masquee. Persiste par serveur+section.
    // Selecteur 2 colonnes : gauche "Categories" (pool) / droite "A afficher" (liste blanche).
    // Clique une ligne pour la deplacer d'un cote a l'autre. Vide a droite = tout est affiche.
    private fun showManageCategoriesDialog() {
        val multi = MultiListPref.isAll(this) && Session.playlists.size > 1
        if (multi) {
            showManageCategoriesServerPicker()
            return
        }
        val pl = Session.current ?: return
        showManageCategoriesFor(pl, lastRealCategories)
    }

    // v414 : en multiliste, le premier ecran affiche les NOMS DES SERVEURS.
    // Le choix d un serveur ouvre ensuite exactement le meme gestionnaire que le mode simple.
    private fun showManageCategoriesServerPicker() {
        val lists = Session.playlists
        if (lists.isEmpty()) { msgTv.text = "Aucun serveur disponible."; return }
        val labels = lists.map { p ->
            val type = when (p.type) { "m3u" -> "M3U"; "stalker" -> "Stalker"; else -> "Xtream" }
            p.nom + "   (" + type + ")"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Choisir un serveur")
            .setItems(labels) { d, which ->
                d.dismiss()
                lists.getOrNull(which)?.let { loadManageCategoriesFor(it) }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // Charge toutes les categories du serveur choisi sans changer le serveur actif.
    // Le cache evite un nouvel appel reseau lorsqu on revient sur le meme serveur.
    private fun loadManageCategoriesFor(pl: Playlist) {
        val cached = srvManageCatCache[pl.id]
        if (cached != null) {
            showManageCategoriesFor(pl, cached)
            return
        }
        setLoading(true)
        msgTv.text = "Chargement des categories de " + pl.nom + "..."
        lifecycleScope.launch {
            val realKind = if (kind == "replay") "live" else kind
            val raw = try {
                kotlinx.coroutines.withTimeoutOrNull(20000L) {
                    when (pl.type) {
                        "m3u" -> Api.m3uCategories(pl, realKind)
                        "stalker" -> Api.stalkerCategories(pl, realKind)
                        else -> Api.xtreamCategories(pl, realKind)
                    }
                } ?: emptyList()
            } catch (_: Throwable) { emptyList<Category>() }
            val all = raw.filter { !it.id.startsWith("__") }
                .distinctBy { it.name.lowercase().trim() }
                .sortedBy { it.name.lowercase() }
            srvManageCatCache[pl.id] = all
            setLoading(false)
            msgTv.text = ""
            if (all.isEmpty()) {
                msgTv.text = "Aucune categorie disponible sur " + pl.nom + "."
                return@launch
            }
            CategorySync.report(this@BrowseActivity, pl, realKind, all)
            showManageCategoriesFor(pl, all)
        }
    }

    private fun showManageCategoriesFor(pl: Playlist, allInput: List<Category>) {
        val all = allInput.filter { !it.id.startsWith("__") }
        if (all.isEmpty()) { msgTv.text = "Aucune categorie a gerer sur " + pl.nom + "."; return }
        val shownNames = effectiveShown(pl)
        val left = java.util.ArrayList<Category>()
        val right = java.util.ArrayList<Category>()
        for (c in all) { if (shownNames.contains(c.name.lowercase().trim())) right.add(c) else left.add(c) }

        val dens = resources.displayMetrics.density
        fun px(v: Int) = (v * dens).toInt()
        val accent = KzColors.accent(this)
        val textCol = ContextCompat.getColor(this, R.color.text)
        val mutedCol = ContextCompat.getColor(this, R.color.muted)

        val leftBox = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        val rightBox = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        lateinit var render: () -> Unit
        fun makeRow(c: Category, inRight: Boolean): android.widget.TextView {
            return android.widget.TextView(this).apply {
                text = (if (inRight) "\u2212  " else "+  ") + c.name
                setTextColor(textCol)
                textSize = 14f
                setPadding(px(10), px(10), px(10), px(10))
                isFocusable = true; isClickable = true
                setBackgroundResource(R.drawable.bg_cat)
                setOnClickListener {
                    if (inRight) { right.remove(c); left.add(c) } else { left.remove(c); right.add(c) }
                    render()
                }
            }
        }
        render = {
            leftBox.removeAllViews(); rightBox.removeAllViews()
            if (left.isEmpty()) leftBox.addView(android.widget.TextView(this).apply { text = "(vide)"; setTextColor(mutedCol); setPadding(px(10), px(10), px(10), px(10)) })
            for (c in left) leftBox.addView(makeRow(c, false))
            if (right.isEmpty()) rightBox.addView(android.widget.TextView(this).apply { text = "(aucune \u2192 tout est affiche)"; setTextColor(mutedCol); setPadding(px(10), px(10), px(10), px(10)) })
            for (c in right) rightBox.addView(makeRow(c, true))
        }

        fun columnHeader(t: String) = android.widget.TextView(this).apply {
            text = t; setTextColor(accent); textSize = 13f; setTypeface(null, android.graphics.Typeface.BOLD); setPadding(px(10), px(4), px(10), px(6))
        }
        fun scrollCol(headerTxt: String, box: android.view.View): android.widget.LinearLayout {
            val col = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(columnHeader(headerTxt))
            val sc = android.widget.ScrollView(this)
            sc.layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, px(280))
            sc.addView(box)
            col.addView(sc)
            return col
        }

        val cols = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
        cols.addView(scrollCol("Cat\u00e9gories", leftBox))
        val spacer = android.view.View(this); spacer.layoutParams = android.widget.LinearLayout.LayoutParams(px(10), 1)
        cols.addView(spacer)
        cols.addView(scrollCol("\u00c0 afficher", rightBox))

        val toolRow = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL; setPadding(px(2), px(8), px(2), px(2)) }
        val btnAll = android.widget.Button(this).apply { text = "Tout afficher"; setOnClickListener { right.clear(); right.addAll(all); left.clear(); render() } }
        val btnNone = android.widget.Button(this).apply { text = "Tout masquer"; setOnClickListener { left.clear(); left.addAll(all); right.clear(); render() } }
        toolRow.addView(btnAll); toolRow.addView(btnNone)

        val root = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(px(14), px(8), px(14), px(4)) }
        val hint = android.widget.TextView(this).apply { text = "Clique une cat\u00e9gorie pour la d\u00e9placer. \u00ab \u00c0 afficher \u00bb vide = tout est affich\u00e9."; setTextColor(mutedCol); textSize = 12f; setPadding(px(4), 0, px(4), px(6)) }
        root.addView(hint)
        root.addView(cols)
        root.addView(toolRow)

        render()

        AlertDialog.Builder(this)
            .setTitle("Cat\u00e9gories - " + pl.nom)
            .setView(root)
            .setPositiveButton("Valider") { d, _ ->
                val chosen = right.map { it.name }
                ShownCategories.setShown(this, pl.id, kind, chosen)
                // On enregistre aussi cote panel pour que les deux restent synchronises.
                val lic = DeviceIdentity.licenseCode(this)
                val realK = if (kind == "replay") "live" else kind
                lifecycleScope.launch { Api.setShown(lic, pl.id, realK, chosen) }
                d.dismiss()
                if (MultiListPref.isAll(this) && Session.playlists.size > 1) {
                    // Refiltre immediatement le menu de ce serveur sans perdre les caches
                    // ni changer la liste active ou la categorie actuellement ouverte.
                    srvCatCache[pl.id] = filterHiddenCategories(all, pl)
                    categories = serverRows()
                    bindCategories()
                    msgTv.text = "Categories de " + pl.nom + " mises a jour."
                } else {
                    loadCategories()
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun bindCategories() {
        val adapter = catAdapter
        if (adapter == null) {
            catAdapter = CatAdapter(categories) { cat -> selectCategory(cat) }
            catRv.adapter = catAdapter
        } else {
            adapter.submit(categories)
        }
    }

    private fun selectCategory(cat: Category) {
        // v363 : ligne de serveur (mode toutes les listes) -> on deplie / replie ses categories.
        if (cat.id.startsWith("__srv__")) {
            selectedCat = cat.id
            toggleServer(cat.id.substring(7))
            return
        }
        val previousCat = selectedCat
        selectedCat = cat.id
        val previousIndex = categories.indexOfFirst { it.id == previousCat }
        val selectedIndex = categories.indexOfFirst { it.id == cat.id }
        if (previousIndex >= 0) catAdapter?.notifyItemChanged(previousIndex)
        if (selectedIndex >= 0 && selectedIndex != previousIndex) catAdapter?.notifyItemChanged(selectedIndex)
        // v359 : en mode multi-listes, la categorie porte l identifiant de sa liste.
        // On rebascule la liste active sur la bonne liste avant de charger le contenu,
        // ce qui garantit une lecture identique au mode une seule liste.
        val sepAt = cat.id.lastIndexOf(catSep)
        val catPl = if (sepAt > 0)
            Session.playlists.firstOrNull { it.id == cat.id.substring(sepAt + catSep.length) }
        else null
        val catId = if (sepAt > 0) cat.id.substring(0, sepAt) else cat.id
        if (catPl != null) Session.current = catPl
        val pl = catPl ?: Session.current ?: return
        if (cat.id == "__favorites__") {
            items = Favorites.forKind(this, kind)
            applyFilter()
            msgTv.text = if (items.isEmpty()) "Aucun favori pour cette section." else ""
            setLoading(false)
            return
        }
        if (cat.id.startsWith("__fav_")) {
            val favKind = when (cat.id) {
                "__fav_series__" -> "series"
                "__fav_live__" -> "live"
                else -> "movie"
            }
            items = Favorites.forKind(this, favKind)
            applyFilter()
            msgTv.text = if (items.isEmpty()) "Aucun favori." else ""
            setLoading(false)
            return
        }
        if (cat.id == "__recent__") {
            items = WatchHistory.recentItems(this, kind)
            applyFilter()
            msgTv.text = if (items.isEmpty()) "Aucun contenu vu r\u00e9cemment." else ""
            setLoading(false)
            return
        }
        val realKind = if (kind == "replay") "live" else kind
        when (pl.type) {
            "m3u" -> {
                setLoading(true)
                lifecycleScope.launch {
                    try { items = Api.m3uItems(pl, realKind, catId) }
                    catch (e: Exception) { msgTv.text = "Erreur : ${e.message}" }
                    if (kind == "replay") items = items.filter { it.catchup }
                    applyFilter()
                    msgTv.text = if (items.isEmpty()) "Aucun contenu trouve." else ""
                    setLoading(false)
                }
            }
            "stalker" -> { setLoading(true); lifecycleScope.launch { loadStalkerInto(catId) } }
            else -> {
                setLoading(true)
                lifecycleScope.launch {
                    try { items = Api.xtreamItems(pl, realKind, catId) }
                    catch (e: Exception) { msgTv.text = "Erreur : ${e.message}" }
                    if (kind == "replay") items = items.filter { it.catchup }
                    applyFilter()
                    msgTv.text = if (items.isEmpty()) "Aucun contenu trouve." else ""
                    setLoading(false)
                }
            }
        }
    }

    private suspend fun loadStalkerInto(categoryId: String) {
        val pl = Session.current ?: return
        items = emptyList()
        applyFilter()
        var firstShown = false
        val realKind = if (kind == "replay") "live" else kind
        // v341 : les portails Stalker ne signalent presque jamais le catch-up dans la liste
        // des chaines. On tente d'abord le filtre replay, puis on reaffiche tout si vide,
        // pour que le replay soit utilisable aussi en Stalker.
        val filterReplay = kind == "replay" && !replayShowAll
        Api.stalkerItemsPaged(pl, realKind, categoryId) { batch ->
            withContext(Dispatchers.Main) {
                val newItems = if (filterReplay) batch.filter { it.catchup } else batch
                items = items + newItems
                applyFilter()
                if (!firstShown) { setLoading(false); firstShown = true }
                msgTv.text = ""
            }
        }
        if (filterReplay && items.isEmpty()) {
            replayShowAll = true
            withContext(Dispatchers.Main) {
                msgTv.text = "Replay : toutes les chaines de la categorie sont affichees."
            }
            loadStalkerInto(categoryId)
            return
        }
        withContext(Dispatchers.Main) {
            setLoading(false)
            if (items.isEmpty()) msgTv.text = "Aucun contenu trouve."
        }
    }

    private fun applyFilter() {
        // Un chargement de categorie termine en retard ne doit pas remplacer
        // les resultats de la recherche multi-serveurs en cours.
        if (multiMode) return
        val q = cleanSearch(searchEt.text.toString())
        val searchHadFocus = searchEt.hasFocus()
        // La grille a-t-elle le focus AVANT la mise a jour ? Si oui et qu'il saute hors
        // de la grille apres (vers les categories) a cause d'un rafraichissement pendant
        // le chargement, on le remettra sur la meme tuile.
        val gridHadFocus = itemRv.hasFocus()
        val tokens = q.split(" ").filter { it.isNotBlank() }
        var list = if (tokens.isEmpty()) {
            items
        } else {
            items.filter { item ->
                if (item.kind == "header") return@filter false
                val haystack = cleanSearch(item.name + " " + item.description)
                tokens.all { token -> haystack.contains(token) }
            }
        }
        val hasHeaders = list.any { it.kind == "header" }
        if (!hasHeaders) {
            list = when (sortMode) {
                1 -> list.sortedBy { it.name.lowercase() }
                2 -> list.sortedByDescending { it.added }
                else -> list
            }
        }
        filtered = list
        itemAdapter?.submit(filtered)
        if (searchHadFocus) {
            // La saisie conserve son focus naturellement ; pas de callback qui le
            // reprendrait apres que l'utilisateur est descendu dans les resultats.
        } else if (focusItemsAfterLoad && filtered.isNotEmpty()) {
            focusItemsAfterLoad = false
            itemRv.post {
                itemRv.scrollToPosition(0)
                itemRv.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                    ?: itemRv.postDelayed({ itemRv.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }, 80)
            }
        } else if (gridHadFocus && filtered.isNotEmpty()) {
            // Garde anti-saut : si la liste a change pendant la navigation et que le focus
            // a quitte la grille (renvoye vers les categories), on le ramene sur la tuile.
            itemRv.post {
                if (!itemRv.hasFocus() && !searchEt.hasFocus() && !catRv.hasFocus()) {
                    val pos = lastItemFocusPos.coerceIn(0, (filtered.size - 1).coerceAtLeast(0))
                    itemRv.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
                        ?: itemRv.postDelayed({ itemRv.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() }, 60)
                }
            }
        }
        tryVoicePlay()
    }

    private fun applySort(list: List<Item>): List<Item> = when (sortMode) {
        1 -> list.sortedBy { it.name.lowercase() }
        2 -> list.sortedByDescending { it.added }
        else -> list
    }

    // Recherche multi-serveurs (Films/Series) : interroge tous les serveurs ajoutes,
    // fusionne les doublons et affiche chaque resultat avec le(s) serveur(s) ou il est.
    private fun runMultiServerSearch(q: String) {
        multiMode = true
        searchJob?.cancel()
        // Garde d'epoque : seule la DERNIERE recherche lancee a le droit de mettre a jour l'ecran.
        // Avant, une ancienne recherche (requete reseau encore en cours) continuait d'ecrire ses
        // resultats -> clignotement / "chargement en boucle" quand on relancait une recherche.
        val epoch = ++searchEpoch
        val playlists = Session.playlists
        if (playlists.isEmpty()) { msgTv.text = "Aucun serveur ajoute."; return }
        setLoading(true)
        msgTv.text = ""
        searchJob = lifecycleScope.launch(Dispatchers.IO) {
            delay(250) // anti-rebond pendant la frappe
            if (epoch != searchEpoch) return@launch
            try {
                Api.searchAllServers(playlists, q, kind) { done, total, merged ->
                    withContext(Dispatchers.Main) {
                        if (!multiMode || epoch != searchEpoch) return@withContext
                        filtered = applySort(merged)
                        itemAdapter?.submit(filtered)
                        if (merged.isNotEmpty()) {
                            // Resultats affiches immediatement, sans texte ni spinner au milieu.
                            setLoading(false)
                            msgTv.text = ""
                        } else if (done >= total) {
                            setLoading(false)
                            msgTv.text = "Aucun resultat pour \"$q\"."
                        } else {
                            msgTv.text = ""
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (multiMode && epoch == searchEpoch) {
                        msgTv.text = "Erreur : ${e.message}"
                        setLoading(false)
                    }
                }
            }
        }
    }

    // Precharge en tache de fond les catalogues de tous les serveurs (une seule fois par
    // ouverture d'ecran) pour accelerer la recherche multi-serveurs Films/Series.
    private fun maybeWarmSearch() {
        if (didWarmSearch) return
        if (kind != "movie" && kind != "series") return
        val pls = Session.playlists
        if (pls.size <= 1) return // un seul serveur : deja rapide, inutile de prechauffer
        didWarmSearch = true
        lifecycleScope.launch(Dispatchers.IO) {
            try { Api.prefetchCatalogs(pls, kind) } catch (e: Exception) {}
        }
    }

    private fun ensureAllLoadedForSearch() {
        val q = searchEt.text.toString().trim()
        if (q.isBlank()) return
        if (selectedCat == "__all__") return
        if (loadingAllForSearch) return
        val allCat = categories.firstOrNull { it.id == "__all__" } ?: return
        loadingAllForSearch = true
        msgTv.text = "Recherche dans tout le catalogue..."
        selectCategory(allCat)
        itemRv.postDelayed({ loadingAllForSearch = false }, 1200)
    }

    private fun cleanSearch(s: String): String {
        val noAccent = Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        return noAccent.lowercase()
            .replace("&", " and ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
    }

    private fun updateSortLabel() {
        sortBtn.text = when (sortMode) {
            1 -> "Tri : A-Z"
            2 -> "Tri : R\u00e9cents"
            else -> "Tri : D\u00e9faut"
        }
    }

    private fun updateViewLabel() {
        if (::viewBtn.isInitialized) viewBtn.text = if (liveListMode) "Vue : Liste" else "Vue : Grille"
    }

    private fun applyLayoutMode() {
        if (kind == "live" && liveListMode) {
            itemRv.layoutManager = LinearLayoutManager(this)
        } else {
            glm = GridLayoutManager(this, computeSpan())
            glm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return if (filtered.getOrNull(position)?.kind == "header") glm.spanCount else 1
                }
            }
            itemRv.layoutManager = glm
        }
    }

    private fun openItem(item: Item) {
        // Resultat multi-serveurs : on bascule d'abord sur le serveur ou l'item se trouve.
        if (item.ownerPlaylistId.isNotBlank() && item.ownerPlaylistId != Session.current?.id) {
            Session.playlists.firstOrNull { it.id == item.ownerPlaylistId }?.let { Session.current = it }
        }
        // v340 : en mode REPLAY, un clic sur une chaine ouvre la liste des programmes
        // deja diffuses (archive) au lieu de lancer le direct.
        if (kind == "replay" && (item.kind == "live" || item.kind == "channel")) {
            startActivity(
                Intent(this, ReplayProgramsActivity::class.java)
                    .putExtra("title", item.name)
                    .putExtra("logo", item.logo)
                    .putExtra("streamId", item.streamId ?: "")
                    .putExtra("cmd", item.cmd ?: "")
            )
            return
        }
        if (item.kind == "series") {
            Session.seriesItem = item
            startActivity(Intent(this, SeriesActivity::class.java))
            return
        }
        if (item.kind == "episode") {
            val pl = Session.current ?: return
            val direct = item.directUrl
            if (!direct.isNullOrBlank()) { play(direct, item.name, item.logo, "series"); return }
            val cmd = item.cmd
            if (cmd.isNullOrBlank()) { msgTv.text = "Flux indisponible pour cet episode."; return }
            setLoading(true)
            lifecycleScope.launch {
                val link = try { Api.stalkerLink(pl, cmd, "movie") } catch (e: Exception) { null }
                setLoading(false)
                if (!link.isNullOrBlank()) play(link, item.name, item.logo, "series") else msgTv.text = "Impossible d'obtenir le flux."
            }
            return
        }
        if (item.kind == "movie") {
            Session.detailItem = item
            startActivity(DetailActivity.intentFor(this, item))
            return
        }
        val plCur = Session.current
        if (plCur != null && plCur.type == "stalker") {
            val cmd = item.cmd
            if (cmd.isNullOrBlank()) { msgTv.text = "Flux indisponible pour cet element."; return }
            setLoading(true)
            lifecycleScope.launch {
                val link = try { Api.stalkerLink(plCur, cmd, item.kind) } catch (e: Exception) { null }
                setLoading(false)
                if (!link.isNullOrBlank()) {
                    if (item.kind == "live") previewLive(link, item.name, item.logo, item.streamId ?: "", item)
                    else play(link, item.name, item.logo, "movie")
                } else msgTv.text = "Impossible d'obtenir le flux."
            }
            return
        }
        val url = item.directUrl ?: return
        if (item.kind == "live") previewLive(url, item.name, item.logo, item.streamId ?: "", item)
        else play(url, item.name, item.logo, if (item.kind == "movie" || item.kind == "episode") "movie" else "live")
    }

    private fun previewLive(url: String, title: String, logo: String = "", streamId: String = "", item: Item? = null) {
        val chans = filtered.filter { it.kind == "live" || it.kind == "channel" }
        Session.liveChannels = chans
        // v353 : on retient la categorie affichee et la position exacte de la chaine.
        ZapList.set(chans, item)
        startActivity(
            Intent(this, LivePreviewActivity::class.java)
                .putExtra("url", url)
                .putExtra("title", title)
                .putExtra("logo", logo)
                .putExtra("streamId", streamId)
        )
    }

    private fun play(url: String, title: String, logo: String = "", historyKind: String = "live") {
        if (historyKind == "movie" || historyKind == "series") {
            WatchHistory.touch(this, url, title, logo, historyKind)
        }
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra("url", url)
                .putExtra("title", title)
                .putExtra("logo", logo)
                .putExtra("historyKind", historyKind)
                .putExtra("mode", if (historyKind == "movie" || historyKind == "series") "vod" else "live")
        )
    }

    // Commande vocale "Mets ..." : on charge toutes les chaines puis on lance la correspondance.
    private fun maybeStartVoicePlay() {
        if (voicePlay.isBlank() || kind != "live") return
        val allCat = categories.firstOrNull { it.id == "__all__" }
            ?: categories.firstOrNull { !it.id.startsWith("__") } ?: return
        msgTv.text = "Recherche de la chaine \u00ab ${voicePlay.split("\n").first()} \u00bb..."
        selectCategory(allCat)
        // Repli : si la chaine reste introuvable apres chargement, on affiche la liste filtree.
        itemRv.postDelayed({
            if (voicePlay.isNotBlank() && !voiceTriedPlay) {
                val q = voicePlay.split("\n").first(); voicePlay = ""
                searchEt.setText(q); searchEt.setSelection(q.length)
                msgTv.text = "Chaine introuvable : voici les resultats."
            }
        }, 6000)
    }

    // Lance directement la chaine dont le nom correspond a l'ordre vocal.
    private fun tryVoicePlay() {
        if (voicePlay.isBlank() || voiceTriedPlay || kind != "live") return
        // Plusieurs hypotheses de reconnaissance (separees par des sauts de ligne) : on essaie chacune.
        val cands = voicePlay.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        val match = cands.firstNotNullOfOrNull { findChannelMatch(it, items) } ?: return
        voiceTriedPlay = true
        voicePlay = ""
        openItem(match)
    }

    // Colle un sigle court a un nombre ("t 18" -> "t18", "m 6" -> "m6") pour matcher les chaines
    // alphanumeriques que la reconnaissance vocale separe (T18, M6, W9, C8, TF1...).
    private fun glueVoice(s: String): String =
        Regex("\\b([a-z]{1,3}) (\\d+)").replace(s) { it.groupValues[1] + it.groupValues[2] }

    private fun findChannelMatch(query: String, list: List<Item>): Item? {
        val q = cleanSearch(query).trim()
        if (q.isBlank()) return null
        val g = glueVoice(q)
        val chans = list.filter { it.kind == "live" }
        val tokens = q.split(" ").filter { it.isNotBlank() }
        return chans.firstOrNull { cleanSearch(it.name).trim() == q || glueVoice(cleanSearch(it.name).trim()) == g }
            ?: chans.firstOrNull { cleanSearch(it.name).trim().startsWith(q) || glueVoice(cleanSearch(it.name).trim()).startsWith(g) }
            ?: chans.firstOrNull { cleanSearch(it.name).contains(q) }
            ?: chans.firstOrNull { c -> tokens.isNotEmpty() && tokens.all { cleanSearch(c.name).contains(it) } }
    }

    private fun computeSpan(): Int {
        val m = resources.displayMetrics
        val totalDp = m.widthPixels / m.density
        val content = (totalDp - 230f).coerceAtLeast(220f)
        val target = if (kind == "movie" || kind == "series") 120f else 145f
        return (content / target).toInt().coerceIn(2, 8)
    }

    private fun setLoading(b: Boolean) { progress.visibility = if (b) View.VISIBLE else View.GONE }

    private fun keepFocusOnItems() {
        // Ne jamais voler le focus a la barre de recherche pendant la saisie.
        if (searchEt.hasFocus()) return
        if (filtered.isEmpty()) return
        val current = currentFocus
        if (current != null && catRv.findContainingViewHolder(current) != null) return
        if (current != null && searchEt.hasFocus()) return
        val alreadyOnItem = current != null && itemRv.findContainingViewHolder(current) != null
        if (alreadyOnItem) return
        itemRv.post {
            val pos = lastItemFocusPos.coerceIn(0, (filtered.size - 1).coerceAtLeast(0))
            itemRv.scrollToPosition(pos)
            itemRv.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
                ?: itemRv.postDelayed({ itemRv.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() }, 80)
        }
    }

    override fun onResume() {
        super.onResume()
        if (filtered.isNotEmpty() && currentFocus == null) keepFocusOnItems()
    }

    inner class CatAdapter(initial: List<Category>, val onClick: (Category) -> Unit) :
        RecyclerView.Adapter<CatAdapter.VH>() {
        private val data = ArrayList<Category>(initial)
        init { setHasStableIds(true) }
        override fun getItemId(position: Int): Long = data[position].id.fold(1125899906842597L) { h, c -> h * 31L + c.code }
        fun submit(list: List<Category>) = ListUpdates.submit(this, data, list) { it.id }
        inner class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_category, parent, false) as TextView
            return VH(tv)
        }
        override fun getItemCount() = data.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val c = data[position]
            holder.tv.text = c.name
            val sel = c.id == selectedCat
            holder.tv.isSelected = sel
            holder.tv.setTextColor(ContextCompat.getColor(holder.tv.context, if (sel) R.color.text else R.color.muted))
            holder.tv.setOnClickListener {
                // Ouvrir un serveur ne doit pas programmer un saut vers la grille.
                focusItemsAfterLoad = !c.id.startsWith("__srv__")
                onClick(c)
            }
        }
    }

    private fun toggleLiveFavorite(item: Item) {
        if (item.kind != "live") return
        val added = Favorites.toggle(this, item)
        Toast.makeText(this, if (added) "Ajout\u00e9 aux favoris" else "Retir\u00e9 des favoris", Toast.LENGTH_SHORT).show()
        if (selectedCat == "__favorites__") selectCategory(Category("__favorites__", "Favoris"))
        else itemAdapter?.notifyDataSetChanged()
    }

    inner class ItemAdapter(initialData: List<Item>, val onClick: (Item) -> Unit) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val data = ArrayList<Item>(initialData)

        fun submit(newData: List<Item>) {
            ListUpdates.submit(this, data, newData, ListUpdates::itemKey)
        }
        inner class TileVH(val v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.nameTv)
            val poster: ImageView = v.findViewById(R.id.posterIv)
            val progressWrap: View = v.findViewById(R.id.progressWrap)
            val progressFill: View = v.findViewById(R.id.progressFill)
            val serverChip: TextView = v.findViewById(R.id.serverChip)
            val favBtn: TextView = v.findViewById(R.id.favBtn)
            // Nom de l'item actuellement affiche : garde anti-recyclage pour les affiches TMDB.
            var boundName: String = ""
        }
        inner class ListVH(val v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.nameTv)
            val sub: TextView = v.findViewById(R.id.subTv)
            val logo: ImageView = v.findViewById(R.id.logoIv)
            val favBtn: TextView = v.findViewById(R.id.favBtn)
        }
        inner class HeaderVH(val v: View) : RecyclerView.ViewHolder(v) {
            val tv: TextView = v.findViewById(R.id.headerTv)
        }
        override fun getItemViewType(position: Int): Int = when {
            data[position].kind == "header" -> 1
            kind == "live" && liveListMode -> 2
            else -> 0
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return when (viewType) {
                1 -> HeaderVH(inf.inflate(R.layout.item_season_header, parent, false))
                2 -> ListVH(inf.inflate(R.layout.item_channel_list, parent, false))
                else -> TileVH(inf.inflate(R.layout.item_tile, parent, false))
            }
        }
        override fun getItemCount() = data.size
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = data[position]
            if (holder is HeaderVH) {
                holder.tv.text = item.name
                return
            }
            if (holder is ListVH) {
                holder.name.text = item.name
                holder.sub.text = "OK : aper\u00e7u + EPG"
                holder.logo.load(item.logo) {
                    crossfade(false)
                    placeholder(R.drawable.bg_tile)
                    error(R.drawable.ic_live_tv)
                }
                holder.favBtn.text = if (Favorites.isFavorite(this@BrowseActivity, item)) "\u2605" else "\u2606"
                holder.favBtn.setOnClickListener { v ->
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    toggleLiveFavorite(item)
                }
                holder.v.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) lastItemFocusPos = holder.bindingAdapterPosition.coerceAtLeast(0)
                    holder.v.animate().scaleX(if (hasFocus) 1.025f else 1.0f)
                        .scaleY(if (hasFocus) 1.025f else 1.0f)
                        .setDuration(80)
                        .start()
                    holder.v.translationZ = if (hasFocus) 12f else 0f
                    holder.name.setTextColor(if (hasFocus) KzColors.accent(holder.name.context) else ContextCompat.getColor(holder.name.context, R.color.text))
                }
                holder.v.setOnLongClickListener {
                    lastItemFocusPos = holder.bindingAdapterPosition.coerceAtLeast(0)
                    toggleLiveFavorite(item)
                    true
                }
                holder.v.setOnClickListener {
                    lastItemFocusPos = holder.bindingAdapterPosition.coerceAtLeast(0)
                    onClick(item)
                }
                return
            }
            holder as TileVH
            holder.name.text = item.name
            holder.boundName = item.name
            val isPoster = item.kind == "movie" || item.kind == "series" || item.kind == "season" || item.kind == "episode"
            val h = resources.getDimensionPixelSize(if (isPoster) R.dimen.tile_poster_h else R.dimen.tile_logo_h)
            val lp = holder.poster.layoutParams
            lp.height = h
            holder.poster.layoutParams = lp
            holder.poster.scaleType = if (isPoster) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
            val fallback = if (isPoster) R.drawable.ic_movie else R.drawable.ic_live_tv
            // Repli TMDB EN COMPLEMENT des codes : on tente une affiche TMDB si le code n'a
            // PAS d'image OU si l'image du code est CASSEE (404/timeout). C'etait le bug :
            // beaucoup de films ont une URL d'affiche invalide, donc logo n'est pas vide et
            // l'ancien repli (logo vide uniquement) ne se declenchait jamais.
            // Garde anti-recyclage stricte (boundName) pour ne jamais ecraser une autre tuile.
            val tmdbFallback = tmdbFallback@{
                if (!((item.kind == "movie" || item.kind == "series") && Tmdb.enabled())) return@tmdbFallback
                val target = item.name
                val series = item.kind == "series"
                lifecycleScope.launch {
                    val url = Tmdb.posterFor(target, series)
                    if (url.isNotBlank() && holder.boundName == target) {
                        holder.poster.load(url) { crossfade(false); placeholder(R.drawable.bg_tile); error(fallback) }
                    }
                }
                Unit
            }
            if (item.logo.isBlank()) {
                holder.poster.setImageResource(fallback)
                tmdbFallback()
            } else {
                holder.poster.load(item.logo) {
                    // Pas de crossfade dans les grandes grilles TV : \u00e7a cr\u00e9e de la latence et des saccades.
                    crossfade(false)
                    placeholder(R.drawable.bg_tile)
                    error(fallback)
                    // Image du code injoignable -> on bascule automatiquement sur TMDB.
                    listener(onError = { _, _ -> tmdbFallback() })
                }
            }
            holder.favBtn.text = if (Favorites.isFavorite(this@BrowseActivity, item)) "\u2605" else "\u2606"
            holder.favBtn.visibility = if (item.kind == "live") View.VISIBLE else View.GONE
            holder.favBtn.setOnClickListener { v ->
                v.parent?.requestDisallowInterceptTouchEvent(true)
                toggleLiveFavorite(item)
            }
            // Petite case serveur (recherche multi-serveurs).
            if (item.serverLabel.isNotBlank()) {
                holder.serverChip.visibility = View.VISIBLE
                holder.serverChip.text = item.serverLabel
            } else {
                holder.serverChip.visibility = View.GONE
            }
            val pct = when {
                item.directUrl != null -> WatchHistory.progressPercent(holder.v.context, item.directUrl)
                item.kind == "series" -> WatchHistory.progressForSeries(holder.v.context, item.name)
                else -> 0
            }
            if (pct > 0) {
                holder.progressWrap.visibility = View.VISIBLE
                holder.progressWrap.post {
                    val lp2 = holder.progressFill.layoutParams
                    lp2.width = (holder.progressWrap.width * (pct / 100f)).toInt().coerceAtLeast(3)
                    holder.progressFill.layoutParams = lp2
                }
            } else {
                holder.progressWrap.visibility = View.GONE
            }
            holder.v.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) lastItemFocusPos = holder.bindingAdapterPosition.coerceAtLeast(0)
                // Curseur TV tres visible : zoom + titre rouge + elevation.
                holder.v.animate().scaleX(if (hasFocus) 1.04f else 1.0f)
                    .scaleY(if (hasFocus) 1.04f else 1.0f)
                    .setDuration(90)
                    .start()
                holder.v.translationZ = if (hasFocus) 16f else 0f
                holder.name.setTextColor(
                    if (hasFocus) KzColors.accent(holder.name.context) else ContextCompat.getColor(holder.name.context, R.color.text)
                )
            }
            holder.v.setOnLongClickListener {
                lastItemFocusPos = holder.bindingAdapterPosition.coerceAtLeast(0)
                toggleLiveFavorite(item)
                true
            }
            holder.v.setOnClickListener {
                lastItemFocusPos = holder.bindingAdapterPosition.coerceAtLeast(0)
                onClick(item)
            }
        }
    }
}
