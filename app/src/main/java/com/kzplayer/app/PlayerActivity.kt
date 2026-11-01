package com.kzplayer.app

import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class PlayerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private var playbackOwnerId=""
    private var vodCodecFactory:VodAsyncRenderersFactory?=null
    private val vodCodecRecovery=VodPlaybackPolicy.RecoveryBudget()
    private var compatibleVideoSelected=false
    private var activeVideoDecoderName=""
    private var playbackErrorDialog:PlaybackErrorDialog?=null
    private fun closePlaybackError(){val d=playbackErrorDialog;playbackErrorDialog=null;d?.dismiss()}
    private fun showPlaybackError(title:String,message:String){
        if(isFinishing||isDestroyed||!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))return
        closePlaybackError()
        val d=PlaybackErrorDialog(this)
        d.setTitle(title);d.setMessage(message)
        d.setButton(android.content.DialogInterface.BUTTON_POSITIVE,"OK"){_,_->}
        d.setOnDismissListener {if(playbackErrorDialog===d)playbackErrorDialog=null}
        playbackErrorDialog=d;d.show()
    }

    private lateinit var playerView: PlayerView
    private var candidates: List<String> = emptyList()
    private var isReplayMode = false
    private val replayStartupWatchdog = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (!isReplayMode || (p.playbackState == Player.STATE_READY && p.videoSize.width > 0)) return
            if (candIdx < candidates.size - 1) {
                candIdx++
                intent.putExtra("replayCandidateIndex", candIdx)
                playCurrent()
            } else {
                p.pause()
                androidx.appcompat.app.AlertDialog.Builder(this@PlayerActivity)
                    .setTitle("Replay indisponible")
                    .setMessage("Aucune variante d'archive n'a demarre. Aucun basculement vers le direct.")
                    .setPositiveButton("OK") { d, _ -> d.dismiss() }
                    .show()
            }
        }
    }
    private var candIdx = 0
    companion object {
        private var stalkerVodSansEntetes = false
        private val UAS_VOD = listOf(
            "VLC/3.0.20 LibVLC/3.0.20",
            "Lavf/60.16.100",
            "IPTVSmartersPro",
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "okhttp/4.12.0",
            "Dalvik/2.1.0 (Linux; U; Android 12)"
        )
        private var vodUaIdx = 0
    }
    private var repliEntetesFait = false
    private var reessai401Fait = false

    private var demarrageOk = false
    private var demarrageEssais = 0
    private val startupWatchdog = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (demarrageOk) return
            val demarre = p.playbackState == Player.STATE_READY &&
                (p.videoSize.width > 0 || p.currentPosition > 0L)
            if (demarre) { demarrageOk = true; return }
            demarrageEssais++
            val nb = candidates.size.coerceAtLeast(1)
            if (demarrageEssais <= nb) {
                candIdx = (candIdx + 1) % nb
                playCurrent()
                recoveryHandler.postDelayed(this, 9000)
                return
            }
            if (vodUaIdx < UAS_VOD.size - 1) {
                vodUaIdx++
                try { recreate(); return } catch (e: Throwable) {}
            }
            try {
                p.pause()
                androidx.appcompat.app.AlertDialog.Builder(this@PlayerActivity)
                    .setTitle("Chaine indisponible")
                    .setMessage("Le serveur ne renvoie aucune image pour cette chaine. " +
                        "Essaie une autre chaine ou recharge ta liste.")
                    .setPositiveButton("OK") { d, _ -> d.dismiss() }
                    .show()
            } catch (e: Throwable) {}
        }
    }
    private var watchUrl: String = ""
    private var watchTitle: String = ""
    private var watchLogo: String = ""
    private var watchKind: String = "live"
    private var watchSeriesName: String = ""
    private var watchSeriesLogo: String = ""
    private var watchSeriesId: String = ""
    private var watchSeriesCmd: String = ""
    private var watchSourceCmd: String = ""
    private var watchSourceStreamId: String = ""
    private var watchSourceContainerExt: String = ""
    private var didRestorePosition: Boolean = false
    private var isLiveMode: Boolean = false
    private var currentSubUrl: String? = null
    private var currentSubLang: String = ""
    private var currentSubFormat: String = "srt"

    private var topBar: View? = null
    private val barHandler = Handler(Looper.getMainLooper())
    private val hideBarRunnable = Runnable { topBar?.visibility = View.GONE }
    private var audioRecoveryDone = false

    private val recoveryHandler = Handler(Looper.getMainLooper())
    private var lastPos = -1L
    private var lastProgressTs = 0L
    private var liveRetries = 0
    private var workingCandIdx = 0
    private var lastReconnectTs = 0L
    private val stallWatchdog = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && isLiveMode && p.playWhenReady && p.playbackState != Player.STATE_ENDED) {
                val now = SystemClock.elapsedRealtime()
                val pos = p.currentPosition
                if (pos != lastPos) {
                    lastPos = pos
                    lastProgressTs = now
                    liveRetries = 0
                } else {
                    val stalled = now - lastProgressTs
                    val buffering = p.playbackState == Player.STATE_BUFFERING
                    if (lastProgressTs > 0L && ((buffering && stalled > 6000L) || stalled > 10000L)) {
                        reconnectLive()
                    }
                }
            }
            recoveryHandler.postDelayed(this, 1500)
        }
    }

    private var lastFrames = -1L
    private var lastFramesTs = 0L
    private var frameRecoveryCount = 0
    private val frozenImageWatchdog = object : Runnable {
        override fun run() {
            try {
                val p = player
                if (p != null && p.playWhenReady && p.playbackState == Player.STATE_READY &&
                    p.currentTracks.containsType(androidx.media3.common.C.TRACK_TYPE_VIDEO)) {
                    val frames = try {
                        (p.videoDecoderCounters?.renderedOutputBufferCount ?: -1).toLong()
                    } catch (_: Throwable) { -1L }
                    val now = SystemClock.elapsedRealtime()
                    if (frames >= 0L && frames != lastFrames) {
                        lastFrames = frames
                        lastFramesTs = now
                        frameRecoveryCount = 0
                    } else if (frames >= 0L && lastFramesTs > 0L && now - lastFramesTs >= 3500L) {
                        lastFramesTs = now
                        frameRecoveryCount++
                        val oldIdx = candIdx
                        val nextIdx = nextTransportCandidate(oldIdx)
                        if (nextIdx >= 0 && nextIdx != oldIdx) {
                            candIdx = nextIdx
                            workingCandIdx = nextIdx
                            playCurrent()
                        } else if (isLiveMode) {
                            p.stop()
                            playCurrent()
                            try { p.seekToDefaultPosition() } catch (_: Throwable) {}
                        } else {
                            val pos = p.currentPosition
                            p.stop()
                            playCurrent()
                            if (pos > 0L) try { p.seekTo(pos) } catch (_: Throwable) {}
                        }
                    }
                } else {
                    lastFrames = -1L
                    lastFramesTs = SystemClock.elapsedRealtime()
                }
            } catch (_: Throwable) {}
            recoveryHandler.postDelayed(this, 1000L)
        }
    }

    private fun nextTransportCandidate(current: Int): Int {
        if (candidates.size <= 1) return -1
        val order = candidates.indices
            .filter { it != current }
            .sortedBy { if (candidates[it].substringBefore('?').endsWith(".m3u8", true)) 0 else 1 }
        return order.firstOrNull() ?: -1
    }

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
        if(intent.getBooleanExtra("vodCodecRecoveryUsed",false)){
            vodCodecRecovery.markUsed()
            currentSubUrl=intent.getStringExtra("vodCodecSubUrl")
            currentSubLang=intent.getStringExtra("vodCodecSubLang").orEmpty()
            currentSubFormat=intent.getStringExtra("vodCodecSubFormat")?:"srt"
        }
        val requestedUrl=intent.getStringExtra("url").orEmpty()
        if(intent.getBooleanExtra("replay",false)||ReplayUrls.isArchiveUrl(requestedUrl)){
            val replayIntent=android.content.Intent(intent).setClass(this,ReplayPlayerActivity::class.java)
            startActivity(replayIntent);finish();return
        }
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)
        hideSystemBars()
        VideoDecoderPref.resetFor330(this)

        playerView = findViewById(R.id.playerView)
        playerView.controllerShowTimeoutMs = 5000
        playerView.setControllerHideOnTouch(true)
        playerView.isFocusable = true
        playerView.isFocusableInTouchMode = true
        playerView.requestFocus()

        topBar = findViewById<View>(R.id.topBar)
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility -> topBar?.visibility = visibility }
        )

        val title = intent.getStringExtra("title") ?: ""
        val url = intent.getStringExtra("url") ?: ""
        watchUrl = url
        playbackOwnerId=Session.current?.id.orEmpty()
        isReplayMode = intent.getBooleanExtra("replay", false) || ReplayUrls.isArchiveUrl(url)
        watchTitle = title
        watchLogo = intent.getStringExtra("logo") ?: ""
        watchKind = intent.getStringExtra("historyKind") ?: "live"
        watchSeriesName = intent.getStringExtra("seriesName") ?: ""
        watchSeriesLogo = intent.getStringExtra("seriesLogo") ?: ""
        watchSeriesId = intent.getStringExtra("seriesId") ?: ""
        watchSeriesCmd = intent.getStringExtra("seriesCmd") ?: ""
        watchSourceCmd = intent.getStringExtra("historySourceCmd") ?: ""
        watchSourceStreamId = intent.getStringExtra("historySourceStreamId") ?: ""
        watchSourceContainerExt = intent.getStringExtra("historySourceContainerExt") ?: ""
        if (!intent.getBooleanExtra("queued", false)) { Session.episodeQueue = emptyList(); Session.episodeIndex = -1 }
        val mode = intent.getStringExtra("mode") ?: "live"
        val lowerUrl = url.lowercase()
        val pathOnly = lowerUrl.substringBefore('?')
        val looksVod = lowerUrl.contains("/series/") || lowerUrl.contains("/movie/") ||
            pathOnly.endsWith(".mp4") || pathOnly.endsWith(".mkv") || pathOnly.endsWith(".avi") ||
            pathOnly.endsWith(".mov") || pathOnly.endsWith(".flv")
        val isVod = mode == "vod" || looksVod
        isLiveMode = !isVod
        if (isLiveMode) {
            playerView.useController = false
            showTopBarTemporarily()
        }
        if(isLiveMode){val original=Session.zapChannels.getOrNull(Session.zapIndex);if(original!=null&&original.name==watchTitle)LiveHistory.touch(this,original)}
        if (watchKind == "movie" || watchKind == "series") {
            SessionCache.save(this)
            WatchHistory.touch(this, watchUrl, watchTitle, watchLogo, watchKind, watchSeriesName, watchSeriesLogo, watchSeriesId, watchSeriesCmd, watchSourceCmd, watchSourceStreamId, watchSourceContainerExt)
        }
        findViewById<TextView>(R.id.titleTv).text = title
        findViewById<ImageButton>(R.id.backBtn).setOnClickListener { finish() }
        if (isVod) {
            findViewById<View?>(androidx.media3.ui.R.id.exo_settings)?.setOnClickListener {
                showSettingsMenu()
            }
        }

        if (url.isBlank()) {
            Toast.makeText(this, "Flux introuvable", Toast.LENGTH_LONG).show()
            finish(); return
        }

        val plCur = Session.current
        val streamUa = if (plCur != null && plCur.type == "stalker") {
            Api.stalkerHeaders(plCur)["User-Agent"]?.takeIf { it.isNotBlank() }
                ?: "VLC/3.0.20 LibVLC/3.0.20"
        } else if (isVod) {
            UAS_VOD[vodUaIdx.coerceIn(0, UAS_VOD.size - 1)]
        } else {
            UAS_VOD[vodUaIdx.coerceIn(0, UAS_VOD.size - 1)]
        }

        val streamHeaders: Map<String, String> =
            if (plCur != null && plCur.type == "stalker" && isVod && !stalkerVodSansEntetes) {
                try {
                    val h = LinkedHashMap<String, String>()
                    for ((k, v) in Api.stalkerHeaders(plCur)) {
                        if (!k.equals("User-Agent", true) && v.isNotBlank()) h[k] = v
                    }
                    h["Accept"] = "*/*"
                    h
                } catch (e: Throwable) { emptyMap() }
            } else emptyMap()
        
        val uaM3u = try { Api.m3uUserAgents[url] } catch (e: Throwable) { null }
        val replayUa = if (isReplayMode) intent.getStringExtra("forceUa") else null
        val streamUaFinal = if (!replayUa.isNullOrBlank()) replayUa
            else if (!uaM3u.isNullOrBlank()) uaM3u else streamUa
        val httpFactory = KzHttpDataSource.factory(
            this,
            userAgent = streamUaFinal,
            allowCrossProtocolRedirects = true,
            headers = streamHeaders
        )
        val errPolicy = androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(3)
        
        val localScheme = Uri.parse(watchUrl).scheme?.lowercase()
        val sourceFactory: androidx.media3.datasource.DataSource.Factory =
            if (localScheme == "file" || localScheme == "content") {
                androidx.media3.datasource.DefaultDataSource.Factory(this, httpFactory)
            } else httpFactory
        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(sourceFactory)
            .setLoadErrorHandlingPolicy(errPolicy)

        // ---- SOLUTION FINALE : INTERDIRE LE DÉCODEUR ALLWINNER ----
        vodCodecFactory = null 
        val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(
                androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            )
            .setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                val infos = androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT
                    .getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                
                // On bannit le décodeur Allwinner pour le H265 (HDR/DV)
                if (mimeType == androidx.media3.common.MimeTypes.VIDEO_H265) {
                    infos.filter { !it.name.contains("allwinner", ignoreCase = true) }
                } else {
                    infos
                }
            }
        // -----------------------------------------------------------

        if (isReplayMode && intent.getBooleanExtra("replaySoftwareRetry", false)) {
            renderersFactory.setMediaCodecSelector { mime, secure, tunneled ->
                androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT
                    .getDecoderInfos(mime, secure, tunneled)
                    .sortedBy { if (ReplayUrls.isSoftwareDecoder(it.name)) 0 else 1 }
            }
        }

        val playerBuilder = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)
        val loadControl = if (isVod) {
            androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(8000, 60000, 1500, 3000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .setTargetBufferBytes(androidx.media3.common.C.LENGTH_UNSET)
                .build()
        } else {
            androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(10000, 60000, 2500, 5000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .setTargetBufferBytes(androidx.media3.common.C.LENGTH_UNSET)
                .build()
        }
        playerBuilder.setLoadControl(loadControl)
        val p = playerBuilder.build()
        p.addAnalyticsListener(object:androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onVideoDecoderInitialized(eventTime:androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,decoderName:String,initializedTimestampMs:Long,initializationDurationMs:Long){activeVideoDecoderName=decoderName}
        })
        p.setAudioAttributes(
            androidx.media3.common.AudioAttributes.Builder()
                .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .setContentType(
                    if (isVod) androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE
                    else androidx.media3.common.C.AUDIO_CONTENT_TYPE_SPEECH
                )
                .build(),
            true
        )
        p.volume = 1.0f
        if (isVod) {
            var vodParams = p.trackSelectionParameters.buildUpon()
                .setMaxAudioChannelCount(2)
            if (watchKind == "series") {
                vodParams = vodParams.setPreferredAudioLanguages("fr", "fra", "fre")
            }
            p.trackSelectionParameters = vodParams.build()
        }
        try {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setPreferredVideoMimeTypes(
                    androidx.media3.common.MimeTypes.VIDEO_H265,
                    androidx.media3.common.MimeTypes.VIDEO_H264
                )
                .build()
        } catch (e: Throwable) {}
        player = p
        playerView.player = p
        playerView.keepScreenOn = true

        candidates = when {
            isReplayMode && plCur?.type != "stalker" -> {
                val supplied = intent.getStringArrayListExtra("replayCandidates")
                    ?.filter { ReplayUrls.isArchiveUrl(it) }.orEmpty()
                if (supplied.isNotEmpty()) supplied else listOf(url)
            }
            plCur?.type == "stalker" -> listOf(url)
            isVod -> listOf(url)
            else -> buildCandidates(url)
        }
        candIdx = if (isReplayMode) intent.getIntExtra("replayCandidateIndex", 0)
            .coerceIn(0, candidates.lastIndex.coerceAtLeast(0)) else 0
        p.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                if(player!==p||isFinishing||isDestroyed)return
                if(!isLiveMode&&!isReplayMode)preferCompatibleVodTrack(p,tracks)
                if (!isLiveMode && !audioRecoveryDone &&
                    tracks.containsType(androidx.media3.common.C.TRACK_TYPE_AUDIO) &&
                    !tracks.isTypeSelected(androidx.media3.common.C.TRACK_TYPE_AUDIO)) {
                    audioRecoveryDone = true
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setMaxAudioChannelCount(Int.MAX_VALUE)
                        .build()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if(player!==p||isFinishing||isDestroyed)return
                if (playbackState == Player.STATE_READY && !didRestorePosition && (watchKind == "movie" || watchKind == "series")) {
                    didRestorePosition = true
                    val entry=WatchHistory.all(this@PlayerActivity).firstOrNull {it.title==watchTitle&&it.kind==watchKind&&it.playlistId==playbackOwnerId}
                    val resume=entry?.let {if(it.durationMs>0&&it.durationMs-it.positionMs<10000L)0L else it.positionMs.coerceAtLeast(0L)}?:0L
                    val retryResume=intent.getLongExtra("vodCodecResumeMs",-1L)
                    if (retryResume >= 0L) {if(retryResume>0L)p.seekTo(retryResume)}
                    else if (resume > 0L) p.seekTo(resume)
                }
                if (playbackState == Player.STATE_READY && p.playWhenReady && !p.isPlaying) {
                    p.play()
                }
                if (playbackState == Player.STATE_READY) repliEntetesFait = false
                if (playbackState == Player.STATE_READY && p.videoSize.width > 0) {
                    demarrageOk = true
                    recoveryHandler.removeCallbacks(startupWatchdog)
                    recoveryHandler.removeCallbacks(replayStartupWatchdog)
                }
                if (playbackState == Player.STATE_READY && isLiveMode) {
                    workingCandIdx = candIdx
                    liveRetries = 0
                    lastPos = -1L
                    lastProgressTs = SystemClock.elapsedRealtime()
                }
                if (playbackState == Player.STATE_ENDED) {
                    saveWatchProgress(forceCompleted = true)
                    playNextEpisodeIfAny()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if(player!==p||isFinishing||isDestroyed)return
                if(!isLiveMode&&!isReplayMode){
                    recoveryHandler.removeCallbacks(startupWatchdog)
                    val fmt=(error as? androidx.media3.exoplayer.ExoPlaybackException)?.rendererFormat?:p.videoFormat
                    val video=androidx.media3.common.MimeTypes.getTrackType(fmt?.sampleMimeType)==androidx.media3.common.C.TRACK_TYPE_VIDEO
                    val codecFailure=VodPlaybackPolicy.decoderFailure(error.errorCodeName,video)
                    val target=vodCodecFactory?.lastVideoMode=="async-allwinner-hevc"
                    if(codecFailure){
                        if(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)&&vodCodecRecovery.claim(true,target)){
                            val oldEntry=if(!didRestorePosition)WatchHistory.all(this@PlayerActivity).firstOrNull {it.title==watchTitle&&it.kind==watchKind&&it.playlistId==playbackOwnerId} else null
                            val retryPosition=oldEntry?.let {if(it.durationMs>0&&it.durationMs-it.positionMs<10000L)0L else it.positionMs.coerceAtLeast(0L)}?:p.currentPosition.coerceAtLeast(0L)
                            intent.putExtra("vodCodecRecoveryUsed",true)
                                .putExtra("vodCodecResumeMs",retryPosition)
                                .putExtra("vodCodecSubUrl",currentSubUrl)
                                .putExtra("vodCodecSubLang",currentSubLang)
                                .putExtra("vodCodecSubFormat",currentSubFormat)
                                .putExtra("url",watchUrl)
                                .putExtra("vodFirstCodecFailure",error.errorCodeName)
                            saveWatchProgress()
                            closePlaybackError()
                            player=null
                            playerView.player=null
                            try {p.release()}catch(_:RuntimeException){}
                            recreate()
                            return
                        }
                        showVodFailure(error,p)
                        return
                    }
                }
                val httpCode=(error.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
                if(httpCode==429||httpCode==503){
                    recoveryHandler.removeCallbacks(startupWatchdog)
                    showPlaybackError("Lecture impossible : HTTP $httpCode","Le serveur demande de patienter. Aucun changement de variante ni de signature HTTP.")
                    return
                }
                if (isReplayMode) {
                    recoveryHandler.removeCallbacks(replayStartupWatchdog)
                    recoveryHandler.removeCallbacks(startupWatchdog)
                    val replayUrl = candidates.getOrNull(candIdx).orEmpty()
                    if (error.errorCodeName == "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED" &&
                        intent.getStringExtra("replayHlsRetryUrl") != replayUrl) {
                        intent.putExtra("replayHlsRetryUrl", replayUrl)
                        playCurrent()
                        return
                    }
                    if (ReplayUrls.isDecodeFailure(error.errorCodeName) &&
                        !intent.getBooleanExtra("replaySoftwareRetry", false)) {
                        intent.putExtra("replaySoftwareRetry", true)
                        intent.putExtra("replayCandidateIndex", candIdx)
                        intent.putExtra("replayResumeMs", p.currentPosition.coerceAtLeast(0L))
                        recreate()
                        return
                    }
                    if (candIdx < candidates.size - 1) {
                        candIdx++
                        intent.putExtra("replayCandidateIndex", candIdx)
                        playCurrent()
                        return
                    }
                    val u = ReplayUrls.safeDiagnosticUrl(candidates.getOrNull(candIdx).orEmpty())
                    androidx.appcompat.app.AlertDialog.Builder(this@PlayerActivity)
                        .setTitle("Replay impossible : " + error.errorCodeName)
                        .setMessage("Aucune variante d'archive n'a pu etre lue.\n" + u)
                        .setPositiveButton("OK") { d, _ -> d.dismiss() }
                        .show()
                    return
                }
                if (candIdx < candidates.size - 1) {
                    candIdx++
                    playCurrent()
                } else if (isLiveMode) {
                    recoveryHandler.postDelayed({ reconnectLive() }, 1500)
                } else {
                    val c = error.cause
                    val detail = if (c is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)
                        "HTTP ${c.responseCode}" else error.errorCodeName
                    if (!isLiveMode && Session.current?.type == "stalker" && !repliEntetesFait &&
                        c is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
                    ) {
                        repliEntetesFait = true
                        stalkerVodSansEntetes = !stalkerVodSansEntetes
                        try { recreate(); return } catch (e: Throwable) {}
                    }
                    if (!isLiveMode && Session.current?.type != "stalker" &&
                        c is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
                    ) {
                        val code = c.responseCode
                        if (code == 401 || code == 403 || code == 429 || code == 503 || code == 512) {
                            if (!reessai401Fait) {
                                reessai401Fait = true
                                candIdx = 0
                                recoveryHandler.postDelayed({ playCurrent() }, 2500)
                                return
                            }
                            if (vodUaIdx < UAS_VOD.size - 1) {
                                vodUaIdx++
                                reessai401Fait = false
                                try { recreate(); return } catch (e: Throwable) {}
                            }
                        }
                    }
                    showVodFailure(error,p)
                }
            }
        })
        playCurrent()
        lastProgressTs = SystemClock.elapsedRealtime()
        if (isLiveMode) recoveryHandler.postDelayed(stallWatchdog, 2000)
        if (isLiveMode&&!isReplayMode) recoveryHandler.postDelayed(startupWatchdog, 9000)
    }

    private fun showVodFailure(error:PlaybackException,p:ExoPlayer){
        recoveryHandler.removeCallbacks(startupWatchdog)
        val fmt=(error as? androidx.media3.exoplayer.ExoPlaybackException)?.rendererFormat?:p.videoFormat
        val mode=vodCodecFactory?.lastVideoMode?:"standard-synchronous"
        val first=intent.getStringExtra("vodFirstCodecFailure").orEmpty()
        val firstSafe=if(first=="ERROR_CODE_DECODING_FAILED"||first=="ERROR_CODE_DECODER_INIT_FAILED")first else "aucune"
        val diag="KZ PLAYER : ${BuildConfig.VERSION_NAME}\nVideo : $mode\nRecuperation codec : ${if(intent.getBooleanExtra("vodCodecRecoveryUsed",false))"1/1" else "0/1"}\nPremiere erreur codec : $firstSafe\nFormat : ${fmt?.sampleMimeType.orEmpty()} ${fmt?.codecs.orEmpty()} ${fmt?.width?:0} x ${fmt?.height?:0}\nDecodeur : ${activeVideoDecoderName.ifBlank {"non initialise"}}\n1080p : ${if(compatibleVideoSelected)"piste compatible du meme contenu selectionnee" else "aucune substitution ni conversion forcee"}\n${PlaybackFailureDetails.describe(error)}"
        showPlaybackError("Lecture impossible : ${error.errorCodeName}",diag)
    }

    private fun preferCompatibleVodTrack(p:ExoPlayer,tracks:androidx.media3.common.Tracks){
        if(compatibleVideoSelected)return
        val videoGroups=tracks.groups.filter {it.type==androidx.media3.common.C.TRACK_TYPE_VIDEO}
        val unsupportedSelected=videoGroups.any {g->(0 until g.length).any {g.isTrackSelected(it)&&!g.isTrackSupported(it)}}
        if(!unsupportedSelected)return
        var bestGroup:androidx.media3.common.Tracks.Group?=null
        var bestIndex=-1
        var bestPixels=0L
        for(g in videoGroups)for(i in 0 until g.length){
            val f=g.getTrackFormat(i)
            val transfer=f.colorInfo?.colorTransfer
            val hdr=transfer==androidx.media3.common.C.COLOR_TRANSFER_ST2084||transfer==androidx.media3.common.C.COLOR_TRANSFER_HLG
            if(VodPlaybackPolicy.compatible1080(g.isTrackSupported(i),f.sampleMimeType,f.width,f.height,hdr)){
                val pixels=f.width.toLong()*f.height.toLong()
                if(pixels>bestPixels){bestPixels=pixels;bestGroup=g;bestIndex=i}
            }
        }
        val chosen=bestGroup?:return
        compatibleVideoSelected=true
        p.trackSelectionParameters=p.trackSelectionParameters.buildUpon()
            .setOverrideForType(androidx.media3.common.TrackSelectionOverride(chosen.mediaTrackGroup,listOf(bestIndex))).build()
        android.util.Log.i("KZVodCodec","selected existing supported SDR video <=1080p; same media item")
    }

    private fun playCurrent() {
        val p = player ?: return
        val u = candidates.getOrNull(candIdx) ?: return
        closePlaybackError()
        lastFrames = -1L
        lastFramesTs = SystemClock.elapsedRealtime()
        p.setMediaItem(buildMediaItem(u))
        if (isReplayMode) {
            val resume = intent.getLongExtra("replayResumeMs", 0L)
            if (resume > 0L) p.seekTo(resume)
        }
        p.playWhenReady = true
        p.prepare()
        p.play()
        if (isReplayMode) {
            recoveryHandler.removeCallbacks(replayStartupWatchdog)
            recoveryHandler.postDelayed(replayStartupWatchdog, 12000)
        }
    }

    private fun playNextEpisodeIfAny() {
        if (watchKind != "series") return
        val q = Session.episodeQueue
        val nextIdx = Session.episodeIndex + 1
        if (q.isEmpty() || nextIdx < 0 || nextIdx >= q.size) return
        Session.episodeIndex = nextIdx
        val ep = q[nextIdx]
        val series = Session.seriesItem
        val epTitle = if (series != null) "${series.name} - ${ep.name}" else ep.name
        val direct = ep.directUrl
        if (!direct.isNullOrBlank()) { setEpisodeSource(ep);switchToVod(direct, epTitle, ep.logo); return }
        val cmd = ep.cmd
        val pl = Session.current
        if (cmd.isNullOrBlank() || pl == null) return
        lifecycleScope.launch {
            val link = try { Api.stalkerLink(pl, cmd, "movie") } catch (e: Exception) { null }
            if (!link.isNullOrBlank()&&!isFinishing&&!isDestroyed) {setEpisodeSource(ep);switchToVod(link, epTitle, ep.logo)}
        }
    }

    private fun setEpisodeSource(ep:Item){
        watchSourceCmd=ep.cmd.orEmpty()
        watchSourceStreamId=ep.streamId.orEmpty()
        watchSourceContainerExt=ep.containerExt.orEmpty()
    }

    private var zapEnCours = false
    private fun zapChannel(delta: Int) {
        if (!isLiveMode) return
        if (zapEnCours) return
        val chans = Session.zapChannels
        if (chans.isEmpty()) return
        var idx = Session.zapIndex
        if (idx < 0) idx = 0
        val newIdx = ((idx + delta) % chans.size + chans.size) % chans.size
        if (newIdx == idx && chans.size == 1) return
        val next = chans[newIdx]
        Session.zapIndex = newIdx
        val previousOwner=Session.current?.id
        LiveHistory.activateOwner(next)
        LiveHistory.touch(this,next)
        if(Session.current?.id!=previousOwner){
            val owner=Session.current?:return
            zapEnCours=true
            lifecycleScope.launch {
                val link=if(owner.type=="stalker"&&!next.cmd.isNullOrBlank())try{Api.stalkerLink(owner,next.cmd!!,"live")}catch(_:Exception){null}else next.directUrl
                if(!link.isNullOrBlank()){
                    intent.putExtra("url",link).putExtra("title",next.name).putExtra("logo",next.logo)
                        .putExtra("historySourceCmd",next.cmd.orEmpty()).putExtra("historySourceStreamId",next.streamId.orEmpty())
                    recreate()
                }else zapEnCours=false
            }
            return
        }
        val pl = Session.current
        try { Toast.makeText(this, next.name, Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
        zapEnCours = true
        val direct = next.directUrl
        if (!direct.isNullOrBlank()) {
            switchToLive(next, direct)
            zapEnCours = false
            return
        }
        val cmd = next.cmd
        if (pl != null && pl.type == "stalker" && !cmd.isNullOrBlank()) {
            lifecycleScope.launch {
                val link = try { Api.stalkerLink(pl, cmd, "live") } catch (e: Exception) { null }
                if (!link.isNullOrBlank()) switchToLive(next, link)
                zapEnCours = false
            }
        } else {
            zapEnCours = false
        }
    }

    private fun switchToLive(item: Item, url: String) {
        val p = player ?: return
        watchUrl = url
        watchTitle = item.name
        if (item.logo.isNotBlank()) watchLogo = item.logo
        findViewById<TextView>(R.id.titleTv).text = item.name
        candidates = buildCandidates(url)
        candIdx = 0
        workingCandIdx = -1
        liveRetries = 0
        demarrageOk = false
        demarrageEssais = 0
        recoveryHandler.removeCallbacks(startupWatchdog)
        recoveryHandler.removeCallbacks(stallWatchdog)
        playCurrent()
        showTopBarTemporarily()
        recoveryHandler.postDelayed(startupWatchdog, 9000)
        recoveryHandler.postDelayed(stallWatchdog, 2000)
    }

    private fun switchToVod(url: String, title: String, logo: String) {
        val p = player ?: return
        vodCodecRecovery.reset()
        compatibleVideoSelected=false
        intent.removeExtra("vodCodecRecoveryUsed")
        intent.removeExtra("vodCodecResumeMs")
        intent.removeExtra("vodFirstCodecFailure")
        intent.removeExtra("vodCodecSubUrl")
        intent.removeExtra("vodCodecSubLang")
        intent.removeExtra("vodCodecSubFormat")
        p.trackSelectionParameters=p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_VIDEO).build()
        watchUrl = url
        watchTitle = title
        watchLogo = logo
        intent.putExtra("url",url).putExtra("title",title).putExtra("logo",logo)
            .putExtra("historyKind","series").putExtra("mode","vod")
            .putExtra("historySourceCmd",watchSourceCmd).putExtra("historySourceStreamId",watchSourceStreamId)
            .putExtra("historySourceContainerExt",watchSourceContainerExt)
        findViewById<TextView>(R.id.titleTv).text = title
        WatchHistory.touch(this, watchUrl, watchTitle, watchLogo, "series", watchSeriesName, watchSeriesLogo, watchSeriesId, watchSeriesCmd, watchSourceCmd, watchSourceStreamId, watchSourceContainerExt)
        didRestorePosition = true
        audioRecoveryDone = false
        currentSubUrl = null
        candidates = listOf(url)
        candIdx = 0
        playCurrent()
    }

    private fun buildMediaItem(u: String): MediaItem {
        val b = MediaItem.Builder().setUri(Uri.parse(u))
        if (isReplayMode) {
            val mime = when {
                intent.getStringExtra("replayHlsRetryUrl") == u -> "application/x-mpegURL"
                u == watchUrl -> intent.getStringExtra("mime").orEmpty()
                else -> ""
            }
            if (mime.isNotBlank()) b.setMimeType(mime)
        }
        val sub = currentSubUrl
        if (!sub.isNullOrBlank()) {
            val mime = when (currentSubFormat.lowercase()) {
                "vtt" -> androidx.media3.common.MimeTypes.TEXT_VTT
                "ass", "ssa" -> androidx.media3.common.MimeTypes.TEXT_SSA
                else -> androidx.media3.common.MimeTypes.APPLICATION_SUBRIP
            }
            val subCfg = MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub))
                .setMimeType(mime)
                .setLanguage(currentSubLang.ifBlank { "und" })
                .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT)
                .build()
            b.setSubtitleConfigurations(listOf(subCfg))
        }
        return b.build()
    }

    private fun showSettingsMenu() {
        val items = arrayOf("Audio", "Sous-titres")
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_KZ_Dialog)
            .setTitle("Param\u00e8tres")
            .setItems(items) { _, which ->
                if (which == 0) showAudioMenu() else showSubtitleMenu()
            }
            .show()
    }

    private fun showAudioMenu() {
        val p = player ?: return
        val groups = p.currentTracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }
        if (groups.isEmpty()) {
            Toast.makeText(this, "Aucune piste audio.", Toast.LENGTH_SHORT).show(); return
        }
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        groups.forEachIndexed { gi, g ->
            for (ti in 0 until g.length) {
                val fmt = g.getTrackFormat(ti)
                val base = langName(fmt.language ?: "").ifBlank { "Piste ${gi + 1}" }
                val ch = if (fmt.channelCount > 0) " (${fmt.channelCount}ch)" else ""
                labels.add((if (g.isTrackSelected(ti)) "\u25cf " else "") + base + ch)
                actions.add { selectTrack(androidx.media3.common.C.TRACK_TYPE_AUDIO, g, ti) }
            }
        }
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_KZ_Dialog)
            .setTitle("Audio")
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    private fun showSubtitleMenu() {
        val p = player ?: return
        val groups = p.currentTracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT }
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        labels.add("D\u00e9sactiver"); actions.add { disableSubtitles() }
        groups.forEach { g ->
            for (ti in 0 until g.length) {
                val fmt = g.getTrackFormat(ti)
                val name = fmt.label ?: langName(fmt.language ?: "").ifBlank { "Piste" }
                labels.add((if (g.isTrackSelected(ti)) "\u25cf " else "") + name + "  (flux)")
                actions.add { selectTrack(androidx.media3.common.C.TRACK_TYPE_TEXT, g, ti) }
            }
        }
        labels.add("Chercher en ligne\u2026"); actions.add { searchSubtitlesOnline() }
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_KZ_Dialog)
            .setTitle("Sous-titres")
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    private fun selectTrack(type: Int, g: androidx.media3.common.Tracks.Group, trackIndex: Int) {
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, false)
            .setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, trackIndex))
            .build()
        if (type == androidx.media3.common.C.TRACK_TYPE_TEXT) {
            currentSubUrl = null
            Toast.makeText(this, "Sous-titres du flux activ\u00e9s", Toast.LENGTH_SHORT).show()
        }
    }

    private fun searchSubtitlesOnline() {
        val query = watchSeriesName.ifBlank { watchTitle }
        if (query.isBlank()) {
            Toast.makeText(this, "Titre inconnu pour la recherche.", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Recherche de sous-titres\u2026", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val results = SubtitlesApi.search(query, SubtitlesConfig.SUBLANGUAGE_IDS)
            if (results.isEmpty()) {
                Toast.makeText(this@PlayerActivity, "Aucun sous-titre trouv\u00e9 pour ce titre.", Toast.LENGTH_LONG).show()
                return@launch
            }
            val labels = results.map { "${langName(it.lang)}  \u2014  ${it.release.take(45)}" }.toTypedArray()
            androidx.appcompat.app.AlertDialog.Builder(this@PlayerActivity, R.style.Theme_KZ_Dialog)
                .setTitle("Choisir la langue")
                .setItems(labels) { _, which -> applyOnlineSubtitle(results[which]) }
                .show()
        }
    }

    private fun applyOnlineSubtitle(opt: SubtitlesApi.SubOption) {
        Toast.makeText(this, "T\u00e9l\u00e9chargement des sous-titres\u2026", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val local = SubtitlesApi.downloadToFile(this@PlayerActivity, opt)
            if (local.isNullOrBlank()) {
                Toast.makeText(this@PlayerActivity, "\u00c9chec du t\u00e9l\u00e9chargement des sous-titres.", Toast.LENGTH_LONG).show()
                return@launch
            }
            currentSubUrl = local
            currentSubLang = opt.lang
            currentSubFormat = opt.format
            reloadWithSubtitle()
        }
    }

    private fun reloadWithSubtitle() {
        val p = player ?: return
        val u = candidates.getOrNull(candIdx) ?: watchUrl
        val pos = p.currentPosition
        p.setMediaItem(buildMediaItem(u))
        p.prepare()
        if (pos > 0) p.seekTo(pos)
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false)
            .setPreferredTextLanguage(currentSubLang.ifBlank { "und" })
            .build()
        p.playWhenReady = true
        p.play()
        Toast.makeText(this, "Sous-titres activ\u00e9s (${langName(currentSubLang)})", Toast.LENGTH_SHORT).show()
    }

    private fun disableSubtitles() {
        currentSubUrl = null
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguage(null)
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
            .build()
        Toast.makeText(this, "Sous-titres d\u00e9sactiv\u00e9s", Toast.LENGTH_SHORT).show()
    }

    private fun langName(code: String): String = when (code.lowercase().take(2)) {
        "fr" -> "Fran\u00e7ais"
        "en" -> "Anglais"
        "es" -> "Espagnol"
        "ar" -> "Arabe"
        "pt" -> "Portugais"
        "de" -> "Allemand"
        "it" -> "Italien"
        "nl" -> "N\u00e9erlandais"
        "ru" -> "Russe"
        "tr" -> "Turc"
        else -> code
    }

    private fun reconnectLive() {
        val p = player ?: return
        if (!isLiveMode) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastReconnectTs < 2500L) return
        lastReconnectTs = now
        liveRetries++
        if (liveRetries > 200) return
        candIdx = if (liveRetries % 3 == 0 && candidates.size > 1)
            (workingCandIdx + 1) % candidates.size
        else workingCandIdx.coerceIn(0, (candidates.size - 1).coerceAtLeast(0))
        lastPos = -1L
        lastProgressTs = now
        playCurrent()
        try { p.seekToDefaultPosition() } catch (e: Exception) {}
    }

    private fun buildVodCandidates(url: String): List<String> {
        val scheme = Uri.parse(url).scheme?.lowercase()
        if (scheme == "file" || scheme == "content") return listOf(url)
        val list = LinkedHashSet<String>()
        list.add(url)

        fun replaceExt(from: String, to: String) {
            val re = Regex("(?i)\\.$from(?=(&|\\?|$))")
            val replaced = url.replace(re, ".$to")
            if (replaced != url) list.add(replaced)
        }

        for (e in listOf("mkv", "mp4", "ts", "avi", "m4v")) {
            replaceExt("mkv", e); replaceExt("mp4", e); replaceExt("ts", e)
            replaceExt("avi", e); replaceExt("m4v", e)
        }
        for (x in listOf("mkv", "mp4", "ts", "avi", "m4v")) {
            val re = Regex("(?i)\\." + x + "(?=(&|\\?|$))")
            val sans = url.replace(re, "")
            if (sans != url) list.add(sans)
        }
        val qm = url.indexOf('?')
        val basePath = if (qm >= 0) url.substring(0, qm) else url
        val query = if (qm >= 0) url.substring(qm) else ""
        if (!basePath.substringAfterLast('/').contains('.')) {
            for (e in listOf("mkv", "mp4", "ts", "avi")) list.add(basePath + "." + e + query)
        }
        
        return list.filter {ReferenceTransportGuard.sameResource(url,it)}
    }

    private fun buildCandidates(url: String): List<String> {
        val list = LinkedHashSet<String>()
        fun addVariants(u: String) {
            list.add(u)
            val q = u.indexOf('?')
            val path = if (q >= 0) u.substring(0, q) else u
            val query = if (q >= 0) u.substring(q) else ""
            when {
                path.endsWith(".ts", true) -> {
                    list.add(path.dropLast(3) + ".m3u8" + query)
                    list.add(path.dropLast(3) + query)
                }
                path.endsWith(".m3u8", true) -> {
                    list.add(path.dropLast(5) + ".ts" + query)
                    list.add(path.dropLast(5) + query)
                }
                else -> {
                    val lastSeg = path.substringAfterLast('/')
                    if (!lastSeg.contains('.')) {
                        list.add(path + ".ts" + query)
                        list.add(path + ".m3u8" + query)
                    }
                }
            }
        }
        addVariants(url)
        if (url.contains("/live/")) addVariants(url.replace("/live/", "/"))
        return list.toList()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val p = player ?: return super.dispatchKeyEvent(event)
        val controllerVisible = playerView.isControllerFullyVisible
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (isLiveMode) {
                showTopBarTemporarily()
                when (event.keyCode) {
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> return super.dispatchKeyEvent(event)
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { zapChannel(-1); return true }
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { zapChannel(+1); return true }
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND -> return true
                }
            }
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    if (controllerVisible) { playerView.hideController(); return true }
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlay(); playerView.showController(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { p.play(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { p.pause(); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(30000); playerView.showController(); return true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-10000); playerView.showController(); return true }
                KeyEvent.KEYCODE_CAPTIONS, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> {
                    showSubtitleMenu(); return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (!controllerVisible) { togglePlay(); playerView.showController(); return true }
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (!controllerVisible) { seekBy(-10000); playerView.showController(); return true }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (!controllerVisible) { seekBy(30000); playerView.showController(); return true }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showTopBarTemporarily() {
        val bar = topBar ?: return
        bar.visibility = View.VISIBLE
        barHandler.removeCallbacks(hideBarRunnable)
        barHandler.postDelayed(hideBarRunnable, 4000)
    }

    private fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    private fun seekBy(ms: Long) {
        val p = player ?: return
        val target = (p.currentPosition + ms).coerceAtLeast(0)
        val dur = p.duration
        p.seekTo(if (dur > 0) target.coerceAtMost(dur) else target)
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onResume() {
        super.onResume()
        LicenseEnforcer.resume(this)
    }

    override fun onPause() {
        LicenseEnforcer.pause(this)
        super.onPause()
    }

    override fun onStop() {
        closePlaybackError()
        saveWatchProgress()
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        closePlaybackError()
        recoveryHandler.removeCallbacksAndMessages(null)
        barHandler.removeCallbacksAndMessages(null)
        saveWatchProgress()
        super.onDestroy()
        player?.release()
        player = null
    }

    private fun saveWatchProgress(forceCompleted: Boolean = false) {
        if (watchKind != "movie" && watchKind != "series") return
        val p = player ?: return
        val dur = p.duration
        val pos = if (forceCompleted && dur > 0L) dur else p.currentPosition
        if (pos < 3000L || dur <= 0L) return
        WatchHistory.save(this, watchUrl, watchTitle, watchLogo, watchKind, pos, dur, watchSeriesName, watchSeriesLogo, watchSeriesId, watchSeriesCmd, watchSourceCmd, watchSourceStreamId, watchSourceContainerExt, ownerPlaylistId=playbackOwnerId)
    }
}