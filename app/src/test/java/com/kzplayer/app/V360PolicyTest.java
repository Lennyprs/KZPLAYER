package com.kzplayer.app;
import org.junit.Test;
import static org.junit.Assert.*;

public class V360PolicyTest {
 @Test public void mapsDvToCompatibleHevcProfile(){assertEquals(2,VodVideoPolicy.baseProfile("video/dolby-vision","video/hevc","video/hevc"));}
 @Test public void mapsDvToCompatibleAvcProfile(){assertEquals(8,VodVideoPolicy.baseProfile("video/dolby-vision","video/avc","video/avc"));}
 @Test public void leavesNativeDolbyVisionUnchanged(){assertEquals(-1,VodVideoPolicy.baseProfile("video/dolby-vision","video/dolby-vision","video/hevc"));}
 @Test public void doesNotInventACompatibleBaseLayer(){assertEquals(-1,VodVideoPolicy.baseProfile("video/dolby-vision","video/hevc",null));}
 @Test public void doesNotChangeOrdinaryHevc(){assertEquals(-1,VodVideoPolicy.baseProfile("video/hevc","video/hevc","video/hevc"));}
 @Test public void rejectsMismatchedBaseCodec(){assertEquals(-1,VodVideoPolicy.baseProfile("video/dolby-vision","video/hevc","video/avc"));}
 @Test public void rejectsUnknownBaseCodec(){assertEquals(-1,VodVideoPolicy.baseProfile("video/dolby-vision","video/av01","video/av01"));}
 @Test public void recognizesCinema4k(){assertTrue(VodVideoPolicy.heavy("video/hevc",3840,1600));}
 @Test public void recognizesDolbyWithUnknownSize(){assertTrue(VodVideoPolicy.heavy("video/dolby-vision",-1,-1));}
 @Test public void excludesOrdinaryFullHd(){assertFalse(VodVideoPolicy.heavy("video/avc",1920,1080));}
 @Test public void recognizesRuntimeDecodeFailure(){assertTrue(VodVideoPolicy.decodeFailure("ERROR_CODE_DECODING_FAILED"));}
 @Test public void doesNotTreatNetworkErrorAsVideoFailure(){assertFalse(VodVideoPolicy.decodeFailure("ERROR_CODE_IO_BAD_HTTP_STATUS"));}
 @Test public void sameChannelIdIgnoresChangingPlaybackUrl(){assertEquals(LiveRecentKey.of("list-a","42","original","url-a","TV"),LiveRecentKey.of("list-a","42","changed","url-b","TV HD"));}
 @Test public void differentListsKeepSameChannelIdsSeparate(){assertFalse(LiveRecentKey.of("list-a","42",null,null,"TV").equals(LiveRecentKey.of("list-b","42",null,null,"TV")));}
 @Test public void stablePortalCommandIgnoresResolvedTokenUrl(){assertEquals(LiveRecentKey.of("list-a",null,"source-command","resolved-a","TV"),LiveRecentKey.of("list-a",null,"source-command","resolved-b","TV"));}
 @Test public void m3uSourcesRemainDistinct(){assertFalse(LiveRecentKey.of("list-a",null,null,"fixture-a","TV").equals(LiveRecentKey.of("list-a",null,null,"fixture-b","TV")));}
 @Test public void blankIdentifiersFallBackToTheName(){assertEquals(LiveRecentKey.of("list-a",null,null,null,"TV"),LiveRecentKey.of("list-a","","","","TV"));}
 @Test public void identityDoesNotRevealSourceInformation(){String key=LiveRecentKey.of("list-a","42","synthetic-command","fixture","TV");assertTrue(key.matches("[0-9a-f]{64}"));}
}
