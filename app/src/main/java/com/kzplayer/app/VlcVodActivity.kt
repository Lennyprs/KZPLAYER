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
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.launch
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.InputStream

/** Internal fallback only. No exported entry point and no permanent change to the normal player. */
class VlcVodActivity : AppCompatActivity() {
    private var vlc:LibVLC?=null
    private var engine:MediaPlayer?=null
    private var bridge:VlcStreamBridge?=null
    private var descriptor:ParcelFileDescriptor?=null
    private lateinit var video:VLCVideoLayout
    private lateinit var controls:LinearLayout
    private lateinit var status:TextView
    private lateinit var seek:SeekBar
    private lateinit var pauseButton:Button
    private var dialog:PlaybackErrorDialog?=null
    private val handler=Handler(Looper.getMainLooper())
    private var software=false
    private var busy=false
    private var generation=0
    private var shouldPlay=true
    private var userSeeking=false
    private var ended=false
    private var videoOutput=false
    @Volatile private var upstreamStatus=0
    private var everOpened=false
    private var sourceUrl=""
    private var resumeMs=0L
    private var seekApplied=false
    private var startedAt=0L
    private var lastSave=0L
    private val mediaTitle get()=intent.getStringExtra("title").orEmpty()
    private val kind get()=intent.getStringExtra("historyKind").orEmpty()
    private val ownerId get()=intent.getStringExtra("vlcOwnerPlaylistId").orEmpty()
    private fun extra(key:String)=intent.getStringExtra(key).orEmpty()
    private fun ownerReady():Boolean {
        if(ownerId.isBlank())return true
        val owner=Session.playlists.firstOrNull {it.id==ownerId}?:return false
        Session.current=owner
        return true
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        if(!intent.getBooleanExtra("vlcFallback",false)||kind !in listOf("movie","series")||!ownerReady()){finish();return}
        sourceUrl=extra("url")
        resumeMs=savedInstanceState?.getLong("position")?:intent.getLongExtra("vlcResumeMs",0L)
        software=savedInstanceState?.getBoolean("software")?:false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        val root=FrameLayout(this);root.setBackgroundColor(Color.BLACK)
        video=VLCVideoLayout(this);root.addView(video,FrameLayout.LayoutParams(-1,-1))
        controls=LinearLayout(this);controls.orientation=LinearLayout.VERTICAL;controls.setPadding(20,12,20,12);controls.setBackgroundColor(0xc0000000.toInt())
        val label=TextView(this);label.text=mediaTitle+" — secours LibVLC";label.setTextColor(Color.WHITE);label.textSize=19f;controls.addView(label)
        status=TextView(this);status.setTextColor(Color.WHITE);status.text="Ouverture du moteur de secours…";controls.addView(status)
        seek=SeekBar(this);seek.max=1000;controls.addView(seek,LinearLayout.LayoutParams(-1,-2))
        seek.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(s:SeekBar){userSeeking=true}
            override fun onStopTrackingTouch(s:SeekBar){val p=engine;if(p!=null&&p.getLength()>0)p.setTime(p.getLength()*s.progress/1000);userSeeking=false}
            override fun onProgressChanged(s:SeekBar,p:Int,user:Boolean){}
        })
        val buttons=LinearLayout(this);buttons.orientation=LinearLayout.HORIZONTAL
        fun button(text:String,action:()->Unit):Button {val b=Button(this);b.text=text;b.setOnClickListener {action()};buttons.addView(b,LinearLayout.LayoutParams(0,-2,1f));return b}
        button("Retour"){finish()};pauseButton=button("Pause"){toggle()}
        button("−10 s"){jump(-10000)};button("+10 s"){jump(10000)}
        button("Audio"){tracks(false)};button("Sous-titres"){tracks(true)}
        button("Logiciel"){if(!software)retrySoftware()else Toast.makeText(this,"Le mode logiciel est déjà actif",Toast.LENGTH_SHORT).show()}
        button("Licence"){licence()}
        controls.addView(buttons)
        val lp=FrameLayout.LayoutParams(-1,-2,android.view.Gravity.BOTTOM);root.addView(controls,lp)
        root.setOnClickListener {controls.visibility=if(controls.visibility==View.VISIBLE)View.GONE else View.VISIBLE}
        setContentView(root);pauseButton.requestFocus()
    }
    override fun onStart(){super.onStart();if(!isFinishing&&engine==null&&!busy)openAttempt(everOpened)}
    override fun onResume(){super.onResume();LicenseEnforcer.resume(this)}
    override fun onPause(){LicenseEnforcer.pause(this);saveProgress();engine?.pause();super.onPause()}
    override fun onStop(){generation++;busy=false;saveProgress();dispose();dialog?.dismiss();dialog=null;super.onStop()}
    override fun onDestroy(){handler.removeCallbacksAndMessages(null);dispose();super.onDestroy()}
    override fun onSaveInstanceState(out:Bundle){out.putLong("position",engine?.getTime()?.coerceAtLeast(0L)?:resumeMs);out.putBoolean("software",software);super.onSaveInstanceState(out)}
    private fun openAttempt(renew:Boolean){
        if(busy||isFinishing||isDestroyed||!ownerReady())return
        busy=true;val token=++generation
        lifecycleScope.launch {
            val pl=Session.current
            val url=if(renew&&pl?.type=="stalker"&&extra("historySourceCmd").isNotBlank())try{Api.stalkerLink(pl,extra("historySourceCmd"),"movie")}catch(_:Exception){null}else sourceUrl
            if(token!=generation||isFinishing||isDestroyed)return@launch
            busy=false
            if(url.isNullOrBlank()){fail("Le portail n'a pas renouvelé le lien du film.");return@launch}
            sourceUrl=url;intent.putExtra("url",url);everOpened=true;seekApplied=false;ended=false;videoOutput=false;upstreamStatus=0
            try {
                // Preflight avoids a process exit inside LibVLC's loader on unsupported ABI/installations.
                System.loadLibrary("c++_shared");System.loadLibrary("vlc");System.loadLibrary("vlcjni")
                val library=LibVLC(this@VlcVodActivity,arrayListOf("--quiet","--no-video-title-show","--android-display-chroma=RV32"));vlc=library
                val p=MediaPlayer(library);engine=p
                p.attachViews(video,null,true,false)
                val uri=if(VlcStreamBridge.network(url)){
                    val proxy=makeBridge(url);bridge=proxy;Uri.parse(proxy.localUrl())
                }else Uri.parse(url)
                val media=if(uri.scheme=="content"){
                    val fd=contentResolver.openFileDescriptor(uri,"r")?:throw java.io.IOException("Local file unavailable")
                    descriptor=fd;Media(library,fd.fileDescriptor)
                }else Media(library,uri)
                try {
                    media.setHWDecoderEnabled(!software,!software)
                    media.addOption(":network-caching=1500")
                    media.addOption(":file-caching=1500")
                    if(kind=="series")media.addOption(":audio-language=fr,fra,fre")
                    if(extra("vlcSubtitleUrl").isNotBlank())media.addOption(":sub-file="+extra("vlcSubtitleUrl"))
                    // No resize, transcoding, fabricated URL extension, or Dolby profile relabeling.
                    p.setMedia(media)
                }finally{media.release()}
                startedAt=android.os.SystemClock.elapsedRealtime()
                p.setEventListener {event->runOnUiThread {
                    if(engine!==p||token!=generation)return@runOnUiThread
                    when(event.type){
                        MediaPlayer.Event.Playing->{
                            status.text=if(software)"Secours LibVLC — décodage logiciel" else "Secours LibVLC — décodage automatique"
                            if(!shouldPlay)p.pause()else handler.postDelayed(hideControls,5000)
                        }
                        MediaPlayer.Event.Vout->{videoOutput=event.getVoutCount()>0}
                        MediaPlayer.Event.EncounteredError->retrySoftware()
                        MediaPlayer.Event.EndReached->{ended=true;saveProgress(true);nextEpisode()}
                    }
                }}
                p.play();handler.removeCallbacks(tick);handler.post(tick)
            }catch(_:LinkageError){dispose();fail("Le moteur LibVLC n'a pas pu charger ses bibliothèques sur cette installation.")}
            catch(_:Exception){dispose();fail("Le moteur de secours n'a pas pu ouvrir ce média.")}
        }
    }
    private fun makeBridge(root:String):VlcStreamBridge {
        val pl=Session.current
        val ua=Api.m3uUserAgents[root]?.takeIf {it.isNotBlank()}?:if(pl?.type=="stalker")Api.stalkerHeaders(pl)["User-Agent"]?:"VLC/3.0.20" else "VLC/3.0.20"
        val headers=if(pl?.type=="stalker")try{Api.stalkerHeaders(pl).filterKeys {!it.equals("User-Agent",true)}}catch(_:Exception){emptyMap()}else emptyMap()
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
            val time=p.getTime().coerceAtLeast(0L);val length=p.getLength()
            if(!seekApplied&&length>0){seekApplied=true;if(resumeMs>0)p.setTime(resumeMs.coerceAtMost(length-1))}
            if(!userSeeking&&length>0)seek.progress=(time*1000/length).toInt().coerceIn(0,1000)
            pauseButton.text=if(p.isPlaying)"Pause" else "Lecture"
            if(android.os.SystemClock.elapsedRealtime()-lastSave>5000){saveProgress();lastSave=android.os.SystemClock.elapsedRealtime()}
            if(!ended&&shouldPlay&&!videoOutput&&android.os.SystemClock.elapsedRealtime()-startedAt>30000){retrySoftware();return}
            handler.postDelayed(this,500)
        }
    }
    private val hideControls=Runnable {if(engine?.isPlaying==true&&!userSeeking)controls.visibility=View.GONE}
    private fun retrySoftware(){
        if(busy||isFinishing||isDestroyed)return
        resumeMs=engine?.getTime()?.takeIf {it>0}?:resumeMs
        saveProgress();dispose()
        if(upstreamStatus>=400){fail("Le serveur refuse le flux (HTTP $upstreamStatus). Aucun nouvel essai de codec pour contourner une erreur réseau.");return}
        if(software){fail("Le moteur de secours a également échoué. Aucune lecture ni fluidité n'est confirmée sur ce fichier.");return}
        software=true;status.text="Nouvel essai LibVLC en logiciel…";openAttempt(true)
    }
    private fun toggle(){val p=engine?:return;shouldPlay=!p.isPlaying;if(shouldPlay){if(!videoOutput)startedAt=android.os.SystemClock.elapsedRealtime();p.play();handler.postDelayed(hideControls,5000)}else {p.pause();controls.visibility=View.VISIBLE}}
    private fun jump(delta:Long){val p=engine?:return;val end=p.getLength();if(end>0)p.setTime((p.getTime()+delta).coerceIn(0L,end-1))}
    private fun tracks(subtitles:Boolean){
        val p=engine?:return
        val available=if(subtitles)p.getSpuTracks().orEmpty()else p.getAudioTracks().orEmpty()
        val box=LinearLayout(this);box.orientation=LinearLayout.VERTICAL
        val d=PlaybackErrorDialog(this);d.setTitle(if(subtitles)"Sous-titres intégrés" else "Audio")
        if(available.isEmpty()){val tv=TextView(this);tv.text="Aucune piste disponible";box.addView(tv)}
        for(track in available){val b=Button(this);b.text=track.name?:track.id.toString();b.setOnClickListener {if(engine===p&&!isFinishing&&!isDestroyed)try{if(subtitles)p.setSpuTrack(track.id)else p.setAudioTrack(track.id)}catch(_:IllegalStateException){};d.dismiss()};box.addView(b)}
        val scroll=ScrollView(this);scroll.addView(box);d.setView(scroll);d.setButton(android.content.DialogInterface.BUTTON_NEGATIVE,"Fermer"){_,_->};dialog?.dismiss();dialog=d;d.show()
    }
    private fun licence(){
        val text=assets.open("libvlc-NOTICE.txt").bufferedReader().use {it.readText()}+"\n\n"+assets.open("libvlc-LGPL-2.1.txt").bufferedReader().use {it.readText()}
        val d=PlaybackErrorDialog(this);d.setTitle("LibVLC — licence et sources");d.setMessage(text);d.setButton(android.content.DialogInterface.BUTTON_POSITIVE,"Fermer"){_,_->};dialog?.dismiss();dialog=d;d.show()
    }
    private fun saveProgress(completed:Boolean=false){
        val p=engine?:return;val length=p.getLength();val position=if(completed&&length>0)length else p.getTime()
        if(position>0||seekApplied)resumeMs=position.coerceAtLeast(0L)
        if(position<3000||length<=0||!ownerReady())return
        WatchHistory.save(this,sourceUrl,mediaTitle,extra("logo"),kind,position,length,extra("seriesName"),extra("seriesLogo"),extra("seriesId"),extra("seriesCmd"),extra("historySourceCmd"),extra("historySourceStreamId"),extra("historySourceContainerExt"))
    }
    private fun nextEpisode(){
        if(kind!="series")return
        val index=Session.episodeIndex+1;val next=Session.episodeQueue.getOrNull(index)?:return
        lifecycleScope.launch {
            val pl=Session.current
            val url=next.directUrl?.takeIf {it.isNotBlank()}?:if(pl!=null&&!next.cmd.isNullOrBlank())try{Api.stalkerLink(pl,next.cmd!!,"movie")}catch(_:Exception){null}else null
            if(url.isNullOrBlank()||isFinishing||isDestroyed)return@launch
            Session.episodeIndex=index
            val target=Intent(this@VlcVodActivity,PlayerActivity::class.java);intent.extras?.let {target.putExtras(it)}
            target.extras?.keySet()?.toList()?.filter {it.startsWith("vlc")||it.startsWith("vodRecovery")||it=="vodRejectedDecoders"||it=="vodCompatibleBase"}?.forEach {target.removeExtra(it)}
            target.putExtra("url",url).putExtra("title",Session.seriesItem?.let {"${it.name} - ${next.name}"}?:next.name).putExtra("logo",next.logo)
                .putExtra("historySourceCmd",next.cmd.orEmpty()).putExtra("historySourceStreamId",next.streamId.orEmpty()).putExtra("historySourceContainerExt",next.containerExt.orEmpty()).putExtra("queued",true)
            startActivity(target);finish()
        }
    }
    private fun fail(message:String){
        if(isFinishing||isDestroyed)return
        status.text=message;controls.visibility=View.VISIBLE
        val d=PlaybackErrorDialog(this);d.setTitle("Lecture de secours impossible");d.setMessage(message+"\nAppareil : "+android.os.Build.MANUFACTURER+" "+android.os.Build.MODEL)
        d.setButton(android.content.DialogInterface.BUTTON_POSITIVE,"Retour"){_,_->finish()};d.setOnCancelListener {finish()};dialog?.dismiss();dialog=d;d.show()
    }
    private fun dispose(){
        handler.removeCallbacks(tick);handler.removeCallbacks(hideControls)
        val p=engine;engine=null
        try{bridge?.close()}catch(_:Exception){};bridge=null
        if(p!=null){p.setEventListener(null);try{p.stop()}catch(_:Exception){};try{p.detachViews()}catch(_:Exception){};try{p.release()}catch(_:Exception){}}
        try{descriptor?.close()}catch(_:Exception){};descriptor=null
        try{vlc?.release()}catch(_:Exception){};vlc=null
    }
    override fun onKeyDown(code:Int,event:KeyEvent):Boolean {
        when(code){
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE->{toggle();return true}
            KeyEvent.KEYCODE_MEDIA_PLAY->{shouldPlay=true;engine?.play();return true}
            KeyEvent.KEYCODE_MEDIA_PAUSE->{shouldPlay=false;engine?.pause();return true}
            KeyEvent.KEYCODE_MEDIA_REWIND->{jump(-10000);return true}
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD->{jump(10000);return true}
            KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN->{if(controls.visibility!=View.VISIBLE){controls.visibility=View.VISIBLE;pauseButton.requestFocus();return true}}
            KeyEvent.KEYCODE_DPAD_CENTER->{if(controls.visibility!=View.VISIBLE){controls.visibility=View.VISIBLE;pauseButton.requestFocus();return true}}
            KeyEvent.KEYCODE_BACK->{finish();return true}
        }
        return super.onKeyDown(code,event)
    }
}
