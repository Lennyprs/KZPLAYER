package com.kzplayer.app;
import java.net.URLEncoder;import java.text.*;import java.util.*;
/** Native Stalker wire contract: epg.get_simple_data_table and tv_archive.create_link. */
public final class ReplayStalker {
 private ReplayStalker(){}
 private static String enc(String s){try{return URLEncoder.encode(s,"UTF-8");}catch(Exception e){throw new IllegalArgumentException("ENCODAGE");}}
 public static String guideQuery(String channel,String date,int page){
  if(channel==null||channel.isEmpty()||page<1||page>32||date==null||!date.matches("\\d{4}-\\d{2}-\\d{2}"))return "";
  SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd",Locale.US);f.setLenient(false);ParsePosition p=new ParsePosition(0);if(f.parse(date,p)==null||p.getIndex()!=date.length())return "";
  return "type=epg&action=get_simple_data_table&ch_id="+enc(channel)+"&date="+enc(date)+"&p="+page+"&JsHttpRequest=1-xml";
 }
 public static String archiveQuery(String programId){
  if(programId==null||!programId.matches("[1-9][0-9]*"))return "";
  return "type=tv_archive&action=create_link&cmd="+enc("auto /media/"+programId+".mpg")+"&JsHttpRequest=1-xml";
 }
}
