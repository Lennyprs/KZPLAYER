package com.kzplayer.app

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.launch
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.InputStream

/** Same activity_player layout and controls; alternate engine is an implementation detail. */
class VlcVodActivity : AppCompatActivity() {
    private var vlc:LibVLC?=null
    private var engine:MediaPlayer?=null
    private var activeMedia:Media?=null
    private var bridge:VlcStreamBridge?=null
    private var descriptor:ParcelFileDescriptor?=null
    private lateinit var video:VLCVideoLayout
    private lateinit var playerView:PlayerView
    private lateinit var controller:UnifiedVodController
    private var dialog:PlaybackErrorDialog?=null
    private val handler=Handler(Looper.getMainLooper())
    private val health=VodFrameHealth(6000L,6000L,4000L)
    private var mode=0
    private var busy=false
    private var generation=0
    private var shouldPlay=true
    private var foreground=false
    private var buffering=true
    private var ready=false
    private var failed=false
    private var ended=false
    private var heavy=true
    private var declaredHeavy=false
    private var speed=1f
    private var graceUntil=0L
    private var resumeMs=0L
    private var seekApplied=false
    private var everOpened=false
    private var sourceUrl=""
    private var lastSave=0L
    private var advancing=false
    @Volatile private var upstreamStatus=0
    private val mediaTitle get()=intent.getStringExtra("title").orEmpty()
    private val kind get()=intent.getStringExtra("historyKind").orEmpty()
    private val ownerId get()=intent.getStringExtra("vlcOwnerPlaylistId").orEmpty()
    private fun extra(key:String)=intent.getStringExtra(key).orEmpty()
    private fun ownerReady():Boolean {
        if(ownerId.isBlank())return true
        val owner=Session.playlists.firstOrNull {it.id==ownerId}?:return false
        Session.current=owner;return true
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        if((!intent.getBooleanExtra("vlcPrimary",false)&&!intent.getBooleanExtra("vlcFallback",false))||kind !in listOf("movie","series")||!ownerReady()){finish();return}
        sourceUrl=extra("url")
        resumeMs=savedInstanceState?.getLong("position")?:intent.getLongExtra("vlcResumeMs",0L)
        declaredHeavy=intent.getBooleanExtra("vlcHeavy",false)||VodPlaybackPlan.heavyTitle(mediaTitle)
        heavy=true // Unknown geometry is not permission to force software decoding of a possible 4K file.
        mode=(savedInstanceState?.getInt("mode")?:VodPlaybackPlan.first(declaredHeavy)).coerceIn(0,3)
        shouldPlay=savedInstanceState?.getBoolean("wantsPlay")?:true
        if(heavy&&mode==3)mode=2
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        setContentView(R.layout.activity_player)
        playerView=findViewById(R.id.playerView)
        val oldSurface=playerView.videoSurfaceView
        (oldSurface?.parent as? ViewGroup)?.removeView(oldSurface)
        playerView.setShutterBackgroundColor(Color.TRANSPARENT)
        playerView.setArtworkDisplayMode(PlayerView.ARTWORK_DISPLAY_MODE_OFF)
        val root=playerView.parent as FrameLayout
        video=VLCVideoLayout(this);root.addView(video,0,FrameLayout.LayoutParams(-1,-1))
        val top=findViewById<View>(R.id.topBar)
        findViewById<TextView>(R.id.titleTv).text=mediaTitle
        findViewById<ImageButton>(R.id.backBtn).setOnClickListener {finish()}
        controller=UnifiedVodController(object:UnifiedVodController.Backend {
            override fun position():Long=engine?.getTime()?.coerceAtLeast(0L)?:resumeMs
            override fun duration():Long=engine?.getLength()?.coerceAtLeast(0L)?:0L
            override fun wantsPlay():Boolean=shouldPlay
            override fun state():Int=when {failed->Player.STATE_IDLE;ended->Player.STATE_ENDED;!ready||buffering->Player.STATE_BUFFERING;else->Player.STATE_READY}
            override fun seekable():Boolean=engine?.isSeekable==true
            override fun playing(value:Boolean){setPlaying(value)}
            override fun seek(position:Long){seekTo(position)}
            override fun speed():Float=speed
            override fun speed(value:Float){speed=value.coerceIn(0.25f,2f);engine?.setRate(speed);health.reset()}
            override fun hasNext():Boolean=kind=="series"&&Session.episodeIndex+1<Session.episodeQueue.size
            override fun next(){saveProgress();nextEpisode()}
        },mediaTitle)
        playerView.player=controller
        playerView.controllerShowTimeoutMs=5000
        playerView.setControllerHideOnTouch(true)
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener {visibility->top.visibility=visibility})
        playerView.isFocusable=true;playerView.isFocusableInTouchMode=true;playerView.requestFocus()
        playerView.findViewById<View?>(androidx.media3.ui.R.id.exo_settings)?.setOnClickListener {settings()}
    }
    override fun onStart(){super.onStart();if(!isFinishing&&engine==null&&!busy)openAttempt(everOpened)}
    override fun onResume(){super.onResume();foreground=true;LicenseEnforcer.resume(this);if(shouldPlay&&ready)engine?.play();health.reset()}
    override fun onPause(){foreground=false;LicenseEnforcer.pause(this);saveProgress();engine?.pause();health.reset();super.onPause()}
    override fun onStop(){generation++;busy=false;saveProgress();dispose();dialog?.dismiss();dialog=null;super.onStop()}
    override fun onDestroy(){handler.removeCallbacksAndMessages(null);dispose();if(::controller.isInitialized){playerView.player=null;controller.release()};super.onDestroy()}
    override fun onSaveInstanceState(out:Bundle){out.putLong("position",engine?.getTime()?.takeIf {it>0}?:resumeMs);out.putInt("mode",mode);out.putBoolean("wantsPlay",shouldPlay);super.onSaveInstanceState(out)}
    private fun openAttempt(renew:Boolean){
        if(busy||isFinishing||isDestroyed||!ownerReady())return
        busy=true;failed=false;ready=false;buffering=true;ended=false;health.reset();controller.refresh()
        val token=++generation
        lifecycleScope.launch {
            val pl=Session.current
            val url=if(renew&&pl?.type=="stalker"&&extra("historySourceCmd").isNotBlank())try{Api.stalkerLink(pl,extra("historySourceCmd"),"movie")}catch(_:Exception){null}else sourceUrl
            if(token!=generation||isFinishing||isDestroyed)return@launch
            busy=false
            if(url.isNullOrBlank()){fail("Le serveur n'a pas renouvelé le lien du film.");return@launch}
            sourceUrl=url;intent.putExtra("url",url);everOpened=true;seekApplied=false;upstreamStatus=0
            try {
                System.loadLibrary("c++_shared");System.loadLibrary("vlc");System.loadLibrary("vlcjni")
                val options=arrayListOf("--quiet","--no-video-title-show","--stats")
                val library=LibVLC(this@VlcVodActivity,options);vlc=library
                val p=MediaPlayer(library);engine=p
                p.attachViews(video,null,true,VodPlaybackPlan.texture(mode))
                val uri=if(VlcStreamBridge.network(url)){val proxy=makeBridge(url);bridge=proxy;Uri.parse(proxy.localUrl())}else Uri.parse(url)
                val media=if(uri.scheme=="content"){
                    val fd=contentResolver.openFileDescriptor(uri,"r")?:throw java.io.IOException("Local file unavailable")
                    descriptor=fd;Media(library,fd.fileDescriptor)
                }else Media(library,uri)
                activeMedia=media
                // Cache is set before HW selection so LibVLC does not replace it with legacy defaults.
                media.addOption(":network-caching=1000");media.addOption(":file-caching=300")
                if(mode==0)media.setHWDecoderEnabled(true,false)
                for(option in VodPlaybackPlan.options(mode))media.addOption(option)
                if(kind=="series")media.addOption(":audio-language=fr,fra,fre")
                if(extra("vlcSubtitleUrl").isNotBlank())media.addOption(":sub-file="+extra("vlcSubtitleUrl"))
                p.setMedia(media)
                graceUntil=android.os.SystemClock.elapsedRealtime()+1500
                p.setEventListener {event->runOnUiThread {
                    if(engine!==p||token!=generation)return@runOnUiThread
                    when(event.type){
                        MediaPlayer.Event.Playing->{ready=true;buffering=false;if(!shouldPlay||!foreground)p.pause();p.setRate(speed)}
                        MediaPlayer.Event.Paused->{/* Preserve the user's requested state across lifecycle pauses. */}
                        MediaPlayer.Event.Buffering->{buffering=event.getBuffering()<100f;if(buffering)health.reset()}
                        MediaPlayer.Event.EncounteredError->{retryPath();return@runOnUiThread}
                        MediaPlayer.Event.EndReached->{ended=true;saveProgress(true);nextEpisode()}
                    }
                    controller.refresh()
                }}
                p.play();handler.removeCallbacks(tick);handler.post(tick)
            }catch(_:LinkageError){dispose();fail("Les bibliothèques vidéo n'ont pas pu être chargées sur cette installation.")}
            catch(_:Exception){dispose();fail("Le film n'a pas pu être ouvert.")}
        }
    }
    private fun makeBridge(root:String):VlcStreamBridge {
        val pl=Session.current
        val ua=Api.m3uUserAgents[root]?.takeIf {it.isNotBlank()}?:if(pl?.type=="stalker")Api.stalkerHeaders(pl)["User-Agent"]?:"VLC/3.0.20" else "VLC/3.0.20"
        val headers=if(pl?.type=="stalker"&&VlcStreamBridge.sameOrigin(pl.serverUrl,root))try{Api.stalkerHeaders(pl).filterKeys {!it.equals("User-Agent",true)}}catch(_:Exception){emptyMap()}else emptyMap()
        return VlcStreamBridge(root,VlcStreamBridge.Opener {url,offset,length->
            val factory=KzHttpDataSource.factory(this,ua,true,if(VlcStreamBridge.sameOrigin(root,url))headers else emptyMap())
            val source=factory.createDataSource()
            try {
                val builder=DataSpec.Builder().setUri(url).setPosition(offset)
                if(length>0)builder.setLength(length)
                val size=source.open(builder.build())
                fun header(name:String)=source.responseHeaders.entries.firstOrNull {it.key.equals(name,true)}?.value?.firstOrNull().orEmpty()
                val input=object:InputStream(){
                    private val single=ByteArray(1)
                    override fun read():Int=if(read(single,0,1)<0)-1 else single[0].toInt() and 255
                    override fun read(b:ByteArray,o:Int,l:Int):Int=if(l==0)0 else source.read(b,o,l)
                    override fun close(){source.close()}
                }
                VlcStreamBridge.Source(input,size,header("Content-Type"),header("Content-Range"),source.uri?.toString()?:url)
            }catch(e:Exception){if(e is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)upstreamStatus=e.responseCode;try{source.close()}catch(_:Exception){};throw java.io.IOException("Stream unavailable")}
        })
    }
    private val tick=object:Runnable {
        override fun run(){
            val p=engine?:return
            val now=android.os.SystemClock.elapsedRealtime();val length=p.getLength();val time=p.getTime().coerceAtLeast(0L)
            if(!seekApplied&&length>0&&p.isSeekable){seekApplied=true;if(resumeMs>0)seekTo(resumeMs.coerceAtMost(length-1))}
            if(now-lastSave>5000){saveProgress();lastSave=now}
            val stats=activeMedia?.getStats()
            val track=p.getCurrentVideoTrack()
            if(track!=null&&track.width>0&&track.height>0)heavy=declaredHeavy||VodVideoPolicy.heavy(null,track.width,track.height)
            val fps=if(track!=null&&track.frameRateDen>0)track.frameRateNum.toFloat()/track.frameRateDen else 0f
            val nearEnd=length>0&&length-time<2000
            val action=health.sample(now,foreground&&shouldPlay&&!ended&&!nearEnd,buffering,now<graceUntil,
                stats?.displayedPictures?.toLong()?:-1L,stats?.lostPictures?.toLong()?:0L,time,fps)
            if(action!=VodFrameHealth.OK){retryPath();return}
            controller.refresh();handler.postDelayed(this,500)
        }
    }
    private fun retryPath(){
        if(busy||isFinishing||isDestroyed||failed)return
        resumeMs=engine?.getTime()?.takeIf {it>0}?:resumeMs
        saveProgress();dispose()
        if(upstreamStatus>=400){fail("Le serveur refuse le flux (HTTP $upstreamStatus).");return}
        val next=VodPlaybackPlan.next(mode,heavy)
        if(next<0){fail("Les chemins de lecture disponibles n'ont pas fourni une image stable pour ce fichier. Aucune nouvelle reprise automatique.");return}
        mode=next;openAttempt(true)
    }
    private fun setPlaying(value:Boolean){
        shouldPlay=value;health.reset();graceUntil=android.os.SystemClock.elapsedRealtime()+1500
        val p=engine
        if(p!=null){if(value&&foreground)p.play()else p.pause()}
        else if(value&&!busy&&!failed)openAttempt(everOpened)
    }
    private fun seekTo(position:Long){
        val p=engine?:return;val length=p.getLength();if(length<=0||!p.isSeekable)return
        p.setTime(position.coerceIn(0L,length-1));health.reset();graceUntil=android.os.SystemClock.elapsedRealtime()+2000
    }
    private fun settings(){
        val d=PlaybackErrorDialog(this);d.setTitle("Paramètres de lecture")
        val box=LinearLayout(this);box.orientation=LinearLayout.VERTICAL
        fun item(label:String,action:()->Unit){val b=Button(this);b.text=label;b.setOnClickListener {d.dismiss();action()};box.addView(b)}
        item("Audio"){tracks(false)};item("Sous-titres"){tracks(true)}
        item("Vitesse"){speeds()};item("Avis légaux"){licence()}
        d.setView(box);d.setButton(android.content.DialogInterface.BUTTON_NEGATIVE,"Fermer"){_,_->};dialog?.dismiss();dialog=d;d.show()
    }
    private fun speeds(){
        val d=PlaybackErrorDialog(this);d.setTitle("Vitesse de lecture");val box=LinearLayout(this);box.orientation=LinearLayout.VERTICAL
        for(value in listOf(0.5f,0.75f,1f,1.25f,1.5f,2f)){val b=Button(this);b.text="${value}×";b.setOnClickListener {speed=value;engine?.setRate(value);health.reset();controller.refresh();d.dismiss()};box.addView(b)}
        d.setView(box);d.setButton(android.content.DialogInterface.BUTTON_NEGATIVE,"Fermer"){_,_->};dialog?.dismiss();dialog=d;d.show()
    }
    private fun tracks(subtitles:Boolean){
        val p=engine?:return
        val available=if(subtitles)p.getSpuTracks().orEmpty()else p.getAudioTracks().orEmpty()
        val box=LinearLayout(this);box.orientation=LinearLayout.VERTICAL
        val d=PlaybackErrorDialog(this);d.setTitle(if(subtitles) "Sous-titres intégrés" else "Audio")
        if(available.isEmpty()){val tv=TextView(this);tv.text="Aucune piste disponible";box.addView(tv)}
        for(track in available){val b=Button(this);b.text=track.name?:track.id.toString();b.setOnClickListener {
            if(engine===p&&!isFinishing&&!isDestroyed)try{if(subtitles)p.setSpuTrack(track.id)else p.setAudioTrack(track.id)}catch(_:IllegalStateException){}
            d.dismiss()
        };box.addView(b)}
        if(subtitles){
            val search=Button(this);search.text="Chercher en ligne…";search.setOnClickListener {d.dismiss();searchSubtitlesOnline()};box.addView(search)
        }
        val scroll=ScrollView(this);scroll.addView(box);d.setView(scroll);d.setButton(android.content.DialogInterface.BUTTON_NEGATIVE,"Fermer"){_,_->};dialog?.dismiss();dialog=d;d.show()
    }
    private fun searchSubtitlesOnline(){
        val query=extra("seriesName").ifBlank {mediaTitle};if(query.isBlank())return
        val token=generation
        Toast.makeText(this,"Recherche de sous-titres…",Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val results=SubtitlesApi.search(query,SubtitlesConfig.SUBLANGUAGE_IDS)
            if(token!=generation||isFinishing||isDestroyed)return@launch
            if(results.isEmpty()){Toast.makeText(this@VlcVodActivity,"Aucun sous-titre trouvé.",Toast.LENGTH_LONG).show();return@launch}
            val d=PlaybackErrorDialog(this@VlcVodActivity);d.setTitle("Choisir un sous-titre")
            val box=LinearLayout(this@VlcVodActivity);box.orientation=LinearLayout.VERTICAL
            for(opt in results){val b=Button(this@VlcVodActivity);b.text="${opt.lang} — ${opt.release.take(45)}";b.setOnClickListener {
                d.dismiss()
                lifecycleScope.launch {
                    val local=SubtitlesApi.downloadToFile(this@VlcVodActivity,opt)
                    if(token!=generation||isFinishing||isDestroyed)return@launch
                    val p=engine
                    val applied=if(local.isNullOrBlank()||p==null)false else try{p.addSlave(org.videolan.libvlc.interfaces.IMedia.Slave.Type.Subtitle,Uri.parse(local),true)}catch(_:Exception){false}
                    if(applied)intent.putExtra("vlcSubtitleUrl",local)
                    Toast.makeText(this@VlcVodActivity,if(applied)"Sous-titres activés" else "Sous-titres indisponibles",Toast.LENGTH_SHORT).show()
                }
            };box.addView(b)}
            val scroll=ScrollView(this@VlcVodActivity);scroll.addView(box);d.setView(scroll)
            d.setButton(android.content.DialogInterface.BUTTON_NEGATIVE,"Fermer"){_,_->};dialog?.dismiss();dialog=d;d.show()
        }
    }
    private fun licence(){
        val text=assets.open("libvlc-NOTICE.txt").bufferedReader().use {it.readText()}+"\n\n"+assets.open("libvlc-LGPL-2.1.txt").bufferedReader().use {it.readText()}
        val d=PlaybackErrorDialog(this);d.setTitle("Avis légaux");d.setMessage(text);d.setButton(android.content.DialogInterface.BUTTON_POSITIVE,"Fermer"){_,_->};dialog?.dismiss();dialog=d;d.show()
    }
    private fun saveProgress(completed:Boolean=false){
        val p=engine?:return;val length=p.getLength();val position=if(completed&&length>0)length else p.getTime()
        if(position>0||seekApplied)resumeMs=position.coerceAtLeast(0L)
        if(position<3000||length<=0||!ownerReady())return
        WatchHistory.save(this,sourceUrl,mediaTitle,extra("logo"),kind,position,length,extra("seriesName"),extra("seriesLogo"),extra("seriesId"),extra("seriesCmd"),extra("historySourceCmd"),extra("historySourceStreamId"),extra("historySourceContainerExt"))
    }
    private fun nextEpisode(){
        if(kind!="series"||advancing)return
        val index=Session.episodeIndex+1;val next=Session.episodeQueue.getOrNull(index)?:return
        advancing=true
        lifecycleScope.launch {
            val pl=Session.current
            val url=next.directUrl?.takeIf {it.isNotBlank()}?:if(pl!=null&&!next.cmd.isNullOrBlank())try{Api.stalkerLink(pl,next.cmd!!,"movie")}catch(_:Exception){null}else null
            if(url.isNullOrBlank()||isFinishing||isDestroyed){advancing=false;return@launch}
            Session.episodeIndex=index
            val target=Intent(this@VlcVodActivity,PlayerActivity::class.java);intent.extras?.let {target.putExtras(it)}
            target.extras?.keySet()?.toList()?.filter {it.startsWith("vlc")||it.startsWith("vodRecovery")||it=="vodRejectedDecoders"||it=="vodCompatibleBase"||it=="vodUaIndex"}?.forEach {target.removeExtra(it)}
            target.putExtra("url",url).putExtra("title",Session.seriesItem?.let {"${it.name} - ${next.name}"}?:next.name).putExtra("logo",next.logo)
                .putExtra("historySourceCmd",next.cmd.orEmpty()).putExtra("historySourceStreamId",next.streamId.orEmpty()).putExtra("historySourceContainerExt",next.containerExt.orEmpty()).putExtra("queued",true)
            startActivity(target);finish()
        }
    }
    private fun fail(message:String){
        if(isFinishing||isDestroyed)return
        failed=true;ready=false;buffering=false;dispose();controller.refresh();playerView.showController()
        val d=PlaybackErrorDialog(this);d.setTitle("Lecture impossible");d.setMessage(message)
        d.setButton(android.content.DialogInterface.BUTTON_POSITIVE,"Retour"){_,_->finish()};d.setOnCancelListener {finish()};dialog?.dismiss();dialog=d;d.show()
    }
    private fun dispose(){
        handler.removeCallbacks(tick);health.reset()
        val p=engine;engine=null
        try{bridge?.close()}catch(_:Exception){};bridge=null
        if(p!=null){p.setEventListener(null);try{p.stop()}catch(_:Exception){};try{p.detachViews()}catch(_:Exception){};try{p.release()}catch(_:Exception){}}
        try{activeMedia?.release()}catch(_:Exception){};activeMedia=null
        try{descriptor?.close()}catch(_:Exception){};descriptor=null
        try{vlc?.release()}catch(_:Exception){};vlc=null
    }
    override fun onKeyDown(code:Int,event:KeyEvent):Boolean {
        if(!::playerView.isInitialized)return super.onKeyDown(code,event)
        when(code){
            KeyEvent.KEYCODE_BACK->{if(playerView.isControllerFullyVisible)playerView.hideController()else finish();return true}
            KeyEvent.KEYCODE_DPAD_CENTER->{if(!playerView.isControllerFullyVisible){setPlaying(!shouldPlay);controller.refresh();playerView.showController();return true}}
            KeyEvent.KEYCODE_DPAD_LEFT->{if(!playerView.isControllerFullyVisible){seekTo((engine?.getTime()?:0L)-10000);playerView.showController();return true}}
            KeyEvent.KEYCODE_DPAD_RIGHT->{if(!playerView.isControllerFullyVisible){seekTo((engine?.getTime()?:0L)+30000);playerView.showController();return true}}
        }
        return super.onKeyDown(code,event)
    }
}
