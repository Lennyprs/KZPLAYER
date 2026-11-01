package com.kzplayer.app;
import org.junit.Test;import static org.junit.Assert.*;
public class VodPlaybackPolicyTest {
 private final String hw="OMX.allwinner.video.decoder.hevc";
 @Test public void observedVideoUsesAsync(){assertTrue(VodPlaybackPolicy.asynchronousVideo(29,hw,"video/hevc",false));}
 @Test public void api21StaysSynchronous(){assertFalse(VodPlaybackPolicy.asynchronousVideo(21,hw,"video/hevc",false));}
 @Test public void api22StaysSynchronous(){assertFalse(VodPlaybackPolicy.asynchronousVideo(22,hw,"video/hevc",false));}
 @Test public void api23CanUseAsync(){assertTrue(VodPlaybackPolicy.asynchronousVideo(23,hw,"video/hevc",false));}
 @Test public void audioNeverUsesTargetMode(){assertFalse(VodPlaybackPolicy.asynchronousVideo(29,"OMX.google.aac.decoder","audio/mp4a-latm",false));}
 @Test public void softwareNeverForcedAsync(){assertFalse(VodPlaybackPolicy.asynchronousVideo(29,"c2.android.hevc.decoder","video/hevc",false));}
 @Test public void otherVendorsUnchanged(){assertFalse(VodPlaybackPolicy.asynchronousVideo(29,"OMX.MS.HEVC.Decoder","video/hevc",false));}
 @Test public void avcUnchanged(){assertFalse(VodPlaybackPolicy.asynchronousVideo(29,"OMX.allwinner.video.decoder.avc","video/avc",false));}
 @Test public void drmUnchanged(){assertFalse(VodPlaybackPolicy.asynchronousVideo(29,hw,"video/hevc",true));}
 @Test public void nullDecoderSafe(){assertFalse(VodPlaybackPolicy.asynchronousVideo(29,null,"video/hevc",false));}
 @Test public void decoderFailureRecognized(){assertTrue(VodPlaybackPolicy.decoderFailure("ERROR_CODE_DECODING_FAILED",true));}
 @Test public void initFailureRecognized(){assertTrue(VodPlaybackPolicy.decoderFailure("ERROR_CODE_DECODER_INIT_FAILED",true));}
 @Test public void audioFailureExcluded(){assertFalse(VodPlaybackPolicy.decoderFailure("ERROR_CODE_DECODING_FAILED",false));}
 @Test public void parserFailureExcluded(){assertFalse(VodPlaybackPolicy.decoderFailure("ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED",true));}
 @Test public void httpFailureExcluded(){assertFalse(VodPlaybackPolicy.decoderFailure("ERROR_CODE_IO_BAD_HTTP_STATUS",true));}
 @Test public void actualSupported1080Allowed(){assertTrue(VodPlaybackPolicy.compatible1080(true,"video/avc",1920,1080,false));}
 @Test public void actual720Allowed(){assertTrue(VodPlaybackPolicy.compatible1080(true,"video/hevc",1280,720,false));}
 @Test public void fake1080UnsupportedExcluded(){assertFalse(VodPlaybackPolicy.compatible1080(false,"video/avc",1920,1080,false));}
 @Test public void fourKNotRelabeled(){assertFalse(VodPlaybackPolicy.compatible1080(true,"video/hevc",3840,2160,false));}
 @Test public void hdrNotCalledSdr(){assertFalse(VodPlaybackPolicy.compatible1080(true,"video/hevc",1920,1080,true));}
 @Test public void dvNotCalledSdr(){assertFalse(VodPlaybackPolicy.compatible1080(true,"video/dolby-vision",1920,1080,false));}
 @Test public void unknownSizeExcluded(){assertFalse(VodPlaybackPolicy.compatible1080(true,"video/hevc",-1,-1,false));}
 @Test public void audioIsNotVideo(){assertFalse(VodPlaybackPolicy.compatible1080(true,"audio/aac",1920,1080,false));}
 @Test public void portrait1080Supported(){assertTrue(VodPlaybackPolicy.compatible1080(true,"video/avc",1080,1920,false));}
 @Test public void budgetOnlyOnce(){VodPlaybackPolicy.RecoveryBudget b=new VodPlaybackPolicy.RecoveryBudget();assertTrue(b.claim(true,true));assertFalse(b.claim(true,true));}
 @Test public void nonTargetDoesNotConsumeBudget(){VodPlaybackPolicy.RecoveryBudget b=new VodPlaybackPolicy.RecoveryBudget();assertFalse(b.claim(true,false));assertTrue(b.claim(true,true));}
 @Test public void parserDoesNotConsumeBudget(){VodPlaybackPolicy.RecoveryBudget b=new VodPlaybackPolicy.RecoveryBudget();assertFalse(b.claim(false,true));assertTrue(b.claim(true,true));}
 @Test public void persistedBudgetRemainsUsed(){VodPlaybackPolicy.RecoveryBudget b=new VodPlaybackPolicy.RecoveryBudget();b.markUsed();assertFalse(b.claim(true,true));}
 @Test public void nextEpisodeCanReset(){VodPlaybackPolicy.RecoveryBudget b=new VodPlaybackPolicy.RecoveryBudget();assertTrue(b.claim(true,true));b.reset();assertTrue(b.claim(true,true));}
}
