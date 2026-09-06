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
    private lateinit var playerView: PlayerView
    private var candidates: List<String> = emptyList()
    private var candIdx = 0
    // v386 : retenu le temps de la session. true = les films/series Stalker sont demandes
    // sans les en-tetes MAG (repli automatique quand le serveur les refuse).
    companion object {
        private var stalkerVodSansEntetes = false
        // v388 : certains panels IPTV refusent le flux (HTTP 401 / 403) quand le lecteur
        // ne se presente pas comme celui qu ils attendent. Selon le serveur du client, ce
        // n est pas la meme signature qui passe : on les essaie donc toutes, automatiquement.
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
    // v388 : un seul nouvel essai immediat par lecture (limite de connexions du serveur).
    private var reessai401Fait = false

    // v389 : ROND DE CHARGEMENT QUI TOURNE EN BOUCLE SANS AFFICHER LA CHAINE.
    // Le serveur accepte la connexion mais n envoie jamais d image : ExoPlayer reste
    // en chargement indefiniment et aucune erreur n arrive, donc rien ne se declenchait.
    // On surveille donc le demarrage : si rien ne s affiche au bout de 9 s, on essaie
    // automatiquement l adresse suivante, puis une autre signature de lecteur.
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
                // Adresse suivante du meme flux (.ts, .m3u8, sans extension, sans /live/...).
                candIdx = (candIdx + 1) % nb
                playCurrent()
                recoveryHandler.postDelayed(this, 9000)
                return
            }
            if (vodUaIdx < UAS_VOD.size - 1) {
                // Toutes les adresses ont ete essayees : on se presente autrement au serveur.
                vodUaIdx++
                try { recreate(); return } catch (e: Throwable) {}
            }
            // Vraiment rien ne part : on arrete le rond et on le dit clairement.
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
    // Sous-titres externes (OpenSubtitles) charges par-dessus la video en cours.
    private var currentSubUrl: String? = null
    private var currentSubLang: String = ""
    private var currentSubFormat: String = "srt"

    // Barre du haut (titre + bouton X) : masquee automatiquement en direct.
    private var topBar: View? = null
    private val barHandler = Handler(Looper.getMainLooper())
    private val hideBarRunnable = Runnable { topBar?.visibility = View.GONE }
    // Recuperation audio VOD : si des pistes audio existent mais qu'aucune n'est selectionnee
    // (souvent une piste multicanal exclue par la limite stereo), on relache la limite une fois.
    private var audioRecoveryDone = false

    // Reconnexion automatique du direct (chaine qui se fige / coupe apres quelques minutes).
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
                    // Image figee / son coupe : la lecture n'avance plus depuis trop longtemps.
                    // v388 : detection plus rapide (avant : 12 s / 20 s d image figee).
                    if (lastProgressTs > 0L && ((buffering && stalled > 6000L) || stalled > 10000L)) {
                        reconnectLive()
                    }
                }
            }
            recoveryHandler.postDelayed(this, 1500)
        }
    }

    // v380 : IMAGE FIGEE (le son continue, l image ne bouge plus).
    // Aucune reconnexion, aucun rechargement, aucun rebuffer : on se contente de
    // rebrancher l affichage sur le lecteur, ce qui recree la surface de dessin.
    // La lecture ne s arrete pas une seule fois, le flux n est pas retelecharge.
    // v390 : nombre de gels non recuperes pendant cette lecture.
    private var gelsGraves = 0
    private var lastFrames = -1L
    private var lastFramesTs = 0L
    private var surfaceKicks = 0
    private val frozenImageWatchdog = object : Runnable {
        override fun run() {
            try {
                val p = player
                if (p != null && p.isPlaying && p.videoSize.width > 0) {
                    val frames = try {
                        (p.videoDecoderCounters?.renderedOutputBufferCount ?: 0).toLong()
                    } catch (e: Throwable) { -1L }
                    val now = SystemClock.elapsedRealtime()
                    if (frames < 0L) {
                        // Compteur indisponible : on ne fait rien du tout.
                    } else if (frames != lastFrames) {
                        // Des images arrivent : tout va bien, on remet le compteur a zero.
                        lastFrames = frames
                        lastFramesTs = now
                        surfaceKicks = 0
                    } else if (lastFramesTs > 0L && now - lastFramesTs > 5000L && surfaceKicks < 3) {
                        // Plus aucune image depuis 5 s alors que la lecture tourne :
                        // on rebranche juste l affichage.
                        surfaceKicks++
                        lastFramesTs = now
                        try {
                            playerView.player = null
                            playerView.player = p
                        } catch (e: Throwable) {}
                    } else if (lastFramesTs > 0L && now - lastFramesTs > 5000L) {
                        // v387 : rebrancher l affichage n a pas suffi (3 essais) => l image est
                        // vraiment bloquee. On relance le flux au lieu de laisser l ecran fige.
                        // En direct : reconnexion silencieuse. En film/serie : on reprend a la
                        // seconde ou on etait, sans rien afficher a l ecran.
                        lastFramesTs = now
                        surfaceKicks = 0
                        gelsGraves++
                        // v390 : 2 gels non recuperes = le decodeur video de cet appareil
                        // ne suit pas ce flux. On bascule CET appareil en decodeur logiciel
                        // (retenu pour la suite) et on relance proprement la lecture.
                        if (gelsGraves >= 2 && !VideoDecoderPref.autoSoftware(this@PlayerActivity)) {
                            VideoDecoderPref.noteFreeze(this@PlayerActivity)
                            VideoDecoderPref.setAutoSoftware(this@PlayerActivity, true)
                            try { recreate(); return } catch (e: Throwable) {}
                        }
                        try {
                            if (isLiveMode) {
                                reconnectLive()
                            } else {
                                val pos = p.currentPosition
                                playCurrent()
                                if (pos > 0L) p.seekTo(pos)
                            }
                        } catch (e: Throwable) {}
                    }
                } else {
                    lastFramesTs = SystemClock.elapsedRealtime()
                }
            } catch (e: Throwable) {}
            recoveryHandler.postDelayed(this, 2000)
        }
    }


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
        // Plein ecran paysage
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)
        hideSystemBars()

        playerView = findViewById(R.id.playerView)
        // Navigation telecommande : le controleur reste affiche un peu plus longtemps
        playerView.controllerShowTimeoutMs = 5000
        playerView.setControllerHideOnTouch(true)
        playerView.isFocusable = true
        playerView.isFocusableInTouchMode = true
        playerView.requestFocus()

        // La barre du haut (titre + retour) suit l'affichage des commandes :
        // elle disparait en meme temps que le "menu" lecture/avance.
        topBar = findViewById<View>(R.id.topBar)
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility -> topBar?.visibility = visibility }
        )

        val title = intent.getStringExtra("title") ?: ""
        val url = intent.getStringExtra("url") ?: ""
        watchUrl = url
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
        // Lecture a la suite : on ne conserve la file d'episodes que si on vient d'une liste d'episodes.
        if (!intent.getBooleanExtra("queued", false)) { Session.episodeQueue = emptyList(); Session.episodeIndex = -1 }
        // mode = "live" par defaut ; "vod" pour films/episodes.
        // Securite : on detecte aussi automatiquement les VOD par URL, au cas ou un ecran
        // n'envoie pas l'extra mode=vod (ex: series M3U /series/... ou fichiers mp4/mkv/avi).
        val mode = intent.getStringExtra("mode") ?: "live"
        val lowerUrl = url.lowercase()
        val pathOnly = lowerUrl.substringBefore('?')
        val looksVod = lowerUrl.contains("/series/") || lowerUrl.contains("/movie/") ||
            pathOnly.endsWith(".mp4") || pathOnly.endsWith(".mkv") || pathOnly.endsWith(".avi") ||
            pathOnly.endsWith(".mov") || pathOnly.endsWith(".flv")
        val isVod = mode == "vod" || looksVod
        isLiveMode = !isVod
        if (isLiveMode) {
            // Chaines live : pas de boutons play/pause. On garde juste la barre du haut (titre + retour).
            playerView.useController = false
            // Live : la barre du haut (titre + X) s'affiche puis disparait toute seule apres 4 s.
            showTopBarTemporarily()
        }
        if (watchKind == "movie" || watchKind == "series") {
            WatchHistory.touch(this, watchUrl, watchTitle, watchLogo, watchKind, watchSeriesName, watchSeriesLogo, watchSeriesId, watchSeriesCmd, watchSourceCmd, watchSourceStreamId, watchSourceContainerExt)
        }
        findViewById<TextView>(R.id.titleTv).text = title
        findViewById<ImageButton>(R.id.backBtn).setOnClickListener { finish() }
        // Roue dentee (parametres) du lecteur : pour les films/series, on remplace le menu
        // par notre propre menu (Audio + Sous-titres), afin que le bouton sous-titres soit
        // DANS la roue dentee, juste sous "Audio", comme demande.
        if (isVod) {
            findViewById<View?>(androidx.media3.ui.R.id.exo_settings)?.setOnClickListener {
                showSettingsMenu()
            }
        }

        if (url.isBlank()) {
            Toast.makeText(this, "Flux introuvable", Toast.LENGTH_LONG).show()
            finish(); return
        }

        // v149 : httpFactory delegue a KzHttpDataSource qui applique le DNS choisi.
        // Mode DnsPref.SYSTEM (defaut) -> DefaultHttpDataSource inchange (aucun risque de regression).
        // Mode DoH -> OkHttpDataSource + resolveur DNS-over-HTTPS.
        val plCur = Session.current
        val streamUa = if (plCur != null && plCur.type == "stalker") {
            // Pour Stalker : User-Agent MAG uniquement (voir historique - envoyer Cookie/Referer casse
            // la redirection 302 vers le vrai serveur de streaming avec token dans l'URL).
            Api.stalkerHeaders(plCur)["User-Agent"]?.takeIf { it.isNotBlank() }
                ?: "VLC/3.0.20 LibVLC/3.0.20"
        } else if (isVod) {
            // v388 : films / episodes Xtream et M3U. On part sur VLC (le plus accepte) et
            // l appli change toute seule de signature si le serveur refuse (HTTP 401/403).
            UAS_VOD[vodUaIdx.coerceIn(0, UAS_VOD.size - 1)]
        } else {
            // v389 : le DIRECT aussi. On demarre sur VLC (comportement identique a avant
            // pour tous ceux chez qui ca marche) et on ne change de signature que si la
            // chaine refuse de partir.
            UAS_VOD[vodUaIdx.coerceIn(0, UAS_VOD.size - 1)]
        }
// v386 : FILMS / SERIES STALKER refuses par le serveur (HTTP 458, 403, 405...).
        // Le lien de create_link est bon, mais certains portails n acceptent le flux que si
        // la requete arrive avec les MEMES en-tetes que le boitier MAG (Cookie mac, Referer,
        // X-User-Agent, jeton). On les ajoute donc pour les films et series Stalker.
        // Le DIRECT n est pas touche : il continue a partir avec le User-Agent seul, comme
        // avant (y ajouter Cookie/Referer cassait la redirection 302 du live).
        // Si le serveur refuse quand meme, l appli reessaie automatiquement une fois sans ces
        // en-tetes (voir plus bas) : les deux methodes sont donc couvertes.
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
        // v390 : beaucoup de playlists M3U imposent leur propre User-Agent
        // (#EXTVLCOPT:http-user-agent / #EXTHTTP). Sans lui, le serveur ne renvoie
        // rien du tout : la chaine ne se lancait pas. On l utilise quand il existe.
        val uaM3u = try { Api.m3uUserAgents[url] } catch (e: Throwable) { null }
        val streamUaFinal = if (!uaM3u.isNullOrBlank()) uaM3u else streamUa
        val httpFactory = KzHttpDataSource.factory(
            this,
            userAgent = streamUaFinal,
            allowCrossProtocolRedirects = true,
            headers = streamHeaders
        )
        // v389 : 2 essais suffisent pour un morceau de flux ; au dela, le rond de
        // chargement tournait pendant des dizaines de secondes sans rien afficher.
        // v388 : un morceau de flux qui repond mal est reessaye plusieurs fois avant
        // d abandonner (sur les connexions instables l image se figeait tout de suite).
        val errPolicy = androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(3)
        val mediaSourceFactory = if (isVod) {
            // Films / episodes : lecteur VOD standard. Pas de flags TS live, sinon certains VOD
            // chargent la duree mais restent figes sans son.
            androidx.media3.exoplayer.source.DefaultMediaSourceFactory(httpFactory)
                .setLoadErrorHandlingPolicy(errPolicy)
        } else {
            // Live IPTV : beaucoup de flux sont du MPEG-TS brut sans IDR/AUD.
            val extractors = androidx.media3.extractor.DefaultExtractorsFactory()
                .setTsExtractorFlags(
                    androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                        androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
                )
            androidx.media3.exoplayer.source.DefaultMediaSourceFactory(httpFactory, extractors)
                .setLoadErrorHandlingPolicy(errPolicy)
        }
        // v147 : usine de renderers KZ qui donne la priorite au decodeur video LOGICIEL
        // (fixe l'image figee sur les box dont le decodeur materiel plante silencieusement).
        // setEnableDecoderFallback(true) est deja active dans KzRenderersFactory.
// v385 : en DIRECT, c est la puce video qui decode (sauf si tu as choisi
        // "logiciel" dans les reglages). Les chaines Xtream n indiquent pas leur qualite
        // dans leur nom : beaucoup de chaines 1080i / 50 images ne contenaient ni "FHD"
        // ni "4K", elles partaient donc sur le decodeur logiciel, incapable de suivre
        // -> saccades. Le decodeur logiciel reste juste derriere, en secours automatique.
        // v390 : le decodeur ne depend plus du nom de la chaine (FHD, 4K...) : ces
        // etiquettes sont absentes chez beaucoup de fournisseurs, donc des chaines HD
        // partaient en logiciel et saccadaient. Le mode AUTO gere tout, pour tous.
        val renderersFactory = KzRenderersFactory(this, false)
            // CORRECTION SYNC SON/IMAGE (v86) :
            // - VOD/series : EXTENSION_RENDERER_MODE_ON => on prefere le decodeur AUDIO MATERIEL
            //   (AAC/H264 parfaitement synchronises). Avant, le mode PREFER forcait FFmpeg logiciel
            //   sur tout l'audio, ce qui faisait deriver le son par rapport a l'image sur les episodes.
            //   FFmpeg reste utilise EN SECOURS pour AC3/EAC3/DTS grace a setEnableDecoderFallback(true)
            //   (si le materiel n'a pas le codec ou le decode mal, ExoPlayer bascule sur FFmpeg).
            // - Live : on garde PREFER (FFmpeg prioritaire) pour ne rien casser cote Stalker/MAG.
            .setExtensionRendererMode(
                if (isVod)
                    androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                else
                    androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            )

        val playerBuilder = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            // v388 : empeche le boitier / telephone de mettre le Wi-Fi en veille pendant
            // la lecture (cause frequente d image figee au bout de quelques secondes).
            .setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)
        val loadControl = if (isVod) {
            // VOD/films/series : buffer equilibre.
            // v38 etait tres stable mais un peu lent au demarrage ; ici on garde assez de marge
            // pour l'audio Stalker/FFmpeg tout en reduisant la latence de lancement et de reprise.
            androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(8000, 60000, 1500, 3000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .setTargetBufferBytes(androidx.media3.common.C.LENGTH_UNSET)
                .build()
        } else {
            // v387 : le direct demarrait avec seulement 1,5 s d avance et relancait des 0,5 s.
            // Au moindre a-coup du serveur l avance tombait a zero => saccades puis image figee.
            // On garde une vraie reserve d avance (comme un boitier) : demarrage a peine plus
            // long (~1 s) mais lecture fluide et plus de blocage.
            androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(10000, 60000, 2500, 5000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .setTargetBufferBytes(androidx.media3.common.C.LENGTH_UNSET)
                .build()
        }
        playerBuilder.setLoadControl(loadControl)
        val p = playerBuilder.build()
        // Correction audio VOD/series : on force un vrai flux MEDIA + volume max.
        // Certains boitiers Android TV lancent la video mais ne prennent pas le focus audio
        // correctement si les attributs ne sont pas explicites.
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
            // Optimisation audio VOD : si plusieurs pistes existent, ExoPlayer prefere une piste compatible
            // avec peu de canaux (stereo) plutot qu'une piste 5.1/DTS que le boitier ne sort pas.
            var vodParams = p.trackSelectionParameters.buildUpon()
                .setMaxAudioChannelCount(2)
            // Series multi-langues : on selectionne automatiquement le francais quand il existe.
            // C'est une PREFERENCE (pas une contrainte) : si le francais est absent, ExoPlayer garde
            // la piste par defaut, donc jamais de perte de son. Codes fr / fra / fre couverts.
            if (watchKind == "series") {
                vodParams = vodParams.setPreferredAudioLanguages("fr", "fra", "fre")
            }
            p.trackSelectionParameters = vodParams.build()
        }
        // v385 : IMAGE TRES SOMBRE sur les contenus HDR / Dolby Vision. Si le flux
        // propose plusieurs pistes video, on prefere la piste HEVC ou H264 classique
        // plutot que la piste Dolby Vision, que la plupart des boitiers ne savent pas
        // convertir (resultat : image tres sombre et delavee).
        // Simple preference : si le flux n a qu une seule piste, rien ne change.
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

        // Certains serveurs IPTV ne servent pas le live en .ts (mais en .m3u8) ou sans extension :
        // on prepare une liste d'URL candidates et on bascule automatiquement si une renvoie une
        // erreur HTTP (ERROR_CODE_IO_BAD_HTTP_STATUS, etc.).
        // Pour un flux Stalker, l'URL de create_link redirige (302) vers le vrai serveur de flux :
        // il ne faut PAS lui coller des variantes .ts/.m3u8 (faux 404 qui masquent la vraie reponse).
        // On la joue telle quelle et on suit la redirection, exactement comme un vrai boitier.
        candidates = when {
            // v385 : STALKER, films et series compris. Le lien renvoye par create_link est
            // valable une seule fois et le portail refuse toute URL differente (HTTP 405).
            // On joue donc EXACTEMENT l'URL donnee par le portail, sans jamais changer
            // l'extension. Avant, l'appli essayait .ts / .mp4 / .avi a la place du .mkv
            // fourni : le serveur repondait 405 et le film ne partait pas.
            plCur?.type == "stalker" -> listOf(url)
            // VOD/episodes (Xtream, M3U) : on garde l'URL propre, mais si le portail renvoie
            // du .avi non decodable par Android, on tente les containers alternatifs.
            isVod -> buildVodCandidates(url)
            else -> buildCandidates(url)
        }
        candIdx = 0
        p.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                // Film/serie sans son : il y a des pistes audio mais aucune n'est selectionnee.
                // On relache la limite de canaux (une seule fois) pour qu'une piste soit choisie
                // -> retablit le son sans changer le comportement des fichiers qui ont deja du son.
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
                // Certains VOD/episodes chargent bien la duree (STATE_READY) mais restent figes
                // sans demarrer automatiquement sur Android TV. On force le play au moment READY.
                if (playbackState == Player.STATE_READY && !didRestorePosition && (watchKind == "movie" || watchKind == "series")) {
                    didRestorePosition = true
                    val resume = WatchHistory.positionForTitle(this@PlayerActivity, watchTitle)
                    if (resume > 0L) p.seekTo(resume)
                }
                if (playbackState == Player.STATE_READY && p.playWhenReady && !p.isPlaying) {
                    p.play()
                }
                // v386 : la lecture demarre -> la methode d en-tetes utilisee est la bonne,
                // on la garde pour les prochains films de la session.
                if (playbackState == Player.STATE_READY) repliEntetesFait = false
                // v389 : la chaine est partie -> plus besoin de surveiller le demarrage.
                if (playbackState == Player.STATE_READY && p.videoSize.width > 0) {
                    demarrageOk = true
                    recoveryHandler.removeCallbacks(startupWatchdog)
                }
                if (playbackState == Player.STATE_READY && isLiveMode) {
                    // Direct reparti : on memorise la variante qui marche et on remet le compteur
                    // de reconnexions a zero.
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
                if (candIdx < candidates.size - 1) {
                    candIdx++
                    playCurrent()
                } else if (isLiveMode) {
                    // Coupure du direct (reseau coupe, token expire...) : on se rebranche
                    // automatiquement sans afficher d'erreur, comme un vrai boitier.
                    recoveryHandler.postDelayed({ reconnectLive() }, 1500)
                } else {
                    val c = error.cause
                    val detail = if (c is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)
                        "HTTP ${c.responseCode}" else error.errorCodeName
                    // v386 : film/serie Stalker refuse par le serveur -> on retente UNE fois
                    // avec l autre methode d en-tetes, automatiquement et sans rien demander.
                    if (!isLiveMode && Session.current?.type == "stalker" && !repliEntetesFait &&
                        c is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
                    ) {
                        repliEntetesFait = true
                        stalkerVodSansEntetes = !stalkerVodSansEntetes
                        try { recreate(); return } catch (e: Throwable) {}
                    }
                    // v388 : le serveur a refuse le flux (401 Unauthorized, 403, trop de
                    // connexions...). Avant d afficher quoi que ce soit, on retente une fois
                    // le lien (la connexion precedente n est parfois pas encore liberee cote
                    // serveur), puis on rejoue le meme lien avec une autre signature de
                    // lecteur. C est ce qui bloquait chez certains clients seulement.
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
                    val u = candidates.getOrNull(candIdx) ?: ""
                    // Fenetre lisible (au lieu d'un toast fugace) avec le diagnostic complet :
                    // l'utilisateur peut lire / photographier l'URL exacte et la reponse du serveur.
                    val diag = if (Session.current?.type == "stalker" && Api.lastStreamLog.isNotBlank())
                        "\n\n--- Diagnostic ---\n${Api.lastStreamLog}" else ""
                    androidx.appcompat.app.AlertDialog.Builder(this@PlayerActivity)
                        .setTitle("Lecture impossible : $detail")
                        .setMessage("$u$diag")
                        .setPositiveButton("OK") { d, _ -> d.dismiss() }
                        .show()
                }
            }
        })
        playCurrent()
        lastProgressTs = SystemClock.elapsedRealtime()
        if (isLiveMode) recoveryHandler.postDelayed(stallWatchdog, 2000)
        // v389 : surveillance du demarrage (rond de chargement sans fin).
        recoveryHandler.postDelayed(startupWatchdog, 9000)
        // v380 : surveillance de l image (direct ET films/series). Ne touche pas au flux.
        lastFramesTs = SystemClock.elapsedRealtime()
        recoveryHandler.postDelayed(frozenImageWatchdog, 4000)
    }

    private fun playCurrent() {
        val p = player ?: return
        val u = candidates.getOrNull(candIdx) ?: return
        // On laisse ExoPlayer auto-detecter le format (extension + Content-Type + redirections).
        // Forcer le MIME pouvait casser un .ts qui redirige en realite vers du HLS.
        p.setMediaItem(buildMediaItem(u))
        p.playWhenReady = true
        p.prepare()
        p.play()
    }

    // Lecture a la suite : a la fin d'un episode, on enchaine automatiquement sur le suivant
    // de la file (Session.episodeQueue) sans repasser par la fiche serie. Marche sur les 2 themes.
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
        if (!direct.isNullOrBlank()) { switchToVod(direct, epTitle, ep.logo); return }
        val cmd = ep.cmd
        val pl = Session.current
        if (cmd.isNullOrBlank() || pl == null) return
        lifecycleScope.launch {
            val link = try { Api.stalkerLink(pl, cmd, "movie") } catch (e: Exception) { null }
            if (!link.isNullOrBlank()) switchToVod(link, epTitle, ep.logo)
        }
    }

    // v394 : ZAPPING chaine en direct avec la telecommande (fleche haut/bas ou
    // touches CHANNEL_UP/DOWN). Ne concerne QUE le direct. On lit la position
    // courante et la liste memorisees par ZapList (categorie affichee au moment
    // du clic sur la chaine), on avance / recule, on resout l URL (Stalker via
    // Api.stalkerLink, sinon directUrl), puis on rebascule le player sans
    // recreer l activite (donc pas de flash noir).
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
        val pl = Session.current
        // Petit toast facultatif : titre de la chaine visee, dispo sur toutes les tv.
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
        // Reset des watchdogs de reconnexion pour la nouvelle chaine.
        candidates = buildCandidates(url)
        candIdx = 0
        workingCandIdx = -1
        liveRetries = 0
        demarrageOk = false
        demarrageEssais = 0
        recoveryHandler.removeCallbacks(startupWatchdog)
        recoveryHandler.removeCallbacks(stallWatchdog)
        recoveryHandler.removeCallbacks(frozenImageWatchdog)
        playCurrent()
        showTopBarTemporarily()
        recoveryHandler.postDelayed(startupWatchdog, 9000)
        lastFramesTs = SystemClock.elapsedRealtime()
        recoveryHandler.postDelayed(frozenImageWatchdog, 4000)
        recoveryHandler.postDelayed(stallWatchdog, 2000)
    }

    // Bascule le lecteur en cours sur un nouveau flux VOD (episode suivant) sans recreer l'activite.
    private fun switchToVod(url: String, title: String, logo: String) {
        val p = player ?: return
        watchUrl = url
        watchTitle = title
        if (logo.isNotBlank()) watchLogo = logo
        findViewById<TextView>(R.id.titleTv).text = title
        WatchHistory.touch(this, watchUrl, watchTitle, watchLogo, "series", watchSeriesName, watchSeriesLogo, watchSeriesId, watchSeriesCmd, watchSourceCmd, watchSourceStreamId, watchSourceContainerExt)
        // Nouvel episode : on ne reprend pas une position sauvegardee, et on reactive la recup audio.
        didRestorePosition = true
        audioRecoveryDone = false
        currentSubUrl = null
        candidates = buildVodCandidates(url)
        candIdx = 0
        playCurrent()
    }

    // ---- Sous-titres externes multilangues (OpenSubtitles) ----
    // Construit le MediaItem en y greffant, si demande, une piste de sous-titres externe.
    private fun buildMediaItem(u: String): MediaItem {
        val b = MediaItem.Builder().setUri(Uri.parse(u))
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

    // Menu de la roue dentee : Audio puis Sous-titres (comme demande).
    private fun showSettingsMenu() {
        val items = arrayOf("Audio", "Sous-titres")
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_KZ_Dialog)
            .setTitle("Param\u00e8tres")
            .setItems(items) { d, which ->
                d.dismiss()
                if (which == 0) showAudioMenu() else showSubtitleMenu()
            }
            .show()
    }

    // Choix de la piste audio presente dans le flux.
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
            .setItems(labels.toTypedArray()) { d, which -> actions[which](); d.dismiss() }
            .show()
    }

    // Menu Sous-titres : d'abord les pistes integrees au flux (comme TiViMate),
    // puis la recherche en ligne (OpenSubtitles par IP) pour tout le reste.
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
            .setItems(labels.toTypedArray()) { d, which -> actions[which](); d.dismiss() }
            .show()
    }

    // Selectionne une piste precise (audio ou texte) presente dans le flux.
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
                .setItems(labels) { d, which -> applyOnlineSubtitle(results[which]); d.dismiss() }
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

    // Rebranche le direct : on rejoue l'URL (pour un portail Stalker, re-hit du lien create_link
    // => nouvelle redirection 302 avec un token frais). N'affecte que le live (isLiveMode).
    private fun reconnectLive() {
        val p = player ?: return
        if (!isLiveMode) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastReconnectTs < 2500L) return
        lastReconnectTs = now
        liveRetries++
        // v388 : on n abandonne plus la chaine au bout de 12 essais (avant, l image restait
        // figee definitivement). Toutes les 3 tentatives on change de variante d URL.
        if (liveRetries > 200) return
        candIdx = if (liveRetries % 3 == 0 && candidates.size > 1)
            (workingCandIdx + 1) % candidates.size
        else workingCandIdx.coerceIn(0, (candidates.size - 1).coerceAtLeast(0))
        lastPos = -1L
        lastProgressTs = now
        playCurrent()
        // On repart au bord du direct : sinon on rejoue un buffer deja mort.
        try { p.seekToDefaultPosition() } catch (e: Exception) {}
    }

    // Variantes VOD/episodes : certains portails Stalker renvoient un container non decodeable
    // (.mkv/.avi) dans le chemin OU dans le parametre stream=1207225.mkv.
    // On tente donc les containers alternatifs sans toucher au token.
    private fun buildVodCandidates(url: String): List<String> {
        val list = LinkedHashSet<String>()
        list.add(url)

        fun replaceExt(from: String, to: String) {
            val re = Regex("(?i)\\.$from(?=(&|\\?|$))")
            val replaced = url.replace(re, ".$to")
            if (replaced != url) list.add(replaced)
        }

        // v388 : le panel indique souvent une extension qui n est pas celle du fichier
        // (ex : .ts alors que le film est en .mkv) et le serveur repond 401 ou 404.
        // On essaie donc toutes les extensions courantes, puis le lien sans extension.
        for (e in listOf("mkv", "mp4", "ts", "avi", "m4v")) {
            replaceExt("mkv", e); replaceExt("mp4", e); replaceExt("ts", e)
            replaceExt("avi", e); replaceExt("m4v", e)
        }
        for (x in listOf("mkv", "mp4", "ts", "avi", "m4v")) {
            val re = Regex("(?i)\\." + x + "(?=(&|\\?|$))")
            val sans = url.replace(re, "")
            if (sans != url) list.add(sans)
        }
        // v390 : lien M3U (et certains Xtream) SANS extension : les films ne partaient
        // pas du tout. On essaie les containers courants a la suite du lien.
        val qm = url.indexOf('?')
        val basePath = if (qm >= 0) url.substring(0, qm) else url
        val query = if (qm >= 0) url.substring(qm) else ""
        if (!basePath.substringAfterLast('/').contains('.')) {
            for (e in listOf("mkv", "mp4", "ts", "avi")) list.add(basePath + "." + e + query)
        }
        
        return list.toList()
    }

    // Construit les variantes d'URL a essayer pour un meme flux (live surtout).
    private fun buildCandidates(url: String): List<String> {
        val list = LinkedHashSet<String>()
        fun addVariants(u: String) {
            list.add(u)
            val q = u.indexOf('?')
            val path = if (q >= 0) u.substring(0, q) else u
            val query = if (q >= 0) u.substring(q) else ""
            when {
                path.endsWith(".ts") -> {
                    list.add(path.removeSuffix(".ts") + ".m3u8" + query)
                    list.add(path.removeSuffix(".ts") + query)
                }
                path.endsWith(".m3u8") -> {
                    list.add(path.removeSuffix(".m3u8") + ".ts" + query)
                    list.add(path.removeSuffix(".m3u8") + query)
                }
                else -> {
                    // URL sans extension (ex: .../user/pass/12345) -> on tente .ts puis .m3u8
                    val lastSeg = path.substringAfterLast('/')
                    if (!lastSeg.contains('.')) {
                        list.add(path + ".ts" + query)
                        list.add(path + ".m3u8" + query)
                    }
                }
            }
        }
        addVariants(url)
        // Format "legacy" Xtream pour le live : http://host/USER/PASS/ID(.ext) sans le segment /live/
        if (url.contains("/live/")) addVariants(url.replace("/live/", "/"))
        return list.toList()
    }

    // Pilotage a la telecommande (boitier / TV Android, sans ecran tactile)
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val p = player ?: return super.dispatchKeyEvent(event)
        val controllerVisible = playerView.isControllerFullyVisible
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (isLiveMode) {
                // En direct, n'importe quelle touche reaffiche brievement la barre du haut.
                showTopBarTemporarily()
                when (event.keyCode) {
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> return super.dispatchKeyEvent(event)
                    // v394 : ZAPPING chaine. Fleche haut/bas = chaine precedente/suivante
                    // dans la categorie affichee au moment du clic. Idem pour les touches
                    // dediees CHANNEL_UP/DOWN des telecommandes IPTV.
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
                    // 1er retour : si le menu est affiche, on le masque (sans quitter)
                    if (controllerVisible) { playerView.hideController(); return true }
                    // 2e retour : menu deja cache -> on quitte la chaine (comportement par defaut)
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
                    // Si le controleur n'est pas affiche : OK = lecture/pause + affiche les boutons
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

    // Affiche la barre du haut puis la masque apres 4 s (utilise en direct).
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

    override fun onStop() {
        saveWatchProgress()
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
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
        WatchHistory.save(this, watchUrl, watchTitle, watchLogo, watchKind, pos, dur, watchSeriesName, watchSeriesLogo, watchSeriesId, watchSeriesCmd, watchSourceCmd, watchSourceStreamId, watchSourceContainerExt)
    }
}
