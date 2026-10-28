package com.kzplayer.app;
import org.junit.Test;import static org.junit.Assert.*;
public class VlcPrimaryTest {
 @Test public void movieStartsInVlc(){assertTrue(VodPlaybackPlan.useVlc(false,"movie","vod"));}
 @Test public void seriesStartsInVlc(){assertTrue(VodPlaybackPlan.useVlc(false,"series","vod"));}
 @Test public void movieDoesNotNeedACodecFailureFirst(){assertTrue(VodPlaybackPlan.useVlc(false,"movie","live"));}
 @Test public void missingHistoryKindMayUseExplicitVodMode(){assertTrue(VodPlaybackPlan.useVlc(false,null,"vod"));}
 @Test public void channelRemainsInExo(){assertFalse(VodPlaybackPlan.useVlc(false,"live","live"));}
 @Test public void channelNeverRoutesBecauseOfAFileExtension(){assertFalse(VodPlaybackPlan.useVlc(false,"live","vod"));}
 @Test public void replayRemainsInExo(){assertFalse(VodPlaybackPlan.useVlc(true,"movie","vod"));assertFalse(VodPlaybackPlan.useVlc(true,"live","live"));}
 @Test public void trailersRemainSeparate(){assertFalse(VodPlaybackPlan.useVlc(false,"trailer","vod"));}
 @Test public void unknownLiveEntryRemainsInExo(){assertFalse(VodPlaybackPlan.useVlc(false,null,"live"));}
 @Test public void media3FallbackDoesNotRouteBackToVlc(){assertFalse(VodPlaybackPlan.useVlc(false,"movie","vod",true));assertFalse(VodPlaybackPlan.useVlc(false,"series","vod",true));assertTrue(VodPlaybackPlan.useVlc(false,"movie","vod",false));}
 @Test public void normalFullHdKeepsTheNormalSurface(){assertEquals(0,VodPlaybackPlan.first(false));assertFalse(VodPlaybackPlan.texture(0));}
 @Test public void knownHeavyFileUsesTheHdrSurface(){assertEquals(0,VodPlaybackPlan.first(true));assertFalse(VodPlaybackPlan.texture(0,true));assertFalse(VodPlaybackPlan.texture(1,true));}
 @Test public void normalPathDoesNotForceACodecList(){assertEquals(0,VodPlaybackPlan.options(0).length);}
 @Test public void noForcedRgb32Conversion(){for(int mode=0;mode<4;mode++)for(String option:VodPlaybackPlan.options(mode))assertFalse(option.contains("RV32"));}
 @Test public void heavyTitleRecognition(){for(String s:new String[]{"Film [4K]","Film UHD","Film 2160p","Film [DOLBY VISION]","Film [DV]","Film HDR10"})assertTrue(VodPlaybackPlan.heavyTitle(s));}
 @Test public void normalTitlesAreNotHeavyJustBecauseOfLetters(){for(String s:new String[]{"Advice","Adventure 2026 FHD","DVD edition","Full HD","HD"})assertFalse(VodPlaybackPlan.heavyTitle(s));}
 @Test public void heavyOutputAttemptsAreBoundedAndEndWithSoftware(){int mode=VodPlaybackPlan.first(true),attempts=0;while(mode>=0){assertTrue(++attempts<=4);mode=VodPlaybackPlan.next(mode,true);}assertEquals(4,attempts);assertEquals(3,VodPlaybackPlan.next(2,true));}
 @Test public void heavyRetriesUseHardwareThenSoftwareFallback(){String direct=String.join(" ",VodPlaybackPlan.options(1));String copy=String.join(" ",VodPlaybackPlan.options(2));assertTrue(direct.contains("mediacodec_jni"));assertTrue(direct.contains("mediacodec-dr"));assertTrue(copy.contains("mediacodec_jni"));assertTrue(copy.contains("no-mediacodec-dr"));String software=String.join(" ",VodPlaybackPlan.options(3));assertTrue(software.contains(":codec=avcodec"));assertTrue(software.contains(":avcodec-hw=none"));assertFalse(direct.contains(",all"));assertFalse(copy.contains(",all"));}
 @Test public void shortNoImageObservation(){VodFrameHealth h=new VodFrameHealth(6000,6000);for(int s=0;s<6;s++)assertEquals(VodFrameHealth.OK,h.sample(s*1000,true,false,false,0,0,s*1000,24));assertEquals(VodFrameHealth.NO_IMAGE,h.sample(6000,true,false,false,0,0,6000,24));}
 @Test public void bufferingDoesNotTriggerShortDecoderTimeout(){VodFrameHealth h=new VodFrameHealth(6000,6000);for(int s=0;s<60;s++)assertEquals(VodFrameHealth.OK,h.sample(s*1000,true,true,false,0,0,s*1000,24));}
 @Test public void pauseDoesNotTriggerShortDecoderTimeout(){VodFrameHealth h=new VodFrameHealth(6000,6000);for(int s=0;s<60;s++)assertEquals(VodFrameHealth.OK,h.sample(s*1000,false,false,false,0,0,s*1000,24));}
 @Test public void shortIntervalStillLeavesHealthyFramesAlone(){VodFrameHealth h=new VodFrameHealth(6000,6000);for(int s=0;s<60;s++)assertEquals(VodFrameHealth.OK,h.sample(s*1000,true,false,false,s*24,0,s*1000,24));}
 @Test(expected=IllegalArgumentException.class) public void zeroTimeoutCannotCauseRetryStorm(){new VodFrameHealth(0,0);}
 @Test public void verySlowOutputDoesNotWaitForTwoEightSecondWindows(){VodFrameHealth h=new VodFrameHealth(6000,6000,4000);for(int s=0;s<8;s++)assertEquals(VodFrameHealth.OK,h.sample(s*1000,true,false,false,s+1,s*50,s*1000,24));assertEquals(VodFrameHealth.TOO_SLOW,h.sample(8000,true,false,false,9,400,8000,24));}
}
