package com.kzplayer.app;
/** Video progress, not an audio clock or the existence of a surface. No URLs or Android dependencies. */
public final class VodFrameHealth {
 public static final int OK=0,NO_IMAGE=1,FROZEN=2,TOO_SLOW=3;
 private long start=-1,lastFrameAt,windowAt,lastPosition=-1,lastFrames=-1,windowFrames,windowLost;
 private int slowWindows;
 private final long noImageMs,frozenMs,windowMs;
 public VodFrameHealth(){this(18000,10000);}
 public VodFrameHealth(long noImageMs,long frozenMs){this(noImageMs,frozenMs,8000);}
 public VodFrameHealth(long noImageMs,long frozenMs,long windowMs){
  if(noImageMs<1000||frozenMs<1000||windowMs<1000)throw new IllegalArgumentException("Observation interval too short");
  this.noImageMs=noImageMs;this.frozenMs=frozenMs;this.windowMs=windowMs;
 }
 public void reset(){start=-1;lastFrames=-1;lastPosition=-1;slowWindows=0;}
 public int sample(long now,boolean playing,boolean buffering,boolean seeking,long frames,long lost,long position,float expectedFps){
  if(!playing||buffering||seeking||frames<0){reset();return OK;}
  if(start<0||frames<lastFrames||(lastPosition>=0&&Math.abs(position-lastPosition)>5000)){
   start=lastFrameAt=windowAt=now;lastFrames=windowFrames=frames;windowLost=lost;lastPosition=position;slowWindows=0;return OK;
  }
  lastPosition=position;
  if(frames>lastFrames){lastFrameAt=now;lastFrames=frames;}
  if(frames==0&&now-start>=noImageMs)return NO_IMAGE;
  if(frames>0&&now-lastFrameAt>=frozenMs)return FROZEN;
  if(now-windowAt>=windowMs){
   long shown=frames-windowFrames,dropped=Math.max(0,lost-windowLost);
   float fps=shown*1000f/Math.max(1,now-windowAt);
   boolean slow=expectedFps>=15&&expectedFps<=120&&fps<expectedFps*0.45f&&dropped>shown;
   slowWindows=slow?slowWindows+1:0;
   windowAt=now;windowFrames=frames;windowLost=lost;
   if(slowWindows>=2)return TOO_SLOW;
  }
  return OK;
 }
}
