package com.kzplayer.app;
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException;
import androidx.media3.exoplayer.video.MediaCodecVideoDecoderException;
import java.util.IdentityHashMap;
/** Read existing error metadata only. No file, network, logcat or playback action. */
public final class PlaybackFailureDetails {
 private PlaybackFailureDetails(){}
 public static String describe(Throwable error){
  StringBuilder chain=new StringBuilder();String diagnostic=null,operation="non fournie";
  Integer code=null;Boolean surface=null;
  IdentityHashMap<Throwable,Boolean> seen=new IdentityHashMap<>();
  Throwable current=error;
  for(int depth=0;current!=null&&depth<10&&!seen.containsKey(current);depth++){
   seen.put(current,Boolean.TRUE);
   if(chain.length()>0)chain.append(" > ");
   chain.append(CodecDiagnosticText.causeName(current.getClass().getSimpleName()));
   if(current instanceof MediaCodecDecoderException){
    MediaCodecDecoderException codec=(MediaCodecDecoderException)current;
    if(code==null){code=codec.errorCode;diagnostic=codec.diagnosticInfo;}
   }
   if(current instanceof MediaCodecVideoDecoderException)surface=((MediaCodecVideoDecoderException)current).isSurfaceValid;
   if("non fournie".equals(operation))for(StackTraceElement frame:current.getStackTrace()){
    if("android.media.MediaCodec".equals(frame.getClassName())){
     String candidate=CodecDiagnosticText.operation(frame.getMethodName());
     if(!"non fournie".equals(candidate)){operation=candidate;break;}
    }
   }
   current=current.getCause();
  }
  return "Causes : "+(chain.length()==0?"non fournies":chain.toString())
    +"\nCode codec : "+(code==null||code==0?"non transmis":Integer.toString(code))
    +"\nDiagnostic codec : "+CodecDiagnosticText.diagnostic(diagnostic)
    +"\nOperation Android : "+operation
    +"\nSurface (indicateur Media3) : "+(surface==null?"non renseignee":surface?"valide":"invalide");
 }
}
