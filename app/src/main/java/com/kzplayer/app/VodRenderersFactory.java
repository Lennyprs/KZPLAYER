package com.kzplayer.app;
import android.content.Context;
import android.media.MediaFormat;
import android.os.Handler;
import androidx.media3.common.Format;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Comparator;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.mediacodec.DefaultMediaCodecAdapterFactory;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
/** VOD only. Fix the DV-profile/HEVC-base configuration mismatch without changing native DV. */
public final class VodRenderersFactory extends DefaultRenderersFactory {
 private final MediaCodecAdapter.Factory adapters;
 public interface Diagnostics {void onCandidates(String description);}
 private final Diagnostics diagnostics;
 public VodRenderersFactory(Context context){this(context,null);}
 public VodRenderersFactory(Context context,Diagnostics diagnostics){super(context);this.diagnostics=diagnostics;
  final DefaultMediaCodecAdapterFactory defaults=new DefaultMediaCodecAdapterFactory(context).forceDisableAsynchronous();
  adapters=configuration->{
   int profile=VodVideoPolicy.baseProfile(configuration.format.sampleMimeType,configuration.codecInfo.codecMimeType,MediaCodecUtil.getAlternativeCodecMimeType(configuration.format));
   if(profile>=0)configuration.mediaFormat.setInteger(MediaFormat.KEY_PROFILE,profile);
   return defaults.createAdapter(configuration);
  };
 }
 @Override protected MediaCodecAdapter.Factory getCodecAdapterFactory(){return adapters;}
 @Override protected void buildVideoRenderers(Context context,int mode,MediaCodecSelector selector,
   boolean fallback,Handler handler,VideoRendererEventListener listener,long joiningMs,ArrayList<Renderer> out){
  int first=out.size();
  // Keep Media3's extension renderers and all factory settings; replace only its standard video renderer.
  super.buildVideoRenderers(context,mode,selector,fallback,handler,listener,joiningMs,out);
  for(int i=first;i<out.size();i++)if(out.get(i).getClass()==MediaCodecVideoRenderer.class){
   out.set(i,new MediaCodecVideoRenderer(context,getCodecAdapterFactory(),selector,joiningMs,fallback,
     handler,listener,MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY){
    @Override protected List<MediaCodecInfo> getDecoderInfos(MediaCodecSelector codecs,Format format,boolean secure)
      throws MediaCodecUtil.DecoderQueryException {
     List<MediaCodecInfo> standard=super.getDecoderInfos(codecs,format,secure);
     if(secure||format.drmInitData!=null||!VodVideoPolicy.heavy(format.sampleMimeType,format.width,format.height))return standard;
     ArrayList<MediaCodecInfo> ordered=new ArrayList<>(standard);
     Map<MediaCodecInfo,Integer> rank=new HashMap<>();
     for(MediaCodecInfo info:ordered)rank.put(info,VodVideoPolicy.decoderRank(info.hardwareAccelerated,
       info.isFormatSupported(format),info.isFormatFunctionallySupported(format)));
     // Stable sort AFTER Media3's capability sort: otherwise software can move back to the front.
     java.util.Collections.sort(ordered,(a,b)->Integer.compare(rank.get(a),rank.get(b)));
     if(diagnostics!=null){
      StringBuilder text=new StringBuilder();
      for(int n=0;n<Math.min(8,ordered.size());n++){
       MediaCodecInfo info=ordered.get(n);
       if(n>0)text.append("; ");
       text.append(info.name).append(info.hardwareAccelerated?" [materiel]":" [logiciel]");
      }
      diagnostics.onCandidates(text.toString());
     }
     return ordered;
    }
   });
   break;
  }
 }

}
