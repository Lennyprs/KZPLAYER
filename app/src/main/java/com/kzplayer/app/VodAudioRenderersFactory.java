package com.kzplayer.app;
import android.content.Context;
import android.os.Handler;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.ChannelMixingAudioProcessor;
import androidx.media3.common.audio.ChannelMixingMatrix;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.audio.AudioCapabilities;
import androidx.media3.exoplayer.audio.AudioOffloadSupport;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import java.util.ArrayList;
/** VOD only: decode audio locally and output PCM, not encoded HDMI passthrough. */
public class VodAudioRenderersFactory extends DefaultRenderersFactory {
 public VodAudioRenderersFactory(Context context){super(context);}
 @Override protected void buildAudioRenderers(Context context,int mode,MediaCodecSelector selector,
   boolean fallback,AudioSink sink,Handler handler,AudioRendererEventListener listener,ArrayList<Renderer> out){
  // FFmpeg first when available/supported, ordinary Media3 audio decoder remains present.
  // The inherited VIDEO renderer and codec policy are never replaced.
  super.buildAudioRenderers(context,EXTENSION_RENDERER_MODE_PREFER,selector,fallback,sink,handler,listener,out);
 }
 @SuppressWarnings("deprecation")
 @Override protected AudioSink buildAudioSink(Context context,boolean floatOutput,boolean trackPlaybackParams){
  ChannelMixingAudioProcessor mixer=new ChannelMixingAudioProcessor();
  for(int channels=1;channels<=8;channels++)mixer.putChannelMixingMatrix(
    new ChannelMixingMatrix(channels,channels<=2?channels:2,VodStereoMatrix.coefficients(channels)));
  // Media3 1.4.1: Builder(Context) ignores setAudioCapabilities and re-reads HDMI.
  // The legacy no-context builder deliberately fixes PCM-only sink capabilities.
  return new DefaultAudioSink.Builder()
    .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
    .setAudioOffloadSupportProvider((format,attributes)->AudioOffloadSupport.DEFAULT_UNSUPPORTED)
    .setEnableFloatOutput(false)
    .setEnableAudioTrackPlaybackParams(false)
    .setAudioProcessors(new AudioProcessor[]{mixer})
    .build();
 }
}
