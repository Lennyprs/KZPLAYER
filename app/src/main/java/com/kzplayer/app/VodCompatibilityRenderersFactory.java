package com.kzplayer.app;
import android.content.Context;
import android.media.MediaFormat;
import android.os.Handler;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import java.util.ArrayList;
/** VOD only. Retains V138 PCM audio; changes only DV-compatible HEVC configuration. */
public final class VodCompatibilityRenderersFactory extends VodAudioRenderersFactory {
 public interface Diagnostics {void onConfiguration(String label);}
 private final Diagnostics diagnostics;
 public VodCompatibilityRenderersFactory(Context context,Diagnostics diagnostics){super(context);this.diagnostics=diagnostics;}
 @Override protected void buildVideoRenderers(Context context,int mode,MediaCodecSelector selector,
   boolean fallback,Handler handler,VideoRendererEventListener listener,long joiningMs,ArrayList<Renderer> out){
  int first=out.size();
  // Retain extension renderer instances and their ordering. No decoder ranking/probes added.
  super.buildVideoRenderers(context,mode,selector,fallback,handler,listener,joiningMs,out);
  for(int i=first;i<out.size();i++)if(out.get(i).getClass()==MediaCodecVideoRenderer.class){
   out.set(i,new MediaCodecVideoRenderer(context,getCodecAdapterFactory(),selector,joiningMs,fallback,
     handler,listener,MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY){
    @Override protected MediaFormat getMediaFormat(Format source,String decoderMime,CodecMaxValues max,
      float operatingRate,boolean noPostProcess,int tunnelingAudioSessionId){
     String alternative=MediaCodecUtil.getAlternativeCodecMimeType(source);
     boolean compatible=DolbyBaseConfiguration.useHevcBase(source.sampleMimeType,decoderMime,alternative,
       source.drmInitData!=null,tunnelingAudioSessionId!=C.AUDIO_SESSION_ID_UNSET);
     // Only the codec's CONFIGURATION copy changes. Keep original track metadata in Media3,
     // exact dimensions, frame rate, CSD/SPS/PPS, ColorInfo and media bytes unchanged.
     // Let the HEVC decoder obtain its profile from the bitstream; never invent Main/Main10.
     Format configured=compatible?source.buildUpon().setSampleMimeType(decoderMime).setCodecs(null).build():source;
     MediaFormat result=super.getMediaFormat(configured,decoderMime,max,operatingRate,noPostProcess,tunnelingAudioSessionId);
     if(diagnostics!=null)diagnostics.onConfiguration(compatible?
       "Base HEVC reconnue par Media3 ; profil Dolby non impose au decodeur HEVC":"Configuration Media3 standard");
     return result;
    }
   });
   break;
  }
 }
}
