package com.kzplayer.app;
import org.junit.Test;import static org.junit.Assert.*;
public class DolbyBaseConfigurationTest {
 private boolean gate(String source,String decoder,String alternative,boolean drm,boolean tunnel){return DolbyBaseConfiguration.useHevcBase(source,decoder,alternative,drm,tunnel);}
 @Test public void recognizedHevcBaseAccepted(){assertTrue(gate("video/dolby-vision","video/hevc","video/hevc",false,false));}
 @Test public void nativeDolbyDecoderUntouched(){assertFalse(gate("video/dolby-vision","video/dolby-vision","video/hevc",false,false));}
 @Test public void ordinaryHevcUntouched(){assertFalse(gate("video/hevc","video/hevc",null,false,false));}
 @Test public void ordinaryAvcUntouched(){assertFalse(gate("video/avc","video/avc",null,false,false));}
 @Test public void avcBaseNotChangedByHevcFix(){assertFalse(gate("video/dolby-vision","video/avc","video/avc",false,false));}
 @Test public void av1BaseNotChangedByHevcFix(){assertFalse(gate("video/dolby-vision","video/av01","video/av01",false,false));}
 @Test public void noInventedBase(){assertFalse(gate("video/dolby-vision","video/hevc",null,false,false));}
 @Test public void mismatchedBaseRejected(){assertFalse(gate("video/dolby-vision","video/hevc","video/avc",false,false));}
 @Test public void protectedContentUntouched(){assertFalse(gate("video/dolby-vision","video/hevc","video/hevc",true,false));}
 @Test public void tunnelingUntouched(){assertFalse(gate("video/dolby-vision","video/hevc","video/hevc",false,true));}
 @Test public void nullSourceUntouched(){assertFalse(gate(null,"video/hevc","video/hevc",false,false));}
 @Test public void nullDecoderUntouched(){assertFalse(gate("video/dolby-vision",null,"video/hevc",false,false));}
 @Test public void emptyAlternativeRejected(){assertFalse(gate("video/dolby-vision","video/hevc","",false,false));}
 @Test public void sameResolutionNotRelevant(){assertTrue(gate("video/dolby-vision","video/hevc","video/hevc",false,false));}
}
