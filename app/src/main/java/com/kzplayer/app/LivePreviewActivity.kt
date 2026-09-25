package com.kzplayer.app

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.launch

class LivePreviewActivity : BaseActivity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var epgRv: RecyclerView
    private lateinit var epgMsg: TextView
    private lateinit var nowTitle: TextView
    private lateinit var nowTime: TextView
    private lateinit var nowDesc: TextView
    private lateinit var titleTv: TextView

    private var url: String = ""
    private var title: String = ""
    private var logo: String = ""
    private var streamId: String = ""
    private var channels: List<Item> = emptyList()
    private val epgData = HashMap<String, List<EpgEntry>>()

    // Plein ecran auto apres 5 s d'inactivite.
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var autoFsDone = false
    private val autoFsRunnable = Runnable { openFullscreen() }


    // v385 : detecte les contenus lourds (Full HD, 4K/UHD, HDR, Dolby Vision, HEVC).
    // Sert uniquement a choisir le decodeur video : ces flux doivent etre decodes par
    // la puce video. Le decodeur logiciel n arrive pas a suivre (saccades) et rend en
    // plus les contenus HDR/Dolby tres sombres.
    private fun estFullHd(nom: String): Boolean {
        val t = (nom + " " + (try { Session.browseTitle } catch (e: Throwable) { "" }))
            .uppercase()
        return t.contains("FHD") || t.contains("1080") ||
            t.contains("FULL HD") || t.contains("FULLHD") ||
            t.contains("UHD") || t.contains("4K") || t.contains("2160") ||
            t.contains("HDR") || t.contains("DOLBY") || t.contains("DV ") ||
            t.contains("HEVC") || t.contains("H265") || t.contains("H.265")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v154 : sur telephone (sw<600dp) on laisse l'orientation naturelle du device.
        // Auparavant on forcait landscape pour tout le monde -> sur telephone en portrait
        // l'ecran pivotait de force et le player reduit n'apparaissait pas correctement,
        // d'ou le "mode reduit ne fonctionne pas" en Classique sur telephone.
        // TV/tablette : landscape force pour conserver le split horizontal 1.5/1.
        val isPhone = resources.configuration.smallestScreenWidthDp < 600
        if (!isPhone) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_live_preview)

        url = intent.getStringExtra("url") ?: ""
        title = intent.getStringExtra("title") ?: "TV"
        logo = intent.getStringExtra("logo") ?: ""
        streamId = intent.getStringExtra("streamId") ?: ""
        channels = Session.liveChannels

        titleTv = findViewById(R.id.titleTv)
        nowTitle = findViewById(R.id.nowTitle)
        nowTime = findViewById(R.id.nowTime)
        nowDesc = findViewById(R.id.nowDesc)
        epgRv = findViewById(R.id.epgRv)
        epgMsg = findViewById(R.id.epgMsg)
        playerView = findViewById(R.id.playerView)

        titleTv.text = title
        // Bouton "Retour" masque (la touche RETOUR de la telecommande quitte toujours l'apercu).
        findViewById<TextView>(R.id.backBtn).visibility = View.GONE

        epgRv.layoutManager = LinearLayoutManager(this)
        epgRv.itemAnimator = null
        epgRv.setItemViewCacheSize(20)

        // Chaines : aucun bouton play/pause sur l'apercu (lecteur sans controleur).
        playerView.useController = false
        playerView.isFocusable = true
        playerView.isFocusableInTouchMode = true
        playerView.requestFocus()
        playerView.setOnKeyListener { _, keyCode, ev ->
            if (ev.action == KeyEvent.ACTION_DOWN && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                openFullscreen(); true
            } else false
        }

        startMiniPlayer()
        loadNowInfo()
        setupEpgGrid()
        scheduleAutoFullscreen()
    }

    override fun onStart() {
        super.onStart()
        // Au retour du plein ecran, l'apercu avait libere le lecteur : on le relance.
        if (player == null && url.isNotBlank()) startMiniPlayer()
    }

    // Bascule en plein ecran apres 5 s sans action. Relance a chaque touche (donc ne coupe pas
    // la navigation dans le guide). Ne se declenche qu'une seule fois.
    private fun scheduleAutoFullscreen() {
        uiHandler.removeCallbacks(autoFsRunnable)
        if (!autoFsDone) uiHandler.postDelayed(autoFsRunnable, 5000)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!autoFsDone) scheduleAutoFullscreen()
        return super.dispatchKeyEvent(event)
    }

    private fun setupEpgGrid() {
        if (channels.isEmpty()) {
            epgMsg.visibility = View.VISIBLE
            epgMsg.text = "Guide indisponible pour cette source."
            epgRv.visibility = View.GONE
            return
        }
        epgMsg.visibility = View.GONE
        epgRv.visibility = View.VISIBLE
        epgRv.adapter = GridAdapter()
    }

    private fun startMiniPlayer() {
        player?.release(); player = null
        if (url.isBlank()) return
        // v149 : idem PlayerActivity (DNS-over-HTTPS applique au lecteur reduit).
        val plCur = Session.current
        val streamUa = if (plCur != null && plCur.type == "stalker") {
            Api.stalkerHeaders(plCur)["User-Agent"]?.takeIf { it.isNotBlank() }
                ?: "VLC/3.0.20 LibVLC/3.0.20"
        } else {
            "VLC/3.0.20 LibVLC/3.0.20"
        }
        val httpFactory = KzHttpDataSource.factory(this, userAgent = streamUa, allowCrossProtocolRedirects = true)
        val extractors = androidx.media3.extractor.DefaultExtractorsFactory()
            .setTsExtractorFlags(
                androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
            )
        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(httpFactory, extractors)
        // v411 : meme pipeline Media3 standard que le plein ecran.
        val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(
                androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            )
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(6000, 40000, 2000, 3500) // v387 : reserve d avance, fin des saccades
            .build()
        val p = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()
        p.setAudioAttributes(
            androidx.media3.common.AudioAttributes.Builder()
                .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_SPEECH)
                .build(), true
        )
        p.volume = 1.0f
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && p.playWhenReady && !p.isPlaying) p.play()
            }
        })
        player = p
        playerView.player = p
        p.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
        p.playWhenReady = true
        p.prepare()
        p.play()
    }

    // Recupere l'EPG selon le type de source : Xtream (get_simple_data_table) ou Stalker (get_short_epg).
    private suspend fun fetchEpg(pl: Playlist, sid: String): List<EpgEntry> {
        if (sid.isBlank()) return emptyList()
        return try {
            when (pl.type) {
                "xtream" -> Api.xtreamFullEpg(pl, sid)
                "stalker" -> Api.stalkerShortEpg(pl, sid)
                else -> emptyList()
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun loadNowInfo() {
        val pl = Session.current
        val sid = streamId
        if (pl == null || sid.isBlank() || (pl.type != "xtream" && pl.type != "stalker")) {
            nowTitle.text = title
            nowTime.visibility = View.GONE
            nowDesc.text = "Programme TV indisponible pour cette source.\nOK pour passer en plein \u00e9cran."
            return
        }
        nowTitle.text = title
        nowTime.visibility = View.GONE
        nowDesc.text = "Chargement du programme..."
        lifecycleScope.launch {
            val rows = fetchEpg(pl, sid)
            epgData[sid] = rows
            val cur = rows.firstOrNull { it.nowPlaying } ?: rows.firstOrNull()
            if (cur == null) {
                nowTitle.text = title
                nowTime.visibility = View.GONE
                nowDesc.text = "Aucun programme EPG trouv\u00e9.\nOK pour passer en plein \u00e9cran."
            } else {
                nowTitle.text = cur.title
                nowTime.text = cur.time
                nowTime.visibility = if (cur.time.isBlank()) View.GONE else View.VISIBLE
                nowDesc.text = cur.description.ifBlank { title }
            }
        }
    }

    private fun playChannel(item: Item) {
        title = item.name
        titleTv.text = title
        streamId = item.streamId ?: ""
        val direct = item.directUrl
        val pl = Session.current
        if (!direct.isNullOrBlank()) {
            url = direct; startMiniPlayer(); loadNowInfo(); return
        }
        if (pl != null && pl.type == "stalker" && !item.cmd.isNullOrBlank()) {
            nowDesc.text = "Ouverture de la cha\u00eene..."
            lifecycleScope.launch {
                val link = try { Api.stalkerLink(pl, item.cmd!!, "live") } catch (e: Exception) { null }
                if (!link.isNullOrBlank()) { url = link; startMiniPlayer(); loadNowInfo() }
                else nowDesc.text = "Impossible d'ouvrir cette cha\u00eene."
            }
        }
    }

    private fun openFullscreen() {
        autoFsDone = true
        uiHandler.removeCallbacks(autoFsRunnable)
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra("url", url)
                .putExtra("title", title)
                .putExtra("logo", logo)
                .putExtra("historyKind", "live")
                .putExtra("mode", "live")
        )
    }

    override fun onStop() {
        super.onStop()
        uiHandler.removeCallbacks(autoFsRunnable)
        player?.release(); player = null
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun makeCell(time: String, label: String, now: Boolean): View {
        val cell = LinearLayout(this)
        cell.orientation = LinearLayout.VERTICAL
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        lp.marginStart = dp(3); lp.marginEnd = dp(3); lp.topMargin = dp(4); lp.bottomMargin = dp(4)
        cell.layoutParams = lp
        cell.setBackgroundResource(if (now) R.drawable.bg_epg_now else R.drawable.bg_epg_cell)
        cell.setPadding(dp(6), dp(4), dp(6), dp(4))
        cell.gravity = Gravity.CENTER_VERTICAL
        if (time.isNotBlank()) {
            val t = TextView(this)
            t.text = time
            t.textSize = 9.5f
            t.setTextColor(ContextCompat.getColor(this, if (now) R.color.text else R.color.muted))
            cell.addView(t)
        }
        val ti = TextView(this)
        ti.text = label
        ti.textSize = 11f
        ti.maxLines = 2
        ti.ellipsize = android.text.TextUtils.TruncateAt.END
        ti.setTypeface(null, Typeface.BOLD)
        ti.setTextColor(ContextCompat.getColor(this, R.color.text))
        cell.addView(ti)
        return cell
    }

    inner class GridAdapter : RecyclerView.Adapter<GridAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val logo: ImageView = v.findViewById(R.id.logoIv)
            val name: TextView = v.findViewById(R.id.nameTv)
            val progRow: LinearLayout = v.findViewById(R.id.progRow)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(layoutInflater.inflate(R.layout.item_epg_channel, parent, false))
        }
        override fun getItemCount(): Int = channels.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val ch = channels[position]
            val playing = (ch.streamId ?: "") == streamId && streamId.isNotBlank()
            holder.name.text = (if (playing) "\u25b6 " else "") + ch.name
            holder.name.setTextColor(if (playing) KzColors.accent(this@LivePreviewActivity) else ContextCompat.getColor(this@LivePreviewActivity, R.color.text))
            holder.logo.load(ch.logo) {
                crossfade(false)
                placeholder(R.drawable.ic_live_tv)
                error(R.drawable.ic_live_tv)
            }
            holder.itemView.setOnClickListener { playChannel(ch); notifyDataSetChanged() }

            val sid = ch.streamId ?: ""
            val pl = Session.current
            holder.progRow.removeAllViews()
            if (pl == null || (pl.type != "xtream" && pl.type != "stalker") || sid.isBlank()) {
                holder.progRow.addView(makeCell("", "Pas d'information", false))
                return
            }
            val cached = epgData[sid]
            if (cached != null) {
                fill(holder.progRow, cached)
            } else {
                holder.progRow.addView(makeCell("", "Chargement...", false))
                lifecycleScope.launch {
                    val rows = fetchEpg(pl, sid)
                    epgData[sid] = rows
                    if (holder.bindingAdapterPosition == position) fill(holder.progRow, rows)
                }
            }
        }
        private fun fill(row: LinearLayout, rows: List<EpgEntry>) {
            row.removeAllViews()
            if (rows.isEmpty()) { row.addView(makeCell("", "Pas d'information", false)); return }
            for (e in rows.take(3)) row.addView(makeCell(e.time, e.title, e.nowPlaying))
        }
    }
}
