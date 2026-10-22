package com.kzplayer.app;

import java.io.Serializable;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.*;

/** V121: provider-declared M3U catch-up. No probing, no invented live fallback.
 * Protocol references: Kodi IPTV Simple README and IPTVnator (see SOURCES-REPLAY-V121.txt).
 * Independently implemented; no Kodi GPL source is incorporated.
 */
public final class ReplayCatchup {
 private ReplayCatchup(){}
 public static final class Config implements Serializable {
  private static final long serialVersionUID=121L;
  public final String mode,source,tvgId,epgUrl;public final double days,correctionHours;
  Config(String m,String s,String id,String epg,double d,double c){mode=m;source=s;tvgId=id;epgUrl=epg;days=d;correctionHours=c;}
  public boolean declared(){return !mode.isEmpty()&&!mode.equals("none")&&!mode.equals("disabled")&&!mode.equals("0");}
 }
 public static Map<String,String> attributes(String line){
  Map<String,String> out=new LinkedHashMap<>();
  Matcher m=Pattern.compile("(?i)([a-z][a-z0-9_-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s,]+))").matcher(line==null?"":line);
  while(m.find())out.put(m.group(1).toLowerCase(Locale.US),m.group(2)!=null?m.group(2):m.group(3)!=null?m.group(3):m.group(4));return out;
 }
 private static double number(String v,double fallback){try{double n=Double.parseDouble(v);return Double.isNaN(n)||Double.isInfinite(n)?fallback:n;}catch(Exception e){return fallback;}}
 public static Config parse(String entry,String header){
  Map<String,String> attrs=new LinkedHashMap<>(attributes(header));attrs.putAll(attributes(entry));
  String mode=attrs.getOrDefault("catchup","").toLowerCase(Locale.US),source=attrs.getOrDefault("catchup-source","");
  double days=number(attrs.get("catchup-days"),number(attrs.get("timeshift"),0));
  if(mode.isEmpty()&&!source.isEmpty())mode="default";
  if(mode.isEmpty()&&days>0)mode="shift";
  if(mode.equals("xc")||mode.equals("xstream")||mode.equals("xstream-1"))mode="xtream";
  if(mode.equals("fs")||mode.equals("flussonic-hls")||mode.equals("flussonic-ts"))mode="flussonic";
  if(mode.equals("1"))mode=source.isEmpty()?"shift":"default";
  return new Config(mode,source,attrs.getOrDefault("tvg-id",""),attrs.getOrDefault("x-tvg-url",attrs.getOrDefault("url-tvg",attrs.getOrDefault("tvg-url",""))),Math.max(0,days),Math.max(-12,Math.min(14,number(attrs.get("catchup-correction"),0))));
 }
 public static boolean available(Config c,long start,long end,long now){return c!=null&&c.declared()&&start>0&&end>start&&end<=now&&(c.days<=0||start>=now-(long)(c.days*86400000.0));}
 private static boolean http(String url){try{URI u=URI.create(url);return u.getHost()!=null&&(u.getScheme().equalsIgnoreCase("http")||u.getScheme().equalsIgnoreCase("https"));}catch(Exception e){return false;}}
 private static String format(long ms,String pattern,TimeZone zone){
  StringBuilder out=new StringBuilder();for(char ch:pattern.toCharArray()){
   String fmt=ch=='Y'?"yyyy":ch=='m'?"MM":ch=='d'?"dd":ch=='H'?"HH":ch=='M'?"mm":ch=='S'?"ss":null;
   if(fmt==null)out.append(ch);else{SimpleDateFormat f=new SimpleDateFormat(fmt,Locale.US);f.setTimeZone(zone);out.append(f.format(new Date(ms)));}
  }return out.toString();
 }
 public static String substitute(String template,long start,long end,long now,String catchupId,TimeZone zone){
  Pattern token=Pattern.compile("\\$?\\{(utc|start|utcend|end|lutc|now|timestamp|duration|offset|catchup-id|[YmdHMS])(?::([^}]+))?\\}");
  Matcher m=token.matcher(template);StringBuffer out=new StringBuffer();boolean archiveEvidence=false;
  while(m.find()){
   String key=m.group(1),arg=m.group(2),value;long number;
   if(key.equals("catchup-id")){if(catchupId==null||catchupId.isEmpty())return "";try{value=java.net.URLEncoder.encode(catchupId,"UTF-8").replace("+","%20");}catch(Exception e){return "";}archiveEvidence=true;}
   else if(key.length()==1){value=format(start,key,zone);archiveEvidence=true;}
   else if(key.equals("duration")||key.equals("offset")){
    number=key.equals("duration")?(end-start)/1000L:Math.max(0,(now-start)/1000L);long divisor=1;
    if(arg!=null){try{divisor=Long.parseLong(arg);}catch(Exception e){return "";}if(divisor<=0)return "";}
    value=String.valueOf(number/divisor);if(key.equals("offset"))archiveEvidence=true;
   }else{
    long instant=key.equals("utc")||key.equals("start")?start:key.equals("utcend")||key.equals("end")?end:now;
    value=arg==null?String.valueOf(instant/1000L):format(instant,arg,zone);
    if(key.equals("utc")||key.equals("start")||key.equals("utcend")||key.equals("end"))archiveEvidence=true;
   }
   m.appendReplacement(out,Matcher.quoteReplacement(value));
  }
  m.appendTail(out);String result=out.toString();return !archiveEvidence||result.contains("{")||result.contains("}")?"":result;
 }
 public static String build(String live,Config c,long start,long end,long now,String catchupId){return build(live,c,start,end,now,catchupId,TimeZone.getDefault());}
 public static String build(String live,Config c,long start,long end,long now,String catchupId,TimeZone zone){
  if(!available(c,start,end,now)||!http(live))return "";
  long correction=(long)(c.correctionHours*3600000.0);start+=correction;end+=correction;
  String candidate="";
  try{
   if(c.mode.equals("append")||c.mode.equals("default")||c.mode.equals("vod")){
    if(c.source.isEmpty())return "";
    String resolved=substitute(c.source,start,end,now,catchupId,zone);if(resolved.isEmpty())return "";
    if(c.mode.equals("append")){
     if(resolved.startsWith("?")&&live.contains("?"))resolved="&"+resolved.substring(1);
     else if(resolved.startsWith("&")&&!live.contains("?"))resolved="?"+resolved.substring(1);
     candidate=live+resolved;
    }else candidate=resolved.startsWith("?")?live.split("\\?",2)[0]+resolved:URI.create(live).resolve(resolved).toString();
   }else if(c.mode.equals("shift")||c.mode.equals("timeshift")){
    candidate=live+(live.contains("?")?"&":"?")+"utc="+(start/1000L)+"&lutc="+(now/1000L);
   }else if(c.mode.equals("flussonic")){
    URI u=URI.create(live);String path=u.getRawPath();int slash=path.lastIndexOf('/');if(slash<0)return "";
    String leaf=path.substring(slash+1);String tail;
    if(leaf.endsWith(".m3u8"))tail=leaf.substring(0,leaf.length()-5)+"-"+(start/1000L)+"-"+((end-start)/1000L)+".m3u8";
    else if(leaf.equals("mpegts"))tail="timeshift_abs-"+(start/1000L)+".ts";else return "";
    candidate=u.getScheme()+"://"+u.getRawAuthority()+path.substring(0,slash+1)+tail+(u.getRawQuery()==null?"":"?"+u.getRawQuery());
   }else if(c.mode.equals("xtream")){
    URI u=URI.create(live);Matcher match=Pattern.compile("^(.*?)/(?:live/)?([^/]+)/([^/]+)/(\\d+)(?:\\.(?:ts|m3u8))?$").matcher(u.getRawPath());if(!match.matches())return "";
    long minutes=Math.max(1L,((end-start)+59999L)/60000L);
    candidate=u.getScheme()+"://"+u.getRawAuthority()+match.group(1)+"/timeshift/"+match.group(2)+"/"+match.group(3)+"/"+minutes+"/"+format(start,"Y-m-d:H-M",zone)+"/"+match.group(4)+".ts"+(u.getRawQuery()==null?"":"?"+u.getRawQuery());
   }
  }catch(Exception ignored){return "";}
  return http(candidate)?candidate:"";
 }
}
