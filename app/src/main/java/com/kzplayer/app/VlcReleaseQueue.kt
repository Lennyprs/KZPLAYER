package com.kzplayer.app
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.*
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
/** Keep the main looper free while the native stop waits for decoder/vout threads. */
internal object VlcReleaseQueue {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val pending=ArrayList<Job>() // Accessed only on the main looper.
    suspend fun awaitIdle(){while(pending.isNotEmpty())pending.toList().joinAll()}
    fun release(player:MediaPlayer?,media:Media?,library:LibVLC?,fd:ParcelFileDescriptor?,bridge:VlcStreamBridge?,done:()->Unit){
        lateinit var cleanup:Job
        cleanup=scope.launch(start=CoroutineStart.LAZY){
            try{
                withContext(Dispatchers.IO){
                    try{bridge?.close()}catch(_:Exception){}
                    try{player?.stop()}catch(_:Exception){}
                }
                // Surface detachment and Java player release remain on the main looper.
                try{player?.detachViews()}catch(_:Exception){}
                try{player?.release()}catch(_:Exception){}
                try{media?.release()}catch(_:Exception){}
                try{fd?.close()}catch(_:Exception){}
                try{library?.release()}catch(_:Exception){}
            }finally{
                pending.remove(cleanup)
                done()
            }
        }
        pending.add(cleanup)
        cleanup.start()
    }
}
