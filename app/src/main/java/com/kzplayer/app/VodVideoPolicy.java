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
 public static boolean heavy(String mime,int width,int height){return "video/dolby-vision".equals(mime)||width>=3000||height>=1600;}
 public static boolean decodeFailure(String code){return "ERROR_CODE_DECODING_FAILED".equals(code)||"ERROR_CODE_DECODER_INIT_FAILED".equals(code)||"ERROR_CODE_DECODING_FORMAT_UNSUPPORTED".equals(code)||"ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES".equals(code);}
}
