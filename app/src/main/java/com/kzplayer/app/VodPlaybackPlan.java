package com.kzplayer.app;
import java.util.Locale;
/** Bounded native paths. The normal path uses LibVLC device-aware hardware selection. */
public final class VodPlaybackPlan {
 private VodPlaybackPlan(){}
 public static boolean useVlc(boolean replay,String kind,String mode){
  if(replay)return false;
  return "movie".equals(kind)||"series".equals(kind)||((kind==null||kind.isEmpty())&&"vod".equals(mode));
 }
 /** After VLC's bounded hardware attempts fail, allow one Media3 attempt without routing back to VLC. */
 public static boolean useVlc(boolean replay,String kind,String mode,boolean media3FallbackAttempted){
  return !media3FallbackAttempted && useVlc(replay,kind,mode);
 }
 public static boolean heavyTitle(String title){
  if(title==null)return false;
  String t=title.toUpperCase(Locale.ROOT);
  return t.matches(".*(?:^|[^A-Z0-9])(?:4K|UHD|2160P?|DV)(?:[^A-Z0-9]|$).*")||t.contains("DOLBY VISION")||t.contains("DOLBYVISION")||t.contains("HDR");
 }
 public static int first(boolean knownHeavy){return 0;}
 // Heavy/HDR media gets three bounded paths: default hardware, direct-render hardware,
 // then hardware without direct rendering. Never force software decoding for heavy video.
 public static int next(int mode,boolean heavy){return heavy?(mode>=0&&mode<2?mode+1:-1):(mode>=0&&mode<3?mode+1:-1);}
 public static boolean texture(int mode){return mode==1||mode==2;}
 public static boolean texture(int mode,boolean heavy){return !heavy&&texture(mode);}
 public static String[] options(int mode){
  switch(mode){
   case 0:return new String[0]; // setHWDecoderEnabled handles device-specific codec defaults.
   // Keep the heavy-media retries on Android hardware decoders; don't let "all"
   // silently select a software video decoder on a 4K/HDR stream.
   case 1:return new String[]{":codec=mediacodec_jni,mediacodec_ndk",":mediacodec-dr"};
   case 2:return new String[]{":codec=mediacodec_jni,mediacodec_ndk",":no-mediacodec-dr"};
   case 3:return new String[]{":codec=avcodec",":avcodec-hw=none"};
   default:throw new IllegalArgumentException("Unknown video path");
  }
 }
}
