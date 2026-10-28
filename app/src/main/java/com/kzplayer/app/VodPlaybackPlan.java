package com.kzplayer.app;
import java.util.Locale;
/** Bounded native paths. The normal path uses LibVLC device-aware hardware selection. */
public final class VodPlaybackPlan {
 private VodPlaybackPlan(){}
 public static boolean useVlc(boolean replay,String kind,String mode){
  if(replay)return false;
  return "movie".equals(kind)||"series".equals(kind)||((kind==null||kind.isEmpty())&&"vod".equals(mode));
 }
 public static boolean heavyTitle(String title){
  if(title==null)return false;
  String t=title.toUpperCase(Locale.ROOT);
  return t.matches(".*(?:^|[^A-Z0-9])(?:4K|UHD|2160P?|DV)(?:[^A-Z0-9]|$).*")||t.contains("DOLBY VISION")||t.contains("DOLBYVISION")||t.contains("HDR");
 }
 public static int first(boolean knownHeavy){return knownHeavy?1:0;}
 public static int next(int mode,boolean heavy){return mode>=0&&mode<2?mode+1:mode==2&&!heavy?3:-1;}
 public static boolean texture(int mode){return mode==1||mode==2;}
 public static String[] options(int mode){
  switch(mode){
   case 0:return new String[0]; // setHWDecoderEnabled handles device-specific codec defaults.
   case 1:return new String[]{":codec=mediacodec_jni,mediacodec_ndk,all",":mediacodec-dr"};
   case 2:return new String[]{":codec=mediacodec_jni,mediacodec_ndk,all",":no-mediacodec-dr"};
   case 3:return new String[]{":codec=avcodec",":avcodec-hw=none"};
   default:throw new IllegalArgumentException("Unknown video path");
  }
 }
}
