package com.kzplayer.app;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
/** App-private, bounded, last-good category snapshots. No plaintext owner identity in filenames. */
public final class CategorySnapshotStore {
 private static final int MAGIC=0x4b5a4337,MAX=524288;
 private static final long TTL=86400000L;
 private final File dir;
 public CategorySnapshotStore(File directory){dir=directory;}
 private static byte[] hash(byte[] data){try{return MessageDigest.getInstance("SHA-256").digest(data);}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
 public static String key(String owner,String kind){byte[] b=hash((owner+"\u0000"+kind).getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte n:b)s.append(String.format(Locale.ROOT,"%02x",n&255));return s.toString();}
 private File path(String key){if(key==null||!key.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid snapshot key");return new File(dir,key+".cat");}
 public String read(String key,long now){synchronized(CategorySnapshotStore.class){
  File file=path(key);if(!file.isFile()||file.length()>MAX+48L)return null;
  try(DataInputStream in=new DataInputStream(new FileInputStream(file))){
   if(in.readInt()!=MAGIC)return null;long at=in.readLong();int size=in.readInt();
   if(at<=0||now<at||now-at>TTL||size<=0||size>MAX)return null;
   byte[] digest=new byte[32];in.readFully(digest);byte[] bytes=new byte[size];in.readFully(bytes);
   if(in.read()!=-1||!MessageDigest.isEqual(digest,hash(bytes)))return null;
   return new String(bytes,StandardCharsets.UTF_8);
  }catch(IOException e){return null;}
 }}
 public boolean fresh(String key,long now){synchronized(CategorySnapshotStore.class){
  try(DataInputStream in=new DataInputStream(new FileInputStream(path(key)))){if(in.readInt()!=MAGIC)return false;long at=in.readLong();return at>0&&now>=at&&now-at<300000L;}catch(IOException e){return false;}
 }}
 public boolean write(String key,String json,long now){synchronized(CategorySnapshotStore.class){
  File target=path(key);if(json==null||json.trim().isEmpty()||json.trim().equals("[]")||now<=0)return false;
  byte[] bytes=json.getBytes(StandardCharsets.UTF_8);if(bytes.length>MAX)return false;
  if(!dir.isDirectory()&&!dir.mkdirs())return false;
  File temp=new File(dir,key+"."+UUID.randomUUID()+".tmp");
  try{
   try(FileOutputStream stream=new FileOutputStream(temp);DataOutputStream out=new DataOutputStream(stream)){
    out.writeInt(MAGIC);out.writeLong(now);out.writeInt(bytes.length);out.write(hash(bytes));out.write(bytes);out.flush();stream.getFD().sync();
   }
   if(!temp.renameTo(target))return false;
   File[] files=dir.listFiles((d,n)->n.matches("[0-9a-f]{64}\\.cat"));
   if(files!=null&&files.length>64){Arrays.sort(files,(a,b)->a.lastModified()<b.lastModified()?-1:a.lastModified()>b.lastModified()?1:0);for(int i=0;i<files.length-64;i++)if(!files[i].equals(target))files[i].delete();}
   return true;
  }catch(IOException e){return false;}finally{temp.delete();}
 }}
}
