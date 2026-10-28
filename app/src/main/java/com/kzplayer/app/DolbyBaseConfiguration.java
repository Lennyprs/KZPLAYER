package com.kzplayer.app;
/** Configuration gate only. Media3, not a guessed codec/profile, must identify the alternative. */
public final class DolbyBaseConfiguration {
 private DolbyBaseConfiguration(){}
 public static boolean useHevcBase(String sourceMime,String decoderMime,String media3Alternative,
   boolean protectedContent,boolean tunneling){
  return !protectedContent&&!tunneling&&"video/dolby-vision".equals(sourceMime)
    &&"video/hevc".equals(decoderMime)&&decoderMime.equals(media3Alternative);
 }
}
