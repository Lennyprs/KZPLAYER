package com.kzplayer.app;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class VlcFallbackTest {
 private boolean eligible(boolean live,boolean replay,boolean tried,boolean drm,boolean video,String code,String mime,int w,int h,boolean retry){return VodVideoPolicy.alternateEngine(live,replay,tried,drm,video,code,mime,w,h,retry);}
 @Test public void heavyVideoFailureCanUseAnotherEngine(){assertTrue(eligible(false,false,false,false,true,"ERROR_CODE_DECODING_FAILED","video/dolby-vision",3832,1596,false));}
 @Test public void ordinary4kHevcAlsoCovered(){assertTrue(eligible(false,false,false,false,true,"ERROR_CODE_DECODING_FAILED","video/hevc",3840,2160,false));}
 @Test public void noLiveReroute(){assertFalse(eligible(true,false,false,false,true,"ERROR_CODE_DECODING_FAILED","video/hevc",3840,2160,false));}
 @Test public void noReplayReroute(){assertFalse(eligible(false,true,false,false,true,"ERROR_CODE_DECODING_FAILED","video/hevc",3840,2160,false));}
 @Test public void noLoopToAnotherEngine(){assertFalse(eligible(false,false,true,false,true,"ERROR_CODE_DECODING_FAILED","video/hevc",3840,2160,false));}
 @Test public void noDrmBypass(){assertFalse(eligible(false,false,false,true,true,"ERROR_CODE_DECODING_FAILED","video/dolby-vision",3840,2160,false));}
 @Test public void noAudioReroute(){assertFalse(eligible(false,false,false,false,false,"ERROR_CODE_DECODING_FAILED","audio/eac3",3840,2160,false));}
 @Test public void noHttpAuthenticationReroute(){assertFalse(eligible(false,false,false,false,true,"ERROR_CODE_IO_BAD_HTTP_STATUS","video/hevc",3840,2160,false));}
 @Test public void fullHdVideoFailureMayUseAnotherEngine(){assertTrue(eligible(false,false,false,false,true,"ERROR_CODE_DECODING_FAILED","video/avc",1920,1080,false));}
 @Test public void existingHeavyRecoveryMayContinueAfterLowerTrackFails(){assertTrue(eligible(false,false,false,false,true,"ERROR_CODE_DECODING_FAILED","video/avc",1920,1080,true));}
 @Test public void credentialsStayOnExactOrigin(){assertTrue(VlcStreamBridge.sameOrigin("https://example.invalid/a","https://example.invalid:443/b"));assertFalse(VlcStreamBridge.sameOrigin("https://example.invalid/a","http://example.invalid/b"));assertFalse(VlcStreamBridge.sameOrigin("https://example.invalid/a","https://other.invalid/b"));}
 @Test public void noLocalFileManifestChildren(){assertFalse(VlcStreamBridge.network("file:///private/file"));assertFalse(VlcStreamBridge.network("content://private/file"));assertFalse(VlcStreamBridge.network("http://"));}
 private static HttpURLConnection connection(String url)throws Exception {HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(3000);c.setReadTimeout(3000);return c;}
 private static byte[] body(HttpURLConnection c)throws Exception {try(InputStream in=c.getInputStream()){ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] block=new byte[1024];int n;while((n=in.read(block))!=-1)b.write(block,0,n);return b.toByteArray();}finally{c.disconnect();}}
 @Test public void progressiveBytesAndSignedUrlUnchanged()throws Exception {
  byte[] original=new byte[]{0,1,2,3,(byte)255};String upstream="https://example.invalid/movie.php?signature=synthetic%2Bvalue%3D";List<String> seen=new ArrayList<>();
  try(VlcStreamBridge bridge=new VlcStreamBridge(upstream,(url,offset,length)->{seen.add(url);return new VlcStreamBridge.Source(new ByteArrayInputStream(original),original.length,"video/x-matroska","",url);})){assertTrue(bridge.localUrl().startsWith("http://127.0.0.1:"));assertTrue(Arrays.equals(original,body(connection(bridge.localUrl()))));assertEquals(upstream,seen.get(0));}
 }
 @Test public void headDoesNotConsumeSingleUseUrl()throws Exception {
  AtomicInteger opened=new AtomicInteger();try(VlcStreamBridge bridge=new VlcStreamBridge("https://example.invalid/film",(url,o,l)->{opened.incrementAndGet();throw new IOException();})){
   HttpURLConnection c=connection(bridge.localUrl());c.setRequestMethod("HEAD");assertEquals(200,c.getResponseCode());c.disconnect();assertEquals(0,opened.get());}
 }
 @Test public void rangeOffsetAndLengthForwarded()throws Exception {
  long[] requested=new long[2];try(VlcStreamBridge bridge=new VlcStreamBridge("https://example.invalid/film",(url,o,l)->{requested[0]=o;requested[1]=l;return new VlcStreamBridge.Source(new ByteArrayInputStream(new byte[]{2,3,4,5}),4,"video/mp4","bytes 2-5/8",url);})){
   HttpURLConnection c=connection(bridge.localUrl());c.setRequestProperty("Range","bytes=2-5");assertEquals(206,c.getResponseCode());assertEquals("bytes 2-5/8",c.getHeaderField("Content-Range"));assertEquals(4,body(c).length);assertEquals(2L,requested[0]);assertEquals(4L,requested[1]);}
 }
 @Test public void wrongNonceNeverOpensSource()throws Exception {
  AtomicInteger opened=new AtomicInteger();try(VlcStreamBridge bridge=new VlcStreamBridge("https://example.invalid/film",(url,o,l)->{opened.incrementAndGet();throw new IOException();})){
   String invalid=bridge.localUrl().replaceFirst("/[0-9a-f]{64}/","/wrong/");HttpURLConnection c=connection(invalid);assertEquals(404,c.getResponseCode());c.disconnect();assertEquals(0,opened.get());}
 }
 @Test public void arbitraryUrlQueryCannotBecomeAnOpenProxy()throws Exception {
  AtomicInteger opened=new AtomicInteger();try(VlcStreamBridge bridge=new VlcStreamBridge("https://example.invalid/film",(url,o,l)->{opened.incrementAndGet();throw new IOException();})){
   HttpURLConnection c=connection(bridge.localUrl()+"?url=https://other.invalid/");assertEquals(404,c.getResponseCode());c.disconnect();assertEquals(0,opened.get());}
 }
 @Test public void malformedMultiRangeRejected()throws Exception {
  AtomicInteger opened=new AtomicInteger();try(VlcStreamBridge bridge=new VlcStreamBridge("https://example.invalid/film",(url,o,l)->{opened.incrementAndGet();throw new IOException();})){
   HttpURLConnection c=connection(bridge.localUrl());c.setRequestProperty("Range","bytes=0-1,8-9");assertEquals(416,c.getResponseCode());c.disconnect();assertEquals(0,opened.get());}
 }
 @Test public void oversizedRangeRejected()throws Exception {
  try(VlcStreamBridge bridge=new VlcStreamBridge("https://example.invalid/film",(url,o,l)->{throw new IOException();})){
   HttpURLConnection c=connection(bridge.localUrl());c.setRequestProperty("Range","bytes=9999999999999999999999999-");assertEquals(416,c.getResponseCode());c.disconnect();}
 }
 @Test public void hlsRelativeUrisRewrittenWithoutChangingSignatures()throws Exception {
  String root="https://example.invalid/folder/master.m3u8?key=synthetic";String text="#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4?sig=a%2Fb\"\nsegments/one.ts?sig=x%2By\n";List<String> requested=new ArrayList<>();
  try(VlcStreamBridge bridge=new VlcStreamBridge(root,(url,o,l)->{requested.add(url);byte[] bytes=url.equals(root)?text.getBytes(StandardCharsets.UTF_8):new byte[]{7};return new VlcStreamBridge.Source(new ByteArrayInputStream(bytes),bytes.length,url.equals(root)?"application/vnd.apple.mpegurl":"video/mp2t","",url);})){String rewritten=new String(body(connection(bridge.localUrl())),StandardCharsets.UTF_8);assertFalse(rewritten.contains("example.invalid"));assertFalse(rewritten.contains("x%2By"));String child=Arrays.stream(rewritten.split("\n")).filter(line->line.startsWith("http://")).findFirst().get();assertEquals(1,body(connection(child)).length);assertEquals("https://example.invalid/folder/segments/one.ts?sig=x%2By",requested.get(1));}
 }
 @Test public void independentBridgesHaveDifferentSecrets()throws Exception {
  try(VlcStreamBridge a=new VlcStreamBridge("https://example.invalid/a",(url,o,l)->{throw new IOException();});VlcStreamBridge b=new VlcStreamBridge("https://example.invalid/a",(url,o,l)->{throw new IOException();})){String aKey=new URI(a.localUrl()).getPath();String bKey=new URI(b.localUrl()).getPath();assertFalse(aKey.equals(bKey));}
 }
}
