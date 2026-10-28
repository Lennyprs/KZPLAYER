package com.kzplayer.app;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.*;import java.nio.charset.StandardCharsets;import java.util.*;
import android.net.Uri;import androidx.media3.datasource.*;
/** Real Android API required: run with connectedDebugAndroidTest on a device/emulator. */
@RunWith(AndroidJUnit4.class)
public class VodSourceHintsAndroidTest {
 private static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
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
}
