package com.kzplayer.app;
import android.content.Context;
import android.os.Build;
import android.util.Log;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.mediacodec.DefaultMediaCodecAdapterFactory;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;
import java.io.IOException;
/** Installed for VOD only. Audio and all unrelated decoders keep synchronous baseline behavior. */
public final class VodAsyncRenderersFactory extends DefaultRenderersFactory {
 private final MediaCodecAdapter.Factory synchronous;
 private final MediaCodecAdapter.Factory asynchronous;
 private final MediaCodecAdapter.Factory router;
 private volatile String lastVideoMode="standard-synchronous";
 public VodAsyncRenderersFactory(Context context){
  super(context);
  synchronous=new DefaultMediaCodecAdapterFactory(context).forceDisableAsynchronous();
  asynchronous=new DefaultMediaCodecAdapterFactory(context).forceEnableAsynchronous();
  router=new MediaCodecAdapter.Factory(){
   @Override public MediaCodecAdapter createAdapter(MediaCodecAdapter.Configuration c)throws IOException{
    boolean selected=VodPlaybackPolicy.asynchronousVideo(Build.VERSION.SDK_INT,c.codecInfo.name,c.codecInfo.codecMimeType,c.crypto!=null);
    if(c.codecInfo.codecMimeType!=null&&c.codecInfo.codecMimeType.startsWith("video/")){
     lastVideoMode=selected?"async-allwinner-hevc":"standard-synchronous";
     Log.i("KZVodCodec","video mode="+lastVideoMode);
    }
    return (selected?asynchronous:synchronous).createAdapter(c);
   }
  };
 }
 @Override protected MediaCodecAdapter.Factory getCodecAdapterFactory(){return router;}
 public String getLastVideoMode(){return lastVideoMode;}
}
