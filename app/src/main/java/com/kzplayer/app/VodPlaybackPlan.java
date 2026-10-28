package com.kzplayer.app;
/** Bounded paths. Decoder lists retain avcodec because the codec preference also applies to audio. */
public final class VodPlaybackPlan {
 private VodPlaybackPlan(){}
 public static int next(int mode,boolean heavy){return mode>=0&&mode<2?mode+1:mode==2&&!heavy?3:-1;}
 public static String[] options(int mode){
  switch(mode){
   case 0:return new String[]{":codec=mediacodec_ndk,avcodec",":mediacodec-dr"};
   case 1:return new String[]{":codec=mediacodec_jni,avcodec",":mediacodec-dr"};
   case 2:return new String[]{":codec=mediacodec_ndk,mediacodec_jni,avcodec",":no-mediacodec-dr"};
   case 3:return new String[]{":codec=avcodec",":avcodec-hw=none"};
   default:throw new IllegalArgumentException("Unknown video path");
  }
 }
}
