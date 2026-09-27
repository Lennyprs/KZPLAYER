package com.kzplayer.app

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
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

// Ecran catalogue NewTivi (Films / Series) : categories a gauche, grand visuel en haut,
// grille d'affiches en dessous. Reutilise 100% la logique de chargement existante (Api).
abstract class NtCatalogActivity : NtBase() {
    companion object {
        // Regex precompilees (partagees par tous les binds) : eviter d'allouer 3 Regex
        // par tuile a chaque scroll -> reduit fortement le GC sur les grosses grilles.
        private val R_4K = Regex("(?i)(4k|uhd|2160)")
        private val R_FHD = Regex("(?i)(fhd|1080)")
        private val R_HD = Regex("(?i)(\\bhd\\b|720)")
    }

    abstract val kind: String
    abstract val navTag: String
    abstract val screenTitle: String
    abstract fun openItem(item: Item)

    // Liste actuellement affichee (utile aux sous-ecrans, ex. zapping TV).
    protected fun visibleItems(): List<Item> = filtered

    private lateinit var catRv: RecyclerView
    private lateinit var itemRv: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var msgTv: TextView
    private lateinit var searchEt: EditText
    private lateinit var heroImg: ImageView
    private lateinit var heroTitle: TextView
    private lateinit var heroMeta: TextView
    private lateinit var heroDesc: TextView

    private var categories: List<Category> = emptyList()
    private var items: List<Item> = emptyList()
    private var filtered: List<Item> = emptyList()
    private var selectedCat: String = ""
    private var catAdapter: CatAdapter? = null
    private var itemAdapter: TileAdapter? = null
    private lateinit var glm: GridLayoutManager
    // Recherche multi-serveurs (comme le Classique) : cherche sur TOUS les serveurs ajoutes.
    private var multiMode: Boolean = false
    private var searchEpoch: Int = 0
    private var searchJob: Job? = null
    private var voiceQuery: String = ""
    // Empeche le TextWatcher de relancer une recherche quand on vide le champ nous-memes.
    private var ignoreSearchChange: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_new_catalog)
        NavHelper.setup(this, navTag)
        findViewById<TextView>(R.id.titleTv).text = screenTitle
        catRv = findViewById(R.id.catRv)
        itemRv = findViewById(R.id.itemRv)
        progress = findViewById(R.id.progress)
        msgTv = findViewById(R.id.msgTv)
        searchEt = findViewById(R.id.searchEt)
        heroImg = findViewById(R.id.heroImg)
        heroTitle = findViewById(R.id.heroTitle)
        heroMeta = findViewById(R.id.heroMeta)
        heroDesc = findViewById(R.id.heroDesc)
        catRv.layoutManager = LinearLayoutManager(this)
        catRv.setHasFixedSize(true)
        glm = GridLayoutManager(this, computeSpan())
        itemRv.layoutManager = glm
        // Taille de grille fixe + cache plus grand : moins de recalculs pendant le defilement
        // et le chargement page par page (perf).
        itemRv.setHasFixedSize(true)
        itemRv.setItemViewCacheSize(24)
        itemAdapter = TileAdapter { onTileClick(it) }
        itemRv.adapter = itemAdapter
        searchEt.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (ignoreSearchChange) return
                val q = searchEt.text.toString().trim()
                // Films/Series : la recherche cherche sur TOUS les serveurs (comme le Classique).
                if (q.length >= 2) {
                    runMultiServerSearch(q)
                } else {
                    multiMode = false
                    searchJob?.cancel()
                    applyFilter()
                }
            }
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
        })
        // Commande vocale : "voiceQuery" pre-remplit la recherche (traitee apres chargement des categories).
        voiceQuery = intent.getStringExtra("voiceQuery")?.trim().orEmpty()
        ensureSession { loadCategories() }
    }

    private fun setLoading(b: Boolean) { progress.visibility = if (b) View.VISIBLE else View.GONE }

    private fun computeSpan(): Int {
        val m = resources.displayMetrics
        val totalDp = m.widthPixels / m.density
        val content = (totalDp - 64f - 170f).coerceAtLeast(200f)
        return (content / 108f).toInt().coerceIn(2, 9)
    }

    private fun loadCategories() {
        val pl = Session.current ?: run { msgTv.text = "Serveurs indisponibles."; return }
        setLoading(true); msgTv.text = ""
        lifecycleScope.launch {
            try {
                // v391 : mode multiliste (toutes les listes) aussi dans le theme NewTivi.
                val multiLists = MultiListPref.isAll(this@NtCatalogActivity) && Session.playlists.size > 1
                val base = if (multiLists) catsAllLists() else when (pl.type) {
                    "m3u" -> Api.m3uCategories(pl, kind)
                    "stalker" -> Api.stalkerCategories(pl, kind)
                    else -> Api.xtreamCategories(pl, kind)
                }
                if (!multiLists) CategorySync.report(this@NtCatalogActivity, pl, kind, base)
                categories = listOf(
                    Category("__favorites__", "Favoris"),
                    Category("__recent__", "Vu r\u00e9cemment"),
                    Category("__all__", "Tout")
                ) + base.filter { !it.id.startsWith("__") }
                catAdapter = CatAdapter(categories) { selectCategory(it) }
                catRv.adapter = catAdapter
                setLoading(false)
                // Prechauffe en arriere-plan les catalogues de TOUS les serveurs (Films/Series) pour
                // que la recherche (micro ou manuelle) trouve vraiment sur tous les serveurs, et vite.
                if (kind == "movie" || kind == "series") {
                    lifecycleScope.launch(Dispatchers.IO) { try { Api.prefetchCatalogs(Session.playlists, kind) } catch (e: Exception) {} }
                }
                if (voiceQuery.isNotBlank()) {
                    // Recherche vocale : on lance directement la recherche multi-serveurs.
                    val q = voiceQuery; voiceQuery = ""
                    searchEt.setText(q); searchEt.setSelection(q.length)
                } else {
                    val firstReal = categories.firstOrNull { !it.id.startsWith("__") } ?: categories.firstOrNull()
                    if (firstReal != null) selectCategory(firstReal)
                }
            } catch (e: Exception) { setLoading(false); msgTv.text = "Erreur : ${e.message}" }
        }
    }

    // v391 : categories de TOUTES les listes, id = "<categorie>@@<idListe>".
    private suspend fun catsAllLists(): List<Category> {
        val out = ArrayList<Category>()
        for (p in Session.playlists) {
            val cats = try {
                when (p.type) {
                    "m3u" -> Api.m3uCategories(p, kind)
                    "stalker" -> Api.stalkerCategories(p, kind)
                    else -> Api.xtreamCategories(p, kind)
                }
            } catch (e: Exception) { emptyList<Category>() }
            CategorySync.report(this@NtCatalogActivity, p, kind, cats)
            for (c in cats) {
                if (c.id.startsWith("__")) continue
                out.add(Category(c.id + "@@" + p.id, c.name + "   -   " + p.nom))
            }
        }
        return out
    }

    private fun selectCategory(cat: Category) {
        // Choisir une categorie = navigation : on quitte le mode recherche multi-serveurs.
        multiMode = false
        searchJob?.cancel()
        // On efface une eventuelle recherche en cours (ex. apres une recherche vocale) pour ne pas
        // filtrer la categorie choisie avec un ancien texte -> sinon "Tout" semble ne rien afficher.
        if (searchEt.text.isNotEmpty()) { ignoreSearchChange = true; searchEt.setText(""); ignoreSearchChange = false }
        selectedCat = cat.id
        // v391 : categorie issue du multiliste -> on bascule sur sa liste.
        val atSep = cat.id.indexOf("@@")
        val realCat = if (atSep > 0) cat.id.substring(0, atSep) else cat.id
        if (atSep > 0) {
            val owner = Session.playlists.firstOrNull { it.id == cat.id.substring(atSep + 2) }
            if (owner != null && owner.id != Session.current?.id) Session.current = owner
        }
        catAdapter?.notifyDataSetChanged()
        val pl = Session.current ?: return
        if (cat.id == "__favorites__") {
            items = Favorites.forKind(this, kind); applyFilter()
            msgTv.text = if (items.isEmpty()) "Aucun favori." else ""; setLoading(false); return
        }
        if (cat.id == "__recent__") {
            items = WatchHistory.recentItems(this, kind); applyFilter()
            msgTv.text = if (items.isEmpty()) "Rien vu r\u00e9cemment." else ""; setLoading(false); return
        }
        setLoading(true); items = emptyList(); applyFilter()
        lifecycleScope.launch {
            try {
                when (pl.type) {
                    "stalker" -> {
                        val acc = ArrayList<Item>()
                        Api.stalkerItemsPaged(pl, kind, realCat) { batch ->
                            // On passe la reference (pas de copie complete a chaque lot) : combine a
                            // l'insertion incrementale de l'adaptateur, l'affichage reste fluide.
                            withContext(Dispatchers.Main) { acc.addAll(batch); items = acc; applyFilter(); setLoading(false) }
                        }
                    }
                    "m3u" -> { items = Api.m3uItems(pl, kind, realCat); applyFilter(); setLoading(false) }
                    else -> { items = Api.xtreamItems(pl, kind, realCat); applyFilter(); setLoading(false) }
                }
                if (items.isEmpty()) msgTv.text = "Aucun contenu." else msgTv.text = ""
            } catch (e: Exception) { setLoading(false); msgTv.text = "Erreur : ${e.message}" }
        }
    }

    // Clic sur une affiche : bascule d'abord sur le serveur d'origine (resultats multi-serveurs).
    private fun onTileClick(item: Item) {
        if (item.ownerPlaylistId.isNotBlank() && item.ownerPlaylistId != Session.current?.id) {
            Session.playlists.firstOrNull { it.id == item.ownerPlaylistId }?.let { Session.current = it }
        }
        openItem(item)
    }

    // Recherche multi-serveurs (Films/Series) : interroge tous les serveurs, fusionne les doublons
    // et affiche chaque resultat avec le(s) serveur(s) ou il est disponible.
    private fun runMultiServerSearch(q: String) {
        multiMode = true
        searchJob?.cancel()
        // Garde d'epoque : seule la DERNIERE recherche a le droit de mettre a jour l'ecran.
        val epoch = ++searchEpoch
        val playlists = Session.playlists
        if (playlists.isEmpty()) { msgTv.text = "Aucun serveur ajoute."; return }
        setLoading(true); msgTv.text = ""
        searchJob = lifecycleScope.launch(Dispatchers.IO) {
            delay(250) // anti-rebond pendant la frappe
            if (epoch != searchEpoch) return@launch
            try {
                Api.searchAllServers(playlists, q, kind) { done, total, merged ->
                    withContext(Dispatchers.Main) {
                        if (!multiMode || epoch != searchEpoch) return@withContext
                        filtered = merged
                        itemAdapter?.submit(filtered)
                        filtered.firstOrNull()?.let { updateHero(it) }
                        if (merged.isNotEmpty()) { setLoading(false); msgTv.text = "" }
                        else if (done >= total) { setLoading(false); msgTv.text = "Aucun resultat pour \"$q\"." }
                        else msgTv.text = ""
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { msgTv.text = "Erreur : ${e.message}"; setLoading(false) }
            }
        }
    }

    private fun applyFilter() {
        // En mode recherche multi-serveurs, les resultats sont geres par runMultiServerSearch.
        if (multiMode) return
        val q = searchEt.text.toString().trim().lowercase()
        filtered = if (q.isBlank()) items else items.filter { it.kind != "header" && it.name.lowercase().contains(q) }
        itemAdapter?.submit(filtered)
        filtered.firstOrNull()?.let { updateHero(it) }
    }

    private var heroLogo: String = ""
    private fun updateHero(item: Item) {
        heroTitle.text = item.name
        heroMeta.text = item.duration
        heroDesc.text = when {
            item.description.isNotBlank() -> item.description
            item.summary.isNotBlank() -> item.summary
            else -> ""
        }
        // Ne recharge l'affiche que si elle a change : evite de recharger la meme image en boucle
        // pendant le chargement page par page (le 1er element reste le meme a chaque lot).
        if (item.logo != heroLogo) {
            heroLogo = item.logo
            heroImg.load(item.logo) { crossfade(true); placeholder(R.drawable.bg_tile); error(R.drawable.ic_movie) }
        }
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

    inner class TileAdapter(val onClick: (Item) -> Unit) : RecyclerView.Adapter<TileAdapter.VH>() {
        private val data = ArrayList<Item>()
        fun submit(list: List<Item>) {
            // Ajout incremental : si la liste ne fait que s'allonger (meme prefixe), on insere
            // seulement les nouveaux elements au lieu de tout reconstruire. notifyDataSetChanged
            // rebind TOUTE la grille et recharge toutes les affiches a chaque lot Stalker -> lenteur.
            if (list.size > data.size && isPrefix(data, list)) {
                val start = data.size
                data.addAll(list.subList(start, list.size))
                notifyItemRangeInserted(start, list.size - start)
                return
            }
            data.clear(); data.addAll(list); notifyDataSetChanged()
        }
        private fun isPrefix(old: List<Item>, new: List<Item>): Boolean {
            if (new.size < old.size) return false
            for (i in old.indices) if (old[i] != new[i]) return false
            return true
        }
        inner class VH(val v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.nameTv)
            val poster: ImageView = v.findViewById(R.id.posterIv)
            val progressWrap: View = v.findViewById(R.id.progressWrap)
            val serverChip: TextView = v.findViewById(R.id.serverChip)
            val quality: TextView = v.findViewById(R.id.qualityBadge)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_tile, parent, false))
        override fun getItemCount() = data.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = data[position]
            holder.name.text = item.name
            holder.progressWrap.visibility = View.GONE
            // Resultat multi-serveurs : on affiche la pastille du/des serveur(s).
            if (item.serverLabel.isNotBlank()) {
                holder.serverChip.visibility = View.VISIBLE
                holder.serverChip.text = item.serverLabel
            } else holder.serverChip.visibility = View.GONE
            val q = when {
                R_4K.containsMatchIn(item.name) -> "4K"
                R_FHD.containsMatchIn(item.name) -> "FHD"
                R_HD.containsMatchIn(item.name) -> "HD"
                else -> ""
            }
            holder.quality.text = q
            holder.quality.visibility = if (q.isBlank()) View.GONE else View.VISIBLE
            val fallback = R.drawable.ic_movie
            holder.poster.scaleType = ImageView.ScaleType.CENTER_CROP
            if (item.logo.isBlank()) holder.poster.setImageResource(fallback)
            else holder.poster.load(item.logo) {
                crossfade(false)
                placeholder(R.drawable.bg_tile)
                error(fallback)
                // Downsample a la taille du poster : bitmaps plus petits en memoire.
                size(360, 540)
            }
            holder.v.setOnFocusChangeListener { _, hasFocus ->
                holder.v.animate().scaleX(if (hasFocus) 1.05f else 1f).scaleY(if (hasFocus) 1.05f else 1f).setDuration(90).start()
                holder.v.translationZ = if (hasFocus) 16f else 0f
                holder.name.setTextColor(if (hasFocus) KzColors.accent(holder.name.context) else ContextCompat.getColor(holder.name.context, R.color.text))
                if (hasFocus) updateHero(item)
            }
            holder.v.setOnClickListener { onClick(item) }
        }
    }
}
