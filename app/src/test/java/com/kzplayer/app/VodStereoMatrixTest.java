package com.kzplayer.app;
import org.junit.Test;import static org.junit.Assert.*;
public class VodStereoMatrixTest {
 private static void near(float expected,float actual){assertTrue(Math.abs(expected-actual)<0.00001f);}
 private static float sample(int count,int channel,int out){return VodStereoMatrix.coefficients(count)[2*channel+out];}
 @Test public void monoPreserved(){float[] c=VodStereoMatrix.coefficients(1);assertEquals(1,c.length);near(1,c[0]);}
 @Test public void stereoUnchanged(){float[] c=VodStereoMatrix.coefficients(2);near(1,c[0]);near(0,c[1]);near(0,c[2]);near(1,c[3]);}
 @Test public void allSupportedCountsHaveCorrectSize(){for(int n=3;n<=8;n++)assertEquals(2*n,VodStereoMatrix.coefficients(n).length);}
 @Test public void coefficientsNonnegativeAndFinite(){for(int n=1;n<=8;n++)for(float c:VodStereoMatrix.coefficients(n))assertTrue(Float.isFinite(c)&&c>=0&&c<=1);}
 @Test public void noFullScaleClipping(){for(int n=3;n<=8;n++){float[] c=VodStereoMatrix.coefficients(n);float l=0,r=0;for(int i=0;i<n;i++){l+=c[2*i];r+=c[2*i+1];}assertTrue(l<=1.00001&&r<=1.00001);}}
 @Test public void symmetricGain(){for(int n=3;n<=8;n++){float[] c=VodStereoMatrix.coefficients(n);float l=0,r=0;for(int i=0;i<n;i++){l+=c[2*i];r+=c[2*i+1];}near(l,r);}}
 @Test public void threeChannelDialogPreserved(){assertTrue(sample(3,2,0)>0);near(sample(3,2,0),sample(3,2,1));}
 @Test public void quadNoInventedCenter(){near(0,sample(4,2,1));near(0,sample(4,3,0));assertTrue(sample(4,2,0)>0&&sample(4,3,1)>0);}
 @Test public void fiveChannelDialogPreserved(){assertTrue(sample(5,2,0)>0&&sample(5,2,1)>0);}
 @Test public void surround51DialogPreserved(){assertTrue(sample(6,2,0)>0);near(sample(6,2,0),sample(6,2,1));}
 @Test public void surround61DialogPreserved(){assertTrue(sample(7,2,0)>0);near(sample(7,2,0),sample(7,2,1));}
 @Test public void surround71DialogPreserved(){assertTrue(sample(8,2,0)>0);near(sample(8,2,0),sample(8,2,1));}
 @Test public void lfeRetainedAtLowerGain(){for(int n=6;n<=8;n++){assertTrue(sample(n,3,0)>0);assertTrue(sample(n,3,0)<sample(n,2,0));}}
 @Test public void rearCenterInBothSpeakers(){assertTrue(sample(7,4,0)>0);near(sample(7,4,0),sample(7,4,1));}
 @Test public void every51ChannelAudible(){for(int i=0;i<6;i++)assertTrue(sample(6,i,0)>0||sample(6,i,1)>0);}
 @Test public void every71ChannelAudible(){for(int i=0;i<8;i++)assertTrue(sample(8,i,0)>0||sample(8,i,1)>0);}
 @Test public void frontLeftDoesNotLeakRight(){for(int n=3;n<=8;n++)near(0,sample(n,0,1));}
 @Test public void frontRightDoesNotLeakLeft(){for(int n=3;n<=8;n++)near(0,sample(n,1,0));}
 @Test(expected=IllegalArgumentException.class) public void zeroChannelsRejected(){VodStereoMatrix.coefficients(0);}
 @Test(expected=IllegalArgumentException.class) public void unknownLayoutRejected(){VodStereoMatrix.coefficients(9);}
}
