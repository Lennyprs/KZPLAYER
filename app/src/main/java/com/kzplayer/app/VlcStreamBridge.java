package com.kzplayer.app;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/** Private, bounded loopback transport: VLC never receives portal headers or the original URL. */
public final class VlcStreamBridge implements AutoCloseable {
 public interface Opener { Source open(String url,long offset,long length)throws IOException; }
 public static final class Source implements AutoCloseable {
  public final InputStream input; public final long length; public final String type,range,finalUrl;
  public Source(InputStream in,long length,String type,String range,String finalUrl){this.input=in;this.length=length;this.type=type;this.range=range;this.finalUrl=finalUrl;}
  public void close()throws IOException{input.close();}
 }
 private static final int HEADER_LIMIT=16384,MANIFEST_LIMIT=2*1024*1024,ROUTE_LIMIT=32768;
 private static final Pattern RANGE=Pattern.compile("bytes=([0-9]+)-([0-9]*)");
 private static final Pattern ATTR_URI=Pattern.compile("URI=\"([^\"]+)\"");
 private final Opener opener; private final String root,secret; private final ServerSocket server;
 private final Map<String,String> routes=new ConcurrentHashMap<>();
 private final Map<String,String> reverse=new ConcurrentHashMap<>();
 private final Set<Socket> clients=Collections.newSetFromMap(new ConcurrentHashMap<Socket,Boolean>());
 private final ThreadPoolExecutor workers;
 private volatile boolean closed;
 private int routeChars;
 public VlcStreamBridge(String root,Opener opener)throws IOException {
  if(!network(root))throw new IOException("Unsupported source scheme");
  this.root=root;this.opener=opener;routeChars=root.length();
  byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);StringBuilder key=new StringBuilder();
  for(byte b:bytes)key.append(String.format(Locale.ROOT,"%02x",b&255));secret=key.toString();
  server=new ServerSocket();server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),4);
  workers=new ThreadPoolExecutor(2,2,10,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4),r->{Thread t=new Thread(r,"kz-vlc-data");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
  routes.put("root",root);reverse.put(root,"root");
  Thread accept=new Thread(()->{while(!closed)try{Socket s=server.accept();s.setSoTimeout(10000);clients.add(s);try{workers.execute(()->serve(s));}catch(RejectedExecutionException e){clients.remove(s);s.close();}}catch(IOException e){if(closed)break;}},"kz-vlc-loopback");accept.setDaemon(true);accept.start();
 }
 public String localUrl(){return "http://127.0.0.1:"+server.getLocalPort()+"/"+secret+"/root";}
 public static boolean network(String value){try{URI uri=new URI(value);return uri.getHost()!=null&&("http".equalsIgnoreCase(uri.getScheme())||"https".equalsIgnoreCase(uri.getScheme()));}catch(Exception e){return false;}}
 public static boolean sameOrigin(String a,String b){try{URI x=new URI(a),y=new URI(b);return x.getScheme().equalsIgnoreCase(y.getScheme())&&x.getHost().equalsIgnoreCase(y.getHost())&&port(x)==port(y);}catch(Exception e){return false;}}
 private static int port(URI uri){return uri.getPort()>=0?uri.getPort():("https".equalsIgnoreCase(uri.getScheme())?443:80);}
 private synchronized String route(String url)throws IOException {
  if(!network(url))throw new IOException("Unsupported child scheme");String id=reverse.get(url);
  if(id==null){if(routes.size()>=ROUTE_LIMIT||url.length()>16384||routeChars+url.length()>4*1024*1024)throw new IOException("Manifest route limit");routeChars+=url.length();id=Integer.toHexString(routes.size());routes.put(id,url);reverse.put(url,id);}
  return "http://127.0.0.1:"+server.getLocalPort()+"/"+secret+"/"+id;
 }
 private static String line(InputStream in,int[] remaining)throws IOException {
  ByteArrayOutputStream bytes=new ByteArrayOutputStream();int c;
  while((c=in.read())!=-1){if(--remaining[0]<0)throw new IOException("Header limit");if(c=='\n')break;if(c!='\r')bytes.write(c);}
  return c==-1&&bytes.size()==0?null:bytes.toString(StandardCharsets.US_ASCII.name());
 }
 private static String clean(String value,String fallback){return value==null||value.isEmpty()||value.indexOf('\r')>=0||value.indexOf('\n')>=0?fallback:value;}
 private static void reply(OutputStream out,int code,String type,long length,String range)throws IOException {
  StringBuilder h=new StringBuilder("HTTP/1.1 ").append(code).append(code==200?" OK":code==206?" Partial Content":" Error").append("\r\nConnection: close\r\nCache-Control: no-store\r\nAccept-Ranges: bytes\r\nContent-Type: ").append(clean(type,"application/octet-stream")).append("\r\n");
  if(length>=0)h.append("Content-Length: ").append(length).append("\r\n");
  if(range!=null&&!range.isEmpty())h.append("Content-Range: ").append(clean(range,"")).append("\r\n");
  h.append("\r\n");out.write(h.toString().getBytes(StandardCharsets.US_ASCII));out.flush();
 }
 private String rewrite(String text,String base)throws IOException {
  StringBuilder result=new StringBuilder();
  for(String line:text.split("\\r?\\n",-1)){
   String trimmed=line.trim();
   if(!trimmed.isEmpty()&&!trimmed.startsWith("#"))line=route(resolve(base,trimmed));
   else if(trimmed.startsWith("#")){
    Matcher m=ATTR_URI.matcher(line);StringBuffer rewritten=new StringBuffer();
    while(m.find())m.appendReplacement(rewritten,Matcher.quoteReplacement("URI=\""+route(resolve(base,m.group(1)))+"\""));m.appendTail(rewritten);line=rewritten.toString();
   }
   result.append(line).append('\n');
  }
  return result.toString();
 }
 private static String resolve(String base,String child)throws IOException {try{return new URI(base).resolve(child).toString();}catch(Exception e){throw new IOException("Invalid manifest URI");}}
 private void serve(Socket socket){
  boolean sent=false;
  try(Socket ignored=socket){
   InputStream in=socket.getInputStream();OutputStream out=socket.getOutputStream();int[] budget={HEADER_LIMIT};String first=line(in,budget);
   if(first==null)return;String[] request=first.split(" ",3);if(request.length!=3){reply(out,400,"text/plain",0,null);return;}
   String prefix="/"+secret+"/";String target=request[1];
   if(!target.startsWith(prefix)||target.indexOf('?')>=0){reply(out,404,"text/plain",0,null);return;}
   String upstream=routes.get(target.substring(prefix.length()));if(upstream==null){reply(out,404,"text/plain",0,null);return;}
   boolean head="HEAD".equals(request[0]);if(!head&&!"GET".equals(request[0])){reply(out,405,"text/plain",0,null);return;}
   String header,range=null;
   while((header=line(in,budget))!=null&&!header.isEmpty()){int colon=header.indexOf(':');if(colon>0&&header.substring(0,colon).equalsIgnoreCase("Range"))range=header.substring(colon+1).trim();}
   // Never probe a single-use portal URL merely to answer HEAD.
   if(head){reply(out,200,"application/octet-stream",-1,null);return;}
   long offset=0,length=-1;
   if(range!=null){Matcher m=RANGE.matcher(range);if(!m.matches()){reply(out,416,"text/plain",0,null);return;}
    try{offset=Long.parseLong(m.group(1));if(!m.group(2).isEmpty()){long end=Long.parseLong(m.group(2));if(end<offset||end==Long.MAX_VALUE)throw new NumberFormatException();length=end-offset+1;}}catch(NumberFormatException e){reply(out,416,"text/plain",0,null);return;}}
   try(Source source=opener.open(upstream,offset,length)){
    BufferedInputStream data=new BufferedInputStream(source.input,65536);data.mark(16);byte[] prefixBytes=new byte[7];int n=0;while(n<7){int got=data.read(prefixBytes,n,7-n);if(got<0)break;n+=got;}data.reset();
    boolean playlist=source.type!=null&&source.type.toLowerCase(Locale.ROOT).contains("mpegurl")||n==7&&"#EXTM3U".equals(new String(prefixBytes,StandardCharsets.US_ASCII));
    if(playlist){ByteArrayOutputStream body=new ByteArrayOutputStream();byte[] block=new byte[8192];int count;
     while((count=data.read(block))!=-1){if(body.size()+count>MANIFEST_LIMIT)throw new IOException("Manifest limit");body.write(block,0,count);}
     byte[] rewritten=rewrite(body.toString(StandardCharsets.UTF_8.name()),source.finalUrl==null?upstream:source.finalUrl).getBytes(StandardCharsets.UTF_8);
     reply(out,200,"application/vnd.apple.mpegurl",rewritten.length,null);sent=true;out.write(rewritten);
    }else{
     String contentRange=source.range;
     if((contentRange==null||contentRange.isEmpty())&&offset>0&&source.length>0)contentRange="bytes "+offset+"-"+(offset+source.length-1)+"/*";
     reply(out,contentRange!=null&&!contentRange.isEmpty()?206:200,source.type,source.length,contentRange);sent=true;
     byte[] block=new byte[65536];int count;while(!closed&&(count=data.read(block))!=-1)out.write(block,0,count);
    }
    out.flush();
   }
  }catch(Exception e){/* Closing a socket is deliberate; never log signed upstream URLs. */}
  finally{clients.remove(socket);}
 }
 @Override public void close(){if(closed)return;closed=true;try{server.close();}catch(IOException ignored){}for(Socket s:clients)try{s.close();}catch(IOException ignored){}clients.clear();workers.shutdownNow();routes.clear();reverse.clear();}
}
