package com.kzplayer.app;
import android.app.Activity;
import android.view.WindowManager;
import androidx.appcompat.app.AlertDialog;
/** Owned error window: Android may already have detached it when a queued button closes it. */
public final class PlaybackErrorDialog extends AlertDialog {
 private final Activity owner;
 public PlaybackErrorDialog(Activity activity){super(activity);owner=activity;}
 @Override public void show(){
  if(owner.isFinishing()||owner.isDestroyed())return;
  try{super.show();}catch(WindowManager.BadTokenException ignored){}
 }
 @Override public void dismiss(){
  try{super.dismiss();}catch(IllegalArgumentException alreadyDetached){}
 }
}
