package com.kzplayer.app;
import java.io.*;import java.text.*;import java.util.*;import javax.xml.parsers.SAXParserFactory;import org.xml.sax.*;import org.xml.sax.ext.DefaultHandler2;
/** Streaming XMLTV reader: one requested channel; bounded input, no DTD or external entities. */
public final class ReplayXmltv {
 private ReplayXmltv(){}
 public static final class Program {public final String title,description,id;public final long start,end;Program(String t,String d,String i,long s,long e){title=t;description=d;id=i;start=s;end=e;}}
 public static long timestamp(String text){
  if(text==null)return 0;String t=text.trim();for(String pattern:new String[]{"yyyyMMddHHmmss Z","yyyyMMddHHmm Z","yyyyMMddHHmmss","yyyyMMddHHmm"})try{SimpleDateFormat f=new SimpleDateFormat(pattern,Locale.US);f.setLenient(false);f.setTimeZone(TimeZone.getTimeZone("UTC"));ParsePosition p=new ParsePosition(0);Date d=f.parse(t,p);if(d!=null&&p.getIndex()==t.length())return d.getTime();}catch(Exception ignored){}return 0;
 }
 public static List<Program> parse(InputStream input,String channel,long now)throws Exception{
  List<Program> out=new ArrayList<>();if(channel==null||channel.isEmpty())return out;
  InputStream bounded=new FilterInputStream(input){long count;private void add(int n)throws IOException{if(n>0&&(count+=n)>67108864L)throw new IOException("GUIDE_TROP_VOLUMINEUX");}public int read()throws IOException{int n=super.read();add(n<0?0:1);return n;}public int read(byte[]b,int o,int l)throws IOException{int n=in.read(b,o,l);add(n);return n;}};
  DefaultHandler2 handler=new DefaultHandler2(){boolean selected;String field="",title="",description="",id="";long start,end;StringBuilder text=new StringBuilder();
   public void startDTD(String n,String p,String s)throws SAXException{throw new SAXException("DTD_INTERDIT");}
   public InputSource resolveEntity(String p,String s)throws SAXException{throw new SAXException("ENTITE_EXTERNE_INTERDITE");}
   public void startElement(String uri,String local,String q,Attributes a){
    if(q.equals("programme")){selected=channel.equals(a.getValue("channel"));start=timestamp(a.getValue("start"));end=timestamp(a.getValue("stop"));title="";description="";id=a.getValue("catchup-id");if(id==null)id="";}
    if(selected&&(q.equals("title")||q.equals("desc")||q.equals("catchup-id"))){field=q;text.setLength(0);}
   }
   public void characters(char[]c,int o,int n){if(selected&&!field.isEmpty()&&text.length()<16384)text.append(c,o,Math.min(n,16384-text.length()));}
   public void endElement(String uri,String local,String q){
    if(selected&&q.equals(field)){String v=text.toString().trim();if(q.equals("title")&&title.isEmpty())title=v;else if(q.equals("desc")&&description.isEmpty())description=v;else if(q.equals("catchup-id"))id=v;field="";}
    if(q.equals("programme")){if(selected&&start>0&&end>start&&end<=now&&start>=now-31L*86400000L&&!title.isEmpty()&&out.size()<5000)out.add(new Program(title,description,id,start,end));selected=false;field="";}
   }
  };
  SAXParserFactory factory=SAXParserFactory.newInstance();factory.setNamespaceAware(false);XMLReader reader=factory.newSAXParser().getXMLReader();
  reader.setContentHandler(handler);reader.setEntityResolver(handler);reader.setErrorHandler(handler);reader.setProperty("http://xml.org/sax/properties/lexical-handler",handler);
  reader.parse(new InputSource(bounded));out.sort((a,b)->Long.compare(b.start,a.start));return out;
 }
}
