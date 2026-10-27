package com.kzplayer.app;
import org.junit.Test;
import static org.junit.Assert.*;
public class V362DecoderPolicyTest {
 @Test public void fullySupportedHardwareComesFirst(){assertEquals(0,VodVideoPolicy.decoderRank(true,true,true));}
 @Test public void functionalHardwarePrecedesSoftwarePerformanceClaims(){assertTrue(VodVideoPolicy.decoderRank(true,false,true)<VodVideoPolicy.decoderRank(false,true,true));}
 @Test public void fullySupportedHardwarePrecedesUnderreportedHardware(){assertTrue(VodVideoPolicy.decoderRank(true,true,true)<VodVideoPolicy.decoderRank(true,false,true));}
 @Test public void unsupportedHardwareNotForcedInitially(){assertTrue(VodVideoPolicy.decoderRank(false,true,true)<VodVideoPolicy.decoderRank(true,false,false));}
 @Test public void identifiesAndroidSoftwareDecoder(){assertTrue(VodVideoPolicy.platformSoftware("c2.android.hevc.decoder"));}
 @Test public void identifiesLegacyGoogleSoftwareDecoder(){assertTrue(VodVideoPolicy.platformSoftware("OMX.google.hevc.decoder"));}
 @Test public void doesNotClassifyVendorNameAsSoftware(){assertFalse(VodVideoPolicy.platformSoftware("OMX.vendor.video.decoder.hevc"));}
 @Test public void boundedProbeAllowedAfterAndroidSoftwareFailure(){assertTrue(VodVideoPolicy.hardwareProbe("c2.android.hevc.decoder","video/dolby-vision","video/hevc","video/hevc"));}
 @Test public void noInventedDolbyBaseLayer(){assertFalse(VodVideoPolicy.hardwareProbe("c2.android.hevc.decoder","video/dolby-vision",null,"video/hevc"));}
 @Test public void noProbeForMismatchedBase(){assertFalse(VodVideoPolicy.hardwareProbe("c2.android.hevc.decoder","video/dolby-vision","video/hevc","video/avc"));}
 @Test public void noUnderreportedProbeAfterHardwareFailure(){assertFalse(VodVideoPolicy.hardwareProbe("OMX.vendor.hevc","video/dolby-vision","video/hevc","video/hevc"));}
 @Test public void plain4kHevcCanProbeSameCodecHardware(){assertTrue(VodVideoPolicy.hardwareProbe("c2.android.hevc.decoder","video/hevc",null,"video/hevc"));}
}
