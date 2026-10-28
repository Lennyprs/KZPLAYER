package com.kzplayer.app;
/** PCM channel order: FL,FR,FC,LFE,back/side. No codec or encoded audio conversion here. */
public final class VodStereoMatrix {
 private VodStereoMatrix(){}
 public static float[] coefficients(int channels){
  if(channels<1||channels>8)throw new IllegalArgumentException("Unsupported channel count");
  if(channels==1)return new float[]{1};
  if(channels==2)return new float[]{1,0,0,1};
  float[] c=new float[channels*2];c[0]=1;c[3]=1;
  if(channels==4){c[4]=0.70710677f;c[7]=0.70710677f;}
  else {
   c[4]=c[5]=0.70710677f; // center dialog to BOTH speakers
   if(channels==5){c[6]=0.70710677f;c[9]=0.70710677f;}
   else if(channels>=6){
    c[6]=c[7]=0.25f; // low-frequency channel retained at reduced gain
    if(channels==7){c[8]=c[9]=0.5f;c[10]=0.70710677f;c[13]=0.70710677f;}
    else {c[8]=0.70710677f;c[11]=0.70710677f;if(channels==8){c[12]=0.70710677f;c[15]=0.70710677f;}}
   }
  }
  // Common normalization preserves left/right balance and avoids full-scale clipping.
  float left=0,right=0;for(int i=0;i<channels;i++){left+=c[2*i];right+=c[2*i+1];}
  float gain=1f/Math.max(left,right);for(int i=0;i<c.length;i++)c[i]*=gain;
  return c;
 }
}
