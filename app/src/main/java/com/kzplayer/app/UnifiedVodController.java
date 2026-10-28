package com.kzplayer.app;
import android.os.Looper;
import androidx.media3.common.*;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.ArrayList;
import java.util.List;
/** Reuses the actual Media3 PlayerView controls. This class never owns a second decoder or surface. */
public final class UnifiedVodController extends SimpleBasePlayer {
 public interface Backend {
  long position(); long duration(); boolean wantsPlay(); int state(); boolean seekable();
  void playing(boolean value); void seek(long position); float speed(); void speed(float value);
  boolean hasNext(); void next();
 }
 private final Backend backend;
 private final MediaItem item;
 private final Object currentUid=new Object(),nextUid=new Object();
 public UnifiedVodController(Backend backend,String title){
  super(Looper.getMainLooper());this.backend=backend;
  item=new MediaItem.Builder().setMediaId("current-vod").setMediaMetadata(new MediaMetadata.Builder().setTitle(title).build()).build();
 }
 public void refresh(){invalidateState();}
 @Override protected State getState(){
  long length=backend.duration();boolean seekable=length>0&&backend.seekable();
  Player.Commands.Builder commands=new Player.Commands.Builder().addAll(
   Player.COMMAND_PLAY_PAUSE,Player.COMMAND_GET_CURRENT_MEDIA_ITEM,Player.COMMAND_GET_TIMELINE,
   Player.COMMAND_GET_METADATA,Player.COMMAND_SET_SPEED_AND_PITCH,Player.COMMAND_RELEASE);
  if(seekable)commands.addAll(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,Player.COMMAND_SEEK_BACK,
   Player.COMMAND_SEEK_FORWARD,Player.COMMAND_SEEK_TO_PREVIOUS);
  boolean next=backend.hasNext();if(next)commands.add(Player.COMMAND_SEEK_TO_NEXT);
  List<MediaItemData> list=new ArrayList<>();
  list.add(new MediaItemData.Builder(currentUid).setMediaItem(item).setIsSeekable(seekable)
   .setDurationUs(length>0?length*1000:C.TIME_UNSET).build());
  if(next)list.add(new MediaItemData.Builder(nextUid).setMediaItem(new MediaItem.Builder().setMediaId("next-episode").build()).build());
  int state=backend.state();
  return new State.Builder().setAvailableCommands(commands.build()).setPlaylist(list).setCurrentMediaItemIndex(0)
   .setPlayWhenReady(backend.wantsPlay(),Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
   .setPlaybackState(state).setIsLoading(state==Player.STATE_BUFFERING)
   .setContentPositionMs((PositionSupplier)()->Math.max(0,backend.position()))
   .setContentBufferedPositionMs((PositionSupplier)()->Math.max(0,backend.position()))
   .setSeekBackIncrementMs(5000).setSeekForwardIncrementMs(15000)
   .setPlaybackParameters(new PlaybackParameters(backend.speed())).build();
 }
 @Override protected ListenableFuture<?> handleSetPlayWhenReady(boolean value){backend.playing(value);return Futures.immediateVoidFuture();}
 @Override protected ListenableFuture<?> handleSeek(int index,long position,int command){
  if(index>0&&backend.hasNext())backend.next();
  else if(backend.seekable())backend.seek(position==C.TIME_UNSET?0:position);
  return Futures.immediateVoidFuture();
 }
 @Override protected ListenableFuture<?> handleSetPlaybackParameters(PlaybackParameters value){backend.speed(value.speed);return Futures.immediateVoidFuture();}
 @Override protected ListenableFuture<?> handleRelease(){return Futures.immediateVoidFuture();}
}
