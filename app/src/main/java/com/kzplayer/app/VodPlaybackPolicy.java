package com.kzplayer.app;
/** Policy only: neither a video transcoder nor an HDR/Dolby converter. */
public final class VodPlaybackPolicy {
 private VodPlaybackPolicy(){}
 public static boolean asynchronousVideo(int sdk,String decoder,String mime,boolean protectedContent){
  return sdk>=23&&!protectedContent&&"OMX.allwinner.video.decoder.hevc".equalsIgnoreCase(decoder)&&"video/hevc".equalsIgnoreCase(mime);
 }
 public static boolean decoderFailure(String error,boolean video){
  return video&&("ERROR_CODE_DECODING_FAILED".equals(error)||"ERROR_CODE_DECODER_INIT_FAILED".equals(error));
 }
 public static boolean compatible1080(boolean supported,String mime,int width,int height,boolean hdr){
  return supported&&!hdr&&("video/avc".equals(mime)||"video/hevc".equals(mime))&&width>0&&height>0&&Math.max(width,height)<=1920&&Math.min(width,height)<=1080;
 }
 public static final class RecoveryBudget {
  private boolean used;
  public synchronized boolean claim(boolean decoderFailure,boolean targetCodec){if(used||!decoderFailure||!targetCodec)return false;used=true;return true;}
  public synchronized void markUsed(){used=true;}
  public synchronized void reset(){used=false;}
 }
}
