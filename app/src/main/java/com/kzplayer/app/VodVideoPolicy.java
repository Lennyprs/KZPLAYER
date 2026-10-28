package com.kzplayer.app;
/** No HDR conversion or invented software video decoder: only a compatible base layer. */
public final class VodVideoPolicy {
 private VodVideoPolicy(){}
 public static int baseProfile(String source,String codec,String compatible){
  if(!"video/dolby-vision".equals(source)||codec==null||!codec.equals(compatible))return -1;
  if("video/hevc".equals(codec))return 2; // Android HEVCProfileMain10, NOT the DV profile bitmask.
  if("video/avc".equals(codec))return 8; // Android AVCProfileHigh.
  return -1;
 }
 public static int decoderRank(boolean hardware,boolean supported,boolean functional){
  if(hardware&&supported)return 0;
  if(hardware&&functional)return 1;
  if(!hardware&&supported)return 2;
  return hardware?3:4;
 }
 public static boolean platformSoftware(String name){
  if(name==null)return false;
  String n=name.toLowerCase(java.util.Locale.ROOT);
  return n.startsWith("c2.android.")||n.startsWith("omx.google.")||n.startsWith("omx.ffmpeg.");
 }
 public static boolean hardwareProbe(String failed,String source,String base,String codec){
  if(!platformSoftware(failed)||codec==null)return false;
  if("video/dolby-vision".equals(source))return codec.equals(base)&&("video/hevc".equals(base)||"video/avc".equals(base));
  return codec.equals(source)&&("video/hevc".equals(source)||"video/avc".equals(source)||"video/av01".equals(source)||"video/x-vnd.on2.vp9".equals(source));
 }
 public static boolean alternateEngine(boolean live,boolean replay,boolean tried,boolean drm,boolean video,String code,String mime,int width,int height,boolean recovering){
  return !live&&!replay&&!tried&&!drm&&video&&decodeFailure(code);
 }
 public static boolean sameDefinition(int sourceWidth,int sourceHeight,int width,int height){return (sourceWidth<=0||width>=sourceWidth)&&(sourceHeight<=0||height>=sourceHeight);}
 public static boolean heavy(String mime,int width,int height){return "video/dolby-vision".equals(mime)||width>=3000||height>=1600;}
 public static boolean decodeFailure(String code){return "ERROR_CODE_DECODING_FAILED".equals(code)||"ERROR_CODE_DECODER_INIT_FAILED".equals(code)||"ERROR_CODE_DECODING_FORMAT_UNSUPPORTED".equals(code)||"ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES".equals(code);}
}
