package com.kzplayer.app;
/** Pure formatting: never emit exception messages, arbitrary diagnostic strings, or URLs. */
public final class CodecDiagnosticText {
 private CodecDiagnosticText(){}
 public static String diagnostic(String text){
  return text!=null&&text.matches("android\\.media\\.MediaCodec\\.error_(neg_)?[0-9]{1,10}")?text:"non fourni ou format non standard masque";
 }
 public static String causeName(String name){
  if(name!=null)switch(name){case "ExoPlaybackException":case "PlaybackException":case "MediaCodecVideoDecoderException":case "MediaCodecDecoderException":case "CodecException":case "IllegalStateException":case "IllegalArgumentException":case "DecoderInitializationException":return name;}
  return "autre exception";
 }
 public static String operation(String name){
  if(name!=null)switch(name){case "configure":case "native_configure":case "start":case "stop":case "flush":case "dequeueInputBuffer":case "dequeueOutputBuffer":case "native_dequeueOutputBuffer":case "getInputBuffer":case "getOutputBuffer":case "queueInputBuffer":case "native_queueInputBuffer":case "releaseOutputBuffer":return name;}
  return "non fournie";
 }
}
