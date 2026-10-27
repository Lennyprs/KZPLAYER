package com.kzplayer.app;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
/** Stable channel identity, namespaced by the originating playlist. */
public final class LiveRecentKey {
 private LiveRecentKey(){}
 public static String of(String owner,String id,String cmd,String url,String name){
  String type="name",value=name==null?"":name;
  if(id!=null&&!id.trim().isEmpty()){type="id";value=id;}
  else if(cmd!=null&&!cmd.trim().isEmpty()){type="cmd";value=cmd;}
  else if(url!=null&&!url.trim().isEmpty()){type="url";value=url;}
  try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest((owner+"\u0000"+type+"\u0000"+value).getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return s.toString();}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
 }
}
