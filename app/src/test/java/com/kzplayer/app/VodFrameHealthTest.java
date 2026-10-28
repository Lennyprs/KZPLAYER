package com.kzplayer.app;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Arrays;
public class VodFrameHealthTest {
 private final VodFrameHealth health=new VodFrameHealth();
 private int sample(long second,long frames,long lost){return health.sample(second*1000,true,false,false,frames,lost,second*1000,24f);}
 @Test public void audioClockIsNotProofOfVideo(){for(int s=0;s<18;s++)assertEquals(VodFrameHealth.OK,sample(s,0,0));assertEquals(VodFrameHealth.NO_IMAGE,sample(18,0,0));}
 @Test public void singleFrameDoesNotCountAsAHealthyFilm(){for(int s=0;s<10;s++)assertEquals(VodFrameHealth.OK,sample(s,1,0));assertEquals(VodFrameHealth.FROZEN,sample(10,1,0));}
 @Test public void continuousImagesAreLeftAlone(){for(int s=0;s<120;s++)assertEquals(VodFrameHealth.OK,sample(s,s*24,0));}
 @Test public void pausedFilmIsNeverRelaunched(){for(int s=0;s<60;s++)assertEquals(VodFrameHealth.OK,health.sample(s*1000,false,false,false,1,0,1000,24));}
 @Test public void bufferingIsNotAConfirmedDecoderFreeze(){for(int s=0;s<60;s++)assertEquals(VodFrameHealth.OK,health.sample(s*1000,true,true,false,1,0,1000,24));}
 @Test public void seeksHaveTheirOwnGrace(){for(int s=0;s<60;s++)assertEquals(VodFrameHealth.OK,health.sample(s*1000,true,false,true,1,0,s*10000,24));}
 @Test public void missingCountersNeverInventAFreeze(){for(int s=0;s<60;s++)assertEquals(VodFrameHealth.OK,sample(s,-1,0));}
 @Test public void counterResetIsANewDecoderNotAFailure(){sample(0,100,0);assertEquals(VodFrameHealth.OK,sample(1,0,0));assertEquals(VodFrameHealth.OK,sample(2,24,0));}
 @Test public void backwardsSeekDoesNotCauseImmediateFailure(){sample(0,1,0);sample(9,1,0);assertEquals(VodFrameHealth.OK,health.sample(10000,true,false,false,1,0,0,24));}
 @Test public void resumedFilmGetsNewGrace(){sample(0,1,0);health.sample(9000,false,false,false,1,0,0,24);assertEquals(VodFrameHealth.OK,sample(20,1,0));}
 @Test public void slowPlaybackRequiresTwoWindowsAndLostPictures(){for(int s=0;s<16;s++)assertEquals(VodFrameHealth.OK,sample(s,s+1,s*50));assertEquals(VodFrameHealth.TOO_SLOW,sample(16,17,800));}
 @Test public void lowNativeFrameRateIsNotAutomaticallyRejected(){for(int s=0;s<60;s++)assertEquals(VodFrameHealth.OK,health.sample(s*1000,true,false,false,s+1,s*50,s*1000,5));}
 @Test public void fewImagesWithoutDropsAreNotCalledInsufficientCpu(){for(int s=0;s<60;s++)assertEquals(VodFrameHealth.OK,sample(s,s+1,0));}
 @Test public void manualResetStartsANewObservation(){sample(0,1,0);health.reset();assertEquals(VodFrameHealth.OK,sample(100,1,0));}
 @Test public void heavyFilesNeverForceTheSoftwareOnlyPath(){assertEquals(1,VodPlaybackPlan.next(0,true));assertEquals(2,VodPlaybackPlan.next(1,true));assertEquals(-1,VodPlaybackPlan.next(2,true));}
 @Test public void fullHdMayUseOneSoftwareOnlyPath(){assertEquals(3,VodPlaybackPlan.next(2,false));assertEquals(-1,VodPlaybackPlan.next(3,false));}
 @Test public void noRetryCycle(){for(boolean heavy:new boolean[]{false,true}){int n=0;for(int mode=0;mode>=0;mode=VodPlaybackPlan.next(mode,heavy))assertTrue(++n<=4);}}
 @Test public void audioDecoderRemainsAvailableInEveryPath(){for(int mode=0;mode<4;mode++)assertTrue(Arrays.stream(VodPlaybackPlan.options(mode)).anyMatch(s->s.startsWith(":codec=")&&s.contains("avcodec")));}
 @Test public void compatibilityPathChangesRenderingNotTheUrl(){assertTrue(Arrays.asList(VodPlaybackPlan.options(2)).contains(":no-mediacodec-dr"));}
 @Test public void noNetworkOrContainerRewritingInCodecOptions(){for(int mode=0;mode<4;mode++)for(String s:VodPlaybackPlan.options(mode)){assertFalse(s.contains("http"));assertFalse(s.contains("transcode"));assertFalse(s.contains("scale"));}}
 @Test public void sdHdFullHdAnd4kFailuresAreAllEligible(){for(int width:new int[]{720,1280,1920,3840})assertTrue(VodVideoPolicy.alternateEngine(false,false,false,false,true,"ERROR_CODE_DECODING_FAILED","video/avc",width,width/2,false));}
 @Test public void httpFailureIsNotACodecRetry(){assertFalse(VodVideoPolicy.alternateEngine(false,false,false,false,true,"ERROR_CODE_IO_BAD_HTTP_STATUS","video/avc",1920,1080,false));}
 @Test public void healthyPlaybackHasNoAutomaticEngineSwitch(){assertFalse(VodVideoPolicy.alternateEngine(false,false,false,false,true,"", "video/avc",1920,1080,false));}
 @Test public void uhdIsNotSilentlyReplacedByFullHd(){assertFalse(VodVideoPolicy.sameDefinition(3840,2160,1920,1080));}
 @Test public void sameDefinitionBaseTrackIsAllowed(){assertTrue(VodVideoPolicy.sameDefinition(3840,2160,3840,2160));}
}
