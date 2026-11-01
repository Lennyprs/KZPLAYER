package com.kzplayer.app;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.util.*;
public class LiveSearchPolicyTest {
 @Test public void liveAction(){assertEquals("get_live_streams",LiveSearchPolicy.action("live"));}
 @Test public void movieAction(){assertEquals("get_vod_streams",LiveSearchPolicy.action("movie"));}
 @Test public void seriesAction(){assertEquals("get_series",LiveSearchPolicy.action("series"));}
 @Test public void livePortal(){assertEquals("itv",LiveSearchPolicy.portalType("live"));}
 @Test public void moviePortal(){assertEquals("vod",LiveSearchPolicy.portalType("movie"));}
 @Test public void seriesPortal(){assertEquals("series",LiveSearchPolicy.portalType("series"));}
 @Test public void liveGenre(){assertEquals("genre",LiveSearchPolicy.portalCategory("live"));}
 @Test public void movieCategory(){assertEquals("category",LiveSearchPolicy.portalCategory("movie"));}
 @Test public void seriesCategory(){assertEquals("category",LiveSearchPolicy.portalCategory("series"));}
 @Test public void twoTf1OwnersRemainSelectable(){assertFalse(LiveSearchPolicy.groupKey("live","one","12","tf1").equals(LiveSearchPolicy.groupKey("live","two","12","tf1")));}
 @Test public void twoStreamsSameOwnerRemainSelectable(){assertFalse(LiveSearchPolicy.groupKey("live","one","12","tf1").equals(LiveSearchPolicy.groupKey("live","one","13","tf1")));}
 @Test public void sameStreamDeduplicates(){assertEquals(LiveSearchPolicy.groupKey("live","one","12","tf1"),LiveSearchPolicy.groupKey("live","one","12","tf1"));}
 @Test public void ownerIdentityCannotCollide(){assertFalse(LiveSearchPolicy.groupKey("live","ab","c","tf1").equals(LiveSearchPolicy.groupKey("live","a","bc","tf1")));}
 @Test public void filmsKeepApprovedGrouping(){assertEquals("movie:harry potter",LiveSearchPolicy.groupKey("movie","one","12","harry potter"));}
 @Test public void seriesKeepApprovedGrouping(){assertEquals("series:lost",LiveSearchPolicy.groupKey("series","two","13","lost"));}
 @Test public void supportedSections(){assertTrue(LiveSearchPolicy.searchKind("live"));assertTrue(LiveSearchPolicy.searchKind("movie"));assertTrue(LiveSearchPolicy.searchKind("series"));assertFalse(LiveSearchPolicy.searchKind("replay"));assertFalse(LiveSearchPolicy.searchKind("favorites"));}
 @Test public void m3uLiveMovieSeriesRemainDistinct()throws Exception{
  String m="#EXTM3U\n#EXTINF:-1 group-title=\"France\",TF1 HD\nhttps://example.invalid/live/1.ts\n#EXTINF:-1 group-title=\"Sport\",Golf Channel\nhttps://example.invalid/live/2.m3u8\n#EXTINF:-1 group-title=\"Films\",Film Test\nhttps://example.invalid/movie/3.mkv\n#EXTINF:-1 group-title=\"Series\",Serie Test S01E01\nhttps://example.invalid/series/4.mp4\n";
  List<SearchM3u.Entry> e=new ArrayList<>();SearchM3u.read(new BufferedReader(new StringReader(m)),e::add);assertEquals(4,e.size());assertEquals("live",e.get(0).kind);assertEquals("TF1 HD",e.get(0).name);assertEquals("live",e.get(1).kind);assertEquals("movie",e.get(2).kind);assertEquals("series",e.get(3).kind);
 }
 @Test public void liveM3uPreservesUserAgent()throws Exception{
  List<SearchM3u.Entry> e=new ArrayList<>();SearchM3u.read(new BufferedReader(new StringReader("#EXTM3U\n#EXTINF:-1,TF1\n#EXTVLCOPT:http-user-agent=TestPlayer\nhttps://example.invalid/live.ts\n")),e::add);assertEquals("TestPlayer",e.get(0).userAgent);assertEquals("https://example.invalid/live.ts",e.get(0).url);
 }
 @Test public void normalizedQueries(){assertEquals(SearchText.fold("GOLF"),SearchText.fold("golf"));assertEquals(SearchText.fold("Télé"),SearchText.fold("tele"));}
}
