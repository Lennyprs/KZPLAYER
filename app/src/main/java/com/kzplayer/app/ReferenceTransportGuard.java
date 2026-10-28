package com.kzplayer.app;
import java.net.URI;
/** Same media resource only. No catalogue substitutions or opaque token/credential rewriting. */
public final class ReferenceTransportGuard {
 private ReferenceTransportGuard(){}
 private static String stem(String value){return value==null?"":value.replaceFirst("(?i)\\.(mkv|mp4|ts|avi|m4v)$","");}
 public static boolean sameResource(String original,String candidate){
  if(original==null||candidate==null)return false;
  if(original.equals(candidate))return true;
  try{
   URI a=new URI(original),b=new URI(candidate);
   if(!("http".equalsIgnoreCase(a.getScheme())||"https".equalsIgnoreCase(a.getScheme())))return false;
   if(!java.util.Objects.equals(a.getScheme(),b.getScheme())||!java.util.Objects.equals(a.getRawAuthority(),b.getRawAuthority())||!java.util.Objects.equals(a.getRawFragment(),b.getRawFragment()))return false;
   if(!stem(a.getRawPath()).equals(stem(b.getRawPath())))return false;
   if(java.util.Objects.equals(a.getRawQuery(),b.getRawQuery()))return true;
   if(a.getRawQuery()==null||b.getRawQuery()==null)return false;
   String[] qa=a.getRawQuery().split("&",-1),qb=b.getRawQuery().split("&",-1);
   if(qa.length!=qb.length)return false;
   for(int i=0;i<qa.length;i++){
    if(qa[i].equals(qb[i]))continue;
    if(!qa[i].matches("stream=[0-9]+(?i:\\.(mkv|mp4|ts|avi|m4v))?")||!qb[i].matches("stream=[0-9]+(?i:\\.(mkv|mp4|ts|avi|m4v))?"))return false;
    if(!stem(qa[i]).equals(stem(qb[i])))return false;
   }
   return true;
  }catch(java.net.URISyntaxException ignored){return false;}
 }
}
