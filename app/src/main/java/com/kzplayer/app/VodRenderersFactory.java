package com.kzplayer.app;
import android.content.Context;
import android.media.MediaFormat;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.mediacodec.DefaultMediaCodecAdapterFactory;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
/** VOD only. Fix the DV-profile/HEVC-base configuration mismatch without changing native DV. */
public final class VodRenderersFactory extends DefaultRenderersFactory {
 private final MediaCodecAdapter.Factory adapters;
 public VodRenderersFactory(Context context){super(context);
  final DefaultMediaCodecAdapterFactory defaults=new DefaultMediaCodecAdapterFactory(context).forceDisableAsynchronous();
  adapters=configuration->{
   int profile=VodVideoPolicy.baseProfile(configuration.format.sampleMimeType,configuration.codecInfo.codecMimeType,MediaCodecUtil.getAlternativeCodecMimeType(configuration.format));
   if(profile>=0)configuration.mediaFormat.setInteger(MediaFormat.KEY_PROFILE,profile);
   return defaults.createAdapter(configuration);
  };
 }
 @Override protected MediaCodecAdapter.Factory getCodecAdapterFactory(){return adapters;}
}
