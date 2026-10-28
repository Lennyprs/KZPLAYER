package com.kzplayer.app;

import android.net.Uri;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/** Passive inspection of bytes ALREADY read by Media3: no probe, prefetch or second GET. */
public final class VodSourceHints {
 private VodSourceHints(){}
 public static final int LIMIT=4096;
 public enum Kind {
  UNKNOWN("non identifie",""), EMPTY("reponse vide",""), HLS("manifeste HLS","application/x-mpegURL"),
  DASH("manifeste DASH","application/dash+xml"), MP4("MP4/ISO-BMFF",""), MKV("Matroska/WebM",""),
  TS("MPEG-TS",""), FLV("FLV",""), AVI("AVI",""), HTML("page HTML, pas une video",""),
  JSON("reponse JSON, pas une video","");
  public final String label,mime;
  Kind(String label,String mime){this.label=label;this.mime=mime;}
 }
 public static Kind classify(byte[] b,int n){
  if(n<=0)return Kind.UNKNOWN;
  n=Math.min(n,Math.min(b.length,LIMIT));
  if(n>=4&&(b[0]&255)==0x1a&&(b[1]&255)==0x45&&(b[2]&255)==0xdf&&(b[3]&255)==0xa3)return Kind.MKV;
  if(n>=8&&(ascii(b,4,"ftyp")||ascii(b,4,"styp")||ascii(b,4,"moov")||ascii(b,4,"moof")))return Kind.MP4;
  if(n>=12&&ascii(b,0,"RIFF")&&ascii(b,8,"AVI "))return Kind.AVI;
  if(n>=3&&ascii(b,0,"FLV"))return Kind.FLV;
  if(n>=377&&(b[0]&255)==0x47&&(b[188]&255)==0x47&&(b[376]&255)==0x47)return Kind.TS;
  String text=new String(b,0,n,StandardCharsets.UTF_8).trim();
  if(text.startsWith("\uFEFF"))text=text.substring(1).trim();
  if(text.startsWith("#EXTM3U")&&(text.length()==7||Character.isWhitespace(text.charAt(7))))return Kind.HLS;
  if(text.startsWith("{")||text.startsWith("["))return Kind.JSON;
  // Strip only complete, bounded XML declarations/comments before examining the root.
  String xml=text;
  for(int i=0;i<16;i++){
   if(xml.startsWith("<?")){int end=xml.indexOf("?>");if(end<0)return Kind.UNKNOWN;xml=xml.substring(end+2).trim();}
   else if(xml.startsWith("<!--")){int end=xml.indexOf("-->");if(end<0)return Kind.UNKNOWN;xml=xml.substring(end+3).trim();}
   else break;
  }
  if(xml.matches("(?s)^<(?:[A-Za-z_][A-Za-z0-9_.-]*:)?MPD(?:[\\s>/]).*"))return Kind.DASH;
  String lower=xml.toLowerCase(Locale.ROOT);
  if(lower.startsWith("<!doctype html")||lower.startsWith("<html")||lower.startsWith("<head")||lower.startsWith("<body"))return Kind.HTML;
  return Kind.UNKNOWN;
 }
 private static boolean ascii(byte[] b,int offset,String s){
  if(offset+s.length()>b.length)return false;
  for(int i=0;i<s.length();i++)if((b[offset+i]&255)!=s.charAt(i))return false;
  return true;
 }
 /** Keeps only whitelisted format labels; never exposes URL, headers, body or account data. */
 public static final class State {
  private String root="";private long generation;
  private volatile Kind kind=Kind.UNKNOWN;
  public synchronized void begin(String url){if(!root.equals(url)){root=url;generation++;kind=Kind.UNKNOWN;}}
  public Kind getKind(){return kind;}
  private synchronized long token(String url){return root.equals(url)?generation:-1;}
  private synchronized void observed(long token,Kind value){if(token==generation&&token>=0&&value!=Kind.UNKNOWN)kind=value;}
 }
 public static final class Factory implements DataSource.Factory {
  private final DataSource.Factory upstream;private final State state;
  public Factory(DataSource.Factory upstream,State state){this.upstream=upstream;this.state=state;}
  @Override public DataSource createDataSource(){return new Inspecting(upstream.createDataSource(),state);}
 }
 private static final class Inspecting implements DataSource {
  private final DataSource upstream;private final State state;
  private final byte[] prefix=new byte[LIMIT];private int size;private long token=-1;private boolean opened;
  Inspecting(DataSource upstream,State state){this.upstream=upstream;this.state=state;}
  @Override public void addTransferListener(TransferListener t){upstream.addTransferListener(t);}
  @Override public long open(DataSpec spec)throws IOException{
   size=0;token=spec.position==0?state.token(spec.uri.toString()):-1;opened=true;
   try{return upstream.open(spec);}catch(IOException|RuntimeException e){try{close();}catch(IOException ignored){}throw e;}
  }
  @Override public int read(byte[] b,int o,int length)throws IOException{
   if(length==0)return 0;
   int n=upstream.read(b,o,length);
   if(token>=0&&size<LIMIT){
    if(n>0){int copy=Math.min(n,LIMIT-size);System.arraycopy(b,o,prefix,size,copy);size+=copy;state.observed(token,classify(prefix,size));}
    else if(n==-1&&size==0)state.observed(token,Kind.EMPTY);
   }
   return n;
  }
  @Override public Uri getUri(){return upstream.getUri();}
  @Override public Map<String,List<String>> getResponseHeaders(){return upstream.getResponseHeaders();}
  @Override public void close()throws IOException{token=-1;size=0;if(opened){opened=false;upstream.close();}}
 }
 public static boolean parseFailure(String code){
  return "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED".equals(code)||"ERROR_CODE_PARSING_CONTAINER_MALFORMED".equals(code)
   ||"ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED".equals(code)||"ERROR_CODE_PARSING_MANIFEST_MALFORMED".equals(code);
 }
 public static String retryMime(String code,Kind kind,String alreadyUsed,boolean tried){
  if(tried||!parseFailure(code)||kind==null||kind.mime.isEmpty()||kind.mime.equals(alreadyUsed))return "";
  return kind.mime;
 }
}
