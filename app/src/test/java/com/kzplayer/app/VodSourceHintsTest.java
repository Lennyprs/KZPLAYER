package com.kzplayer.app;
import org.junit.Test;import static org.junit.Assert.*;
import java.io.*;import java.nio.charset.StandardCharsets;import java.util.*;
import android.net.Uri;import androidx.media3.datasource.*;
public class VodSourceHintsTest {
 private static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
 private static VodSourceHints.Kind kind(String s){byte[] b=bytes(s);return VodSourceHints.classify(b,b.length);}
 private static final String ROOT="https://fixture.invalid/movie/selected.mp4?token=FIXTURE";
 static class Input implements DataSource {
  final byte[] body;int pos,opens,closes,reads;boolean fail;int chunk=Integer.MAX_VALUE;DataSpec spec;
  Input(String s){this(bytes(s));}Input(byte[] b){body=b;}
  public void addTransferListener(TransferListener t){}
  public long open(DataSpec d)throws IOException{opens++;spec=d;pos=(int)d.position;if(fail)throw new IOException("synthetic");return body.length-pos;}
  public int read(byte[] b,int o,int n){reads++;if(n==0)return 0;if(pos==body.length)return -1;int k=Math.min(Math.min(n,chunk),body.length-pos);System.arraycopy(body,pos,b,o,k);pos+=k;return k;}
  public Uri getUri(){return Uri.parse("https://fixture.invalid/redirected");}
  public Map<String,List<String>> getResponseHeaders(){return Map.of("Content-Type",List.of("application/octet-stream"));}
  public void close(){closes++;}
 }
 private static DataSpec spec(String url,long pos){return new DataSpec.Builder().setUri(url).setPosition(pos).build();}
 private static DataSource wrap(Input in,VodSourceHints.State s){return new VodSourceHints.Factory(()->in,s).createDataSource();}
 private static byte[] all(DataSource d)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[317];int n;while((n=d.read(b,0,b.length))!=-1)out.write(b,0,n);return out.toByteArray();}
 @Test public void hlsWithoutExtension(){assertEquals(VodSourceHints.Kind.HLS,kind("#EXTM3U\n#EXT-X-VERSION:3\n"));}
 @Test public void hlsBomWhitespace(){assertEquals(VodSourceHints.Kind.HLS,kind("\uFEFF  #EXTM3U\r\n"));}
 @Test public void notAnArbitraryM3uPrefix(){assertEquals(VodSourceHints.Kind.UNKNOWN,kind("#EXTM3U_FAKE"));}
 @Test public void dashDetected(){assertEquals(VodSourceHints.Kind.DASH,kind("<MPD type=\"static\">"));}
 @Test public void dashAfterXmlDeclaration(){assertEquals(VodSourceHints.Kind.DASH,kind("<?xml version=\"1.0\"?>\n<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\">"));}
 @Test public void dashWithNamespace(){assertEquals(VodSourceHints.Kind.DASH,kind("<d:MPD xmlns:d=\"urn:mpeg:dash:schema:mpd:2011\">"));}
 @Test public void ignoreFakeMpdInsideComment(){assertEquals(VodSourceHints.Kind.HTML,kind("<!-- <MPD> --> <html>error</html>"));}
 @Test public void truncatedXmlNotInvented(){assertEquals(VodSourceHints.Kind.UNKNOWN,kind("<?xml version=\"1.0\""));}
 @Test public void mpdPrefixNotEnough(){assertEquals(VodSourceHints.Kind.UNKNOWN,kind("<MPDFake>"));}
 @Test public void htmlNotVideo(){assertEquals(VodSourceHints.Kind.HTML,kind("<!DOCTYPE html><html>denied</html>"));}
 @Test public void jsonObjectNotVideo(){assertEquals(VodSourceHints.Kind.JSON,kind("{\"error\":true}"));}
 @Test public void jsonArrayNotVideo(){assertEquals(VodSourceHints.Kind.JSON,kind("[1,2]"));}
 @Test public void mkvSignature(){byte[] b={(byte)0x1a,0x45,(byte)0xdf,(byte)0xa3};assertEquals(VodSourceHints.Kind.MKV,VodSourceHints.classify(b,4));}
 @Test public void mp4Signature(){assertEquals(VodSourceHints.Kind.MP4,kind("0000ftypisom"));}
 @Test public void aviSignature(){assertEquals(VodSourceHints.Kind.AVI,kind("RIFF0000AVI "));}
 @Test public void tsSignature(){byte[] b=new byte[377];b[0]=b[188]=b[376]=0x47;assertEquals(VodSourceHints.Kind.TS,VodSourceHints.classify(b,b.length));}
 @Test public void randomBinaryNotCalledHls(){assertEquals(VodSourceHints.Kind.UNKNOWN,VodSourceHints.classify(new byte[]{1,2,3,4},4));}
 @Test public void noExtraReadOrGetOnOpen()throws Exception{Input i=new Input("#EXTM3U\n");VodSourceHints.State s=new VodSourceHints.State();s.begin(ROOT);DataSource d=wrap(i,s);assertEquals((long)i.body.length,d.open(spec(ROOT,0)));assertEquals(1,i.opens);assertEquals(0,i.reads);assertEquals(VodSourceHints.Kind.UNKNOWN,s.getKind());d.close();}
 @Test public void originalBytesUnchanged()throws Exception{Input i=new Input("#EXTM3U\n"+"#fixture\n".repeat(1000));VodSourceHints.State s=new VodSourceHints.State();s.begin(ROOT);DataSource d=wrap(i,s);d.open(spec(ROOT,0));assertTrue(Arrays.equals(i.body,all(d)));assertEquals(1,i.opens);assertEquals(VodSourceHints.Kind.HLS,s.getKind());d.close();d.close();assertEquals(1,i.closes);}
 @Test public void fragmentedReadsAccumulateSignature()throws Exception{Input i=new Input("\uFEFF#EXTM3U\n");i.chunk=1;VodSourceHints.State s=new VodSourceHints.State();s.begin(ROOT);DataSource d=wrap(i,s);d.open(spec(ROOT,0));assertTrue(Arrays.equals(i.body,all(d)));assertEquals(VodSourceHints.Kind.HLS,s.getKind());d.close();}
 @Test public void seekRangeNotSniffed()throws Exception{Input i=new Input("12345678#EXTM3U\n");VodSourceHints.State s=new VodSourceHints.State();s.begin(ROOT);DataSource d=wrap(i,s);d.open(spec(ROOT,8));assertTrue(Arrays.equals(bytes("#EXTM3U\n"),all(d)));assertEquals(VodSourceHints.Kind.UNKNOWN,s.getKind());d.close();}
 @Test public void segmentsDoNotOverrideRoot()throws Exception{Input i=new Input("<html>segment failure</html>");VodSourceHints.State s=new VodSourceHints.State();s.begin(ROOT);DataSource d=wrap(i,s);d.open(spec("https://fixture.invalid/segment.ts",0));all(d);assertEquals(VodSourceHints.Kind.UNKNOWN,s.getKind());d.close();}
 @Test public void oldCallbacksDoNotOverrideNewEpisode()throws Exception{Input i=new Input("<html>old</html>");VodSourceHints.State s=new VodSourceHints.State();s.begin(ROOT);DataSource d=wrap(i,s);d.open(spec(ROOT,0));s.begin("https://fixture.invalid/new");all(d);assertEquals(VodSourceHints.Kind.UNKNOWN,s.getKind());d.close();}
 @Test public void emptyResponseObserved()throws Exception{Input i=new Input("");VodSourceHints.State s=new VodSourceHints.State();s.begin(ROOT);DataSource d=wrap(i,s);d.open(spec(ROOT,0));all(d);assertEquals(VodSourceHints.Kind.EMPTY,s.getKind());d.close();}
 @Test public void failedOpenClosesExactlyOnce()throws Exception{Input i=new Input("");i.fail=true;VodSourceHints.State s=new VodSourceHints.State();s.begin(ROOT);DataSource d=wrap(i,s);try{d.open(spec(ROOT,0));throw new AssertionError();}catch(IOException expected){}d.close();assertEquals(1,i.closes);}
 @Test public void forwardingHeadersAndActualUri()throws Exception{Input i=new Input("0000ftypisom");VodSourceHints.State s=new VodSourceHints.State();s.begin(ROOT);DataSource d=wrap(i,s);d.open(spec(ROOT,0));assertEquals(i.getResponseHeaders(),d.getResponseHeaders());assertEquals(i.getUri().toString(),d.getUri().toString());d.close();}
 @Test public void readZeroDoesNotTouchUpstream()throws Exception{Input i=new Input("data");VodSourceHints.State s=new VodSourceHints.State();s.begin(ROOT);DataSource d=wrap(i,s);d.open(spec(ROOT,0));assertEquals(0,d.read(new byte[0],0,0));assertEquals(0,i.reads);d.close();}
 @Test public void hlsOnlyAfterParsingError(){assertEquals("application/x-mpegURL",VodSourceHints.retryMime("ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED",VodSourceHints.Kind.HLS,"",false));}
 @Test public void dashOnlyAfterParsingError(){assertEquals("application/dash+xml",VodSourceHints.retryMime("ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED",VodSourceHints.Kind.DASH,"",false));}
 @Test public void noNetworkErrorRouting(){assertEquals("",VodSourceHints.retryMime("ERROR_CODE_IO_BAD_HTTP_STATUS",VodSourceHints.Kind.HLS,"",false));}
 @Test public void noCodecErrorRouting(){assertEquals("",VodSourceHints.retryMime("ERROR_CODE_DECODING_FAILED",VodSourceHints.Kind.HLS,"",false));}
 @Test public void oneCorrectionOnly(){assertEquals("",VodSourceHints.retryMime("ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED",VodSourceHints.Kind.HLS,"",true));}
 @Test public void doNotRetryAlreadyUsedHls(){assertEquals("",VodSourceHints.retryMime("ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED",VodSourceHints.Kind.HLS,"application/x-mpegURL",false));}
 @Test public void noGuessForUnknownBody(){assertEquals("",VodSourceHints.retryMime("ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED",VodSourceHints.Kind.UNKNOWN,"",false));}
 @Test public void noCodecTestsForHtml(){assertEquals("",VodSourceHints.retryMime("ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED",VodSourceHints.Kind.HTML,"",false));}
 @Test public void noCodecTestsForJson(){assertEquals("",VodSourceHints.retryMime("ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED",VodSourceHints.Kind.JSON,"",false));}
 @Test public void noMkvToMp4Pretence(){assertEquals("",VodSourceHints.retryMime("ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED",VodSourceHints.Kind.MKV,"",false));}
 @Test public void onlyWhitelistedOutput(){for(VodSourceHints.Kind k:VodSourceHints.Kind.values()){assertFalse(k.label.contains("fixture"));assertFalse(k.label.contains("token"));assertFalse(k.label.contains("https://"));}}
}
