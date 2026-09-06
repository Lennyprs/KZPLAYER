package com.kzplayer.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DetailActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)

        val item = Session.detailItem
        val pl = Session.current
        val title = findViewById<TextView>(R.id.detailTitle)
        val desc = findViewById<TextView>(R.id.detailDesc)
        val playBtn = findViewById<Button>(R.id.playBtn)
        val trailerBtn = findViewById<Button>(R.id.trailerBtn)
        val downloadBtn = findViewById<Button>(R.id.downloadBtn)
        val favBtn = findViewById<TextView>(R.id.favBtn)
        val prog = findViewById<ProgressBar>(R.id.detailProgress)
        val back = findViewById<TextView>(R.id.backBtn)

        back.setOnClickListener { finish() }

        if (item == null || pl == null) { finish(); return }

        fun refreshFav() {
            favBtn.text = if (Favorites.isFavorite(this, item)) "\u2605 Favori" else "\u2606 Favori"
        }
        refreshFav()
        favBtn.setOnClickListener {
            val added = Favorites.toggle(this, item)
            favBtn.text = if (added) "\u2605 Favori" else "\u2606 Favori"
        }

        title.text = item.name
        findViewById<ImageView>(R.id.posterIv).load(item.logo) {
            placeholder(R.drawable.bg_tile)
            error(R.drawable.ic_movie)
            crossfade(true)
        }
        val meta = findViewById<TextView>(R.id.detailMeta)
        meta.text = if (item.duration.isNotBlank()) "Dur\u00e9e : ${item.duration}" else ""
        desc.text = "Chargement du resume..."

        lifecycleScope.launch {
            val info = try {
                val sid = item.streamId
                if (pl.type == "xtream" && !sid.isNullOrBlank()) Api.xtreamVodInfo(pl, sid) else null
            } catch (e: Exception) { null }
            if (info != null) {
                if (info.duration.isNotBlank()) meta.text = "Dur\u00e9e : ${info.duration}"
                val p = Api.cleanPlot(info.plot).ifBlank { Api.cleanPlot(item.description) }
                desc.text = if (p.isBlank()) "Pas de resume disponible pour ce titre." else p
            } else {
                val p = Api.cleanPlot(item.description)
                desc.text = if (p.isBlank()) "Pas de resume disponible pour ce titre." else p
            }
        }

        loadCastAndSimilar(item, pl)

        trailerBtn.setOnClickListener {
            trailerBtn.isEnabled = false
            trailerBtn.text = "Recherche de la bande-annonce..."
            lifecycleScope.launch {
                // v348 : 1) on trouve la bande-annonce sur YouTube (recherche fiable),
                //        2) on tente le flux direct pour la lire dans le lecteur KZ,
                //        3) si YouTube bloque le flux, on la lit en plein ecran sans habillage.
                // v349 : plusieurs bandes-annonces possibles ; le lecteur essaie la suivante
                // si YouTube refuse la lecture hors de son site (erreur 152).
                val vids = try { YtStream.searchTrailerIds(item.name) } catch (_: Exception) { emptyList<String>() }
                val vid = vids.firstOrNull() ?: ""
                val st = if (vid.isBlank()) null else try { YtStream.resolveVideo(vid) } catch (_: Exception) { null }
                trailerBtn.isEnabled = true
                trailerBtn.text = "Voir la bande-annonce"
                val label = "Bande-annonce - " + item.name
                if (st != null) {
                    startActivity(
                        Intent(this@DetailActivity, PlayerActivity::class.java)
                            .putExtra("url", st.url)
                            .putExtra("title", label)
                            .putExtra("mode", "vod")
                            .putExtra("historyKind", "trailer")
                            .putExtra("forceUa", st.ua)
                    )
                } else if (vid.isNotBlank()) {
                    startActivity(
                        Intent(this@DetailActivity, TrailerActivity::class.java)
                            .putStringArrayListExtra("videoIds", ArrayList(vids))
                            .putExtra("title", label)
                    )
                } else {
                    desc.text = "Aucune bande-annonce trouvee pour ce titre."
                }
            }
        }

        // v359 : telechargement du film dans la memoire de l appareil.
        downloadBtn.setOnClickListener {
            prog.visibility = View.VISIBLE
            downloadBtn.isEnabled = false
            lifecycleScope.launch {
                val dlUrl = try {
                    if (pl.type == "stalker") Api.stalkerLink(pl, item.cmd ?: "", "movie")
                    else item.directUrl
                } catch (e: Exception) { null }
                prog.visibility = View.GONE
                downloadBtn.isEnabled = true
                val msg = Downloads.enqueue(this@DetailActivity, item.name, dlUrl ?: "")
                android.widget.Toast.makeText(this@DetailActivity, msg, android.widget.Toast.LENGTH_LONG).show()
            }
        }

        // v395 : focus TRES visible (fort agrandissement + relief), sinon impossible
        // de savoir sur quel bouton on est sur TV. Le drawable et la couleur du texte
        // se chargent du reste (fond blanc + texte noir + curseur).
        for (b in listOf(playBtn, trailerBtn, downloadBtn)) {
            b.setOnFocusChangeListener { v, has ->
                v.animate().scaleX(if (has) 1.14f else 1f).scaleY(if (has) 1.14f else 1f)
                    .setDuration(110).start()
                v.translationZ = if (has) 20f else 0f
            }
        }
        playBtn.requestFocus()

        playBtn.setOnClickListener {
            prog.visibility = View.VISIBLE
            playBtn.isEnabled = false
            lifecycleScope.launch {
                val url = try {
                    if (pl.type == "stalker") Api.stalkerLink(pl, item.cmd ?: "", "movie")
                    else item.directUrl
                } catch (e: Exception) { null }
                prog.visibility = View.GONE
                playBtn.isEnabled = true
                if (!url.isNullOrBlank()) {
                    WatchHistory.touch(
                        this@DetailActivity,
                        url,
                        item.name,
                        item.logo,
                        "movie",
                        sourceCmd = item.cmd ?: "",
                        sourceStreamId = item.streamId ?: "",
                        sourceContainerExt = item.containerExt ?: ""
                    )
                    startActivity(
                        Intent(this@DetailActivity, PlayerActivity::class.java)
                            .putExtra("url", url)
                            .putExtra("title", item.name)
                            .putExtra("logo", item.logo)
                            .putExtra("historyKind", "movie")
                            .putExtra("historySourceCmd", item.cmd ?: "")
                            .putExtra("historySourceStreamId", item.streamId ?: "")
                            .putExtra("historySourceContainerExt", item.containerExt ?: "")
                            .putExtra("mode", "vod")
                    )
                } else {
                    desc.text = "Impossible d'obtenir le flux pour ce titre."
                }
            }
        }
    }

    // ============================ v338 : CASTING + TITRES SIMILAIRES ============================
    // - Casting : TMDB (photo + nom + role).
    // - Titres similaires : suggestions TMDB filtrees pour ne garder QUE les titres reellement
    //   presents dans la liste de lecture active (donc lisibles en un clic).
    // 100% additif : si TMDB est indisponible, les deux sections restent simplement masquees.
    private fun loadCastAndSimilar(item: Item, pl: Playlist) {
        val castTitle = findViewById<TextView>(R.id.castTitle)
        val castRv = findViewById<RecyclerView>(R.id.castRv)
        val simTitle = findViewById<TextView>(R.id.similarTitle)
        val simRv = findViewById<RecyclerView>(R.id.similarRv)

        lifecycleScope.launch {
            val cast = try { Tmdb.castFor(item.name, false) } catch (e: Exception) { emptyList<Tmdb.CastMember>() }
            if (cast.isNotEmpty()) {
                castTitle.visibility = View.VISIBLE
                castRv.visibility = View.VISIBLE
                castRv.layoutManager = LinearLayoutManager(this@DetailActivity, RecyclerView.HORIZONTAL, false)
                castRv.adapter = CastAdapter(cast)
            }
        }

        lifecycleScope.launch {
            // v351 : on affiche les similaires des que TMDB repond (affiches TMDB),
            // exactement comme la distribution. Le catalogue de la liste de lecture
            // etait tres long a charger et retardait tout l affichage.
            val entries = try { Tmdb.similarEntries(item.name, false) } catch (e: Exception) { emptyList<Tmdb.SimilarEntry>() }
            if (entries.isEmpty()) return@launch
            val rows = entries.map { SimRow(it.title, it.poster, null) }
            val adapter = SimAdapter(rows)
            simTitle.visibility = View.VISIBLE
            simRv.visibility = View.VISIBLE
            simRv.layoutManager = LinearLayoutManager(this@DetailActivity, RecyclerView.HORIZONTAL, false)
            simRv.adapter = adapter

            // Ensuite seulement, en tache de fond : on repere ceux presents dans ta liste
            // pour les rendre cliquables. L affichage est deja fait, rien ne bloque.
            val byKey = withContext(Dispatchers.Default) {
                val wanted = HashMap<String, Item>()
                val keys = rows.map { Tmdb.matchKey(it.title) }.filter { it.length >= 4 }.toHashSet()
                if (keys.isEmpty()) return@withContext wanted
                val catalog = try {
                    when (pl.type) {
                        "m3u" -> Api.m3uItems(pl, "movie", "__all__")
                        "stalker" -> Api.stalkerItems(pl, "movie", "__all__")
                        else -> Api.xtreamItems(pl, "movie", "__all__")
                    }
                } catch (e: Exception) { emptyList<Item>() }
                for (c in catalog) {
                    val k = Tmdb.matchKey(c.name)
                    if (k.isNotBlank() && keys.contains(k) && !wanted.containsKey(k)) wanted[k] = c
                }
                wanted
            }
            if (byKey.isNotEmpty()) {
                var changed = false
                for (r in rows) {
                    val c = byKey[Tmdb.matchKey(r.title)]
                    if (c != null) { r.item = c; changed = true }
                }
                if (changed) adapter.notifyDataSetChanged()
            }
        }
    }

    private inner class CastAdapter(val data: List<Tmdb.CastMember>) :
        RecyclerView.Adapter<CastAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val img: ImageView = v.findViewById(R.id.castIv)
            val name: TextView = v.findViewById(R.id.castName)
            val role: TextView = v.findViewById(R.id.castRole)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_kz_cast, parent, false))
        override fun getItemCount(): Int = data.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val c = data[position]
            holder.name.text = c.name
            holder.role.text = c.role
            if (c.photo.isBlank()) holder.img.setImageResource(R.drawable.ic_person)
            else holder.img.load(c.photo) { crossfade(true); error(R.drawable.ic_person) }
        }
    }

    /** Un titre similaire : affiche TMDB, et le film de ta liste s il y est. */
    private class SimRow(val title: String, val poster: String, var item: Item?)

    private inner class SimAdapter(val data: List<SimRow>) :
        RecyclerView.Adapter<SimAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val poster: ImageView = v.findViewById(R.id.posterIv)
            val name: TextView = v.findViewById(R.id.nameTv)
            val sub: TextView = v.findViewById(R.id.subTv)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_nflx_card, parent, false))

        override fun getItemCount(): Int = data.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = data[position]
            holder.name.text = row.title
            holder.sub.visibility = View.GONE
            if (row.poster.isBlank()) holder.poster.setImageResource(R.drawable.ic_movie)
            else holder.poster.load(row.poster) { crossfade(false); error(R.drawable.ic_movie) }
            holder.itemView.setOnFocusChangeListener { v, has ->
                val sc = if (has) 1.08f else 1f
                v.animate().scaleX(sc).scaleY(sc).setDuration(110).start()
            }
            holder.itemView.setOnClickListener {
                val target = row.item
                if (target == null) {
                    android.widget.Toast.makeText(
                        this@DetailActivity,
                        "Ce titre n est pas dans ta liste de lecture.",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    Session.detailItem = target
                    startActivity(Intent(this@DetailActivity, DetailActivity::class.java))
                }
            }
        }
    }

    private inner class SimilarAdapter(val data: List<Item>) :
        RecyclerView.Adapter<SimilarAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val poster: ImageView = v.findViewById(R.id.posterIv)
            val name: TextView = v.findViewById(R.id.nameTv)
            val sub: TextView = v.findViewById(R.id.subTv)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_nflx_card, parent, false))
        override fun getItemCount(): Int = data.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val it2 = data[position]
            holder.name.text = it2.name
            holder.sub.visibility = View.GONE
            if (it2.logo.isBlank()) holder.poster.setImageResource(R.drawable.ic_movie)
            else holder.poster.load(it2.logo) { crossfade(false); error(R.drawable.ic_movie) }
            holder.itemView.setOnFocusChangeListener { v, has ->
                val sc = if (has) 1.08f else 1f
                v.animate().scaleX(sc).scaleY(sc).setDuration(110).start()
            }
            holder.itemView.setOnClickListener {
                Session.detailItem = it2
                startActivity(Intent(this@DetailActivity, DetailActivity::class.java))
            }
        }
    }
}
