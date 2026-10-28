package com.kzplayer.app;
/** Selection policy only: does not rewrite codec profiles, transcode, or emulate hardware. */
public final class Media3VodPolicy {
 private Media3VodPolicy(){}
 public static final int MAX_WIDTH=1920,MAX_HEIGHT=1080;
 public static boolean decodeFailure(String code){
  return "ERROR_CODE_DECODING_FAILED".equals(code)||"ERROR_CODE_DECODER_INIT_FAILED".equals(code)
   ||"ERROR_CODE_DECODING_FORMAT_UNSUPPORTED".equals(code)||"ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES".equals(code);
 }
 /** Only real, supported, clear AVC/HEVC tracks with strictly fewer pixels are candidates. */
 public static int lowerTrackRank(int failedWidth,int failedHeight,int width,int height,String mime,boolean supported,boolean drm){
  if(!supported||drm||failedWidth<=0||failedHeight<=0||width<=0||height<=0||width>MAX_WIDTH||height>MAX_HEIGHT)return Integer.MAX_VALUE;
  long pixels=(long)width*height;
  if(pixels>=(long)failedWidth*failedHeight)return Integer.MAX_VALUE;
  int codec="video/avc".equals(mime)?0:"video/hevc".equals(mime)?1:-1;
  if(codec<0)return Integer.MAX_VALUE;
  return codec*10000000+(MAX_WIDTH*MAX_HEIGHT-(int)pixels);
 }
}
