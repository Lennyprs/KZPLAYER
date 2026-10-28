package com.kzplayer.app;
import org.junit.Test;import static org.junit.Assert.*;
public class Media3VodPolicyTest {
 private int rank(int fw,int fh,int w,int h,String mime,boolean supported,boolean drm){return Media3VodPolicy.lowerTrackRank(fw,fh,w,h,mime,supported,drm);}
 @Test public void realFhdAvcAccepted(){assertTrue(rank(3840,2160,1920,1080,"video/avc",true,false)<Integer.MAX_VALUE);}
 @Test public void realHdHevcAccepted(){assertTrue(rank(3840,2160,1280,720,"video/hevc",true,false)<Integer.MAX_VALUE);}
 @Test public void equalSizeNotAReduction(){assertEquals(Integer.MAX_VALUE,rank(1920,1080,1920,1080,"video/avc",true,false));}
 @Test public void largerTrackRejected(){assertEquals(Integer.MAX_VALUE,rank(1280,720,1920,1080,"video/avc",true,false));}
 @Test public void unsupportedTrackRejected(){assertEquals(Integer.MAX_VALUE,rank(3840,2160,1920,1080,"video/avc",false,false));}
 @Test public void drmTrackNotDowngraded(){assertEquals(Integer.MAX_VALUE,rank(3840,2160,1920,1080,"video/avc",true,true));}
 @Test public void unknownFailedGeometryNotInvented(){assertEquals(Integer.MAX_VALUE,rank(-1,-1,1920,1080,"video/avc",true,false));}
 @Test public void unknownAlternateGeometryRejected(){assertEquals(Integer.MAX_VALUE,rank(3840,2160,-1,1080,"video/avc",true,false));}
 @Test public void zeroGeometryRejected(){assertEquals(Integer.MAX_VALUE,rank(3840,2160,1920,0,"video/avc",true,false));}
 @Test public void noFabricatedDolbyConversion(){assertEquals(Integer.MAX_VALUE,rank(3840,2160,1920,1080,"video/dolby-vision",true,false));}
 @Test public void noAudioOnlyAlternative(){assertEquals(Integer.MAX_VALUE,rank(3840,2160,1920,1080,"audio/aac",true,false));}
 @Test public void nullMimeRejected(){assertEquals(Integer.MAX_VALUE,rank(3840,2160,1920,1080,null,true,false));}
 @Test public void realLargerAvcPreferredWithinFhd(){assertTrue(rank(3840,2160,1920,1080,"video/avc",true,false)<rank(3840,2160,1280,720,"video/avc",true,false));}
 @Test public void avcPreferredForCompatibility(){assertTrue(rank(3840,2160,1280,720,"video/avc",true,false)<rank(3840,2160,1920,1080,"video/hevc",true,false));}
 @Test public void oversizedTrackRejected(){assertEquals(Integer.MAX_VALUE,rank(7680,4320,3840,2160,"video/hevc",true,false));}
 @Test public void pixelComparisonDoesNotOverflow(){assertTrue(rank(Integer.MAX_VALUE,Integer.MAX_VALUE,1920,1080,"video/avc",true,false)<Integer.MAX_VALUE);}
 @Test public void runtimeVideoErrorEligible(){assertTrue(Media3VodPolicy.decodeFailure("ERROR_CODE_DECODING_FAILED"));}
 @Test public void initVideoErrorEligible(){assertTrue(Media3VodPolicy.decodeFailure("ERROR_CODE_DECODER_INIT_FAILED"));}
 @Test public void unsupportedVideoErrorEligible(){assertTrue(Media3VodPolicy.decodeFailure("ERROR_CODE_DECODING_FORMAT_UNSUPPORTED"));}
 @Test public void capabilityVideoErrorEligible(){assertTrue(Media3VodPolicy.decodeFailure("ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES"));}
 @Test public void networkNotCodecFailure(){assertFalse(Media3VodPolicy.decodeFailure("ERROR_CODE_IO_BAD_HTTP_STATUS"));}
 @Test public void audioNotCodecFailure(){assertFalse(Media3VodPolicy.decodeFailure("ERROR_CODE_AUDIO_TRACK_INIT_FAILED"));}
 @Test public void nullErrorNotCodecFailure(){assertFalse(Media3VodPolicy.decodeFailure(null));}
}
