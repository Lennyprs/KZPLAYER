package com.kzplayer.app;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Compact title snapshots + private binary records. No SQLite, FTS, triggers or per-row SQL. */
public final class SearchCatalogStore implements AutoCloseable {
    public static final int PER_LIST_LIMIT=350;
    private static final int MAGIC=0x4b5a5334, MAX_PAYLOAD=4*1024*1024;
    private final File root;
    private final ConcurrentHashMap<String,Source> sources=new ConcurrentHashMap<>();
    public final AtomicLong revision=new AtomicLong();
    private volatile long invalidated;
    private static final class Snapshot {
        File file;
        final ArrayList<String> titles=new ArrayList<>();
        final ArrayList<Long> offsets=new ArrayList<>();
        Snapshot(File file){this.file=file;}
    }
    private static final class Writer {
        final String stamp;final Snapshot snapshot;final FileOutputStream stream;final DataOutputStream out;
        final HashMap<String,Integer> ids=new HashMap<>();
        Writer(File file,String stamp)throws IOException{this.stamp=stamp;snapshot=new Snapshot(file);stream=new FileOutputStream(file);out=new DataOutputStream(new BufferedOutputStream(stream,65536));}
    }
    private static final class Mark {long at;boolean limited;Mark(long at,boolean limited){this.at=at;this.limited=limited;}}
    private static final class Source {
        final String key;Snapshot saved;Writer working;long updated;boolean loaded;
        final LinkedHashMap<String,Mark> queries=new LinkedHashMap<>();
        Source(String key){this.key=key;}
    }
    public static final class Row {
        public final String identity,title,payload;
        public Row(String identity,String title,String payload){this.identity=identity;this.title=title;this.payload=payload;}
    }
    public SearchCatalogStore(Context app){this(new File(app.getCacheDir(),"search-v124"));}
    public SearchCatalogStore(File directory){root=directory;if(!root.isDirectory()&&!root.mkdirs())throw new IllegalStateException("CACHE_DOSSIER");
        try(DataInputStream in=new DataInputStream(new Manifest(new File(root,"invalidate")).openRead())){invalidated=in.readLong();}catch(IOException ignored){}
        // Recover interrupted atomic manifest writes before collecting orphaned cache files.
        File[] files=root.listFiles();if(files!=null)for(File f:files)if(f.getName().endsWith(".bak")){
            File base=new File(root,f.getName().substring(0,f.getName().length()-4));base.delete();f.renameTo(base);
        }
        HashSet<String> live=new HashSet<>();files=root.listFiles();
        if(files!=null)for(File f:files)if(f.getName().endsWith(".idx"))try(DataInputStream in=new DataInputStream(new FileInputStream(f))){if(in.readInt()==MAGIC){in.readLong();live.add(in.readUTF());}}catch(IOException ignored){}
        if(files!=null)for(File f:files)if(f.getName().endsWith(".part")||(f.getName().endsWith(".data")&&!live.contains(f.getName())))f.delete();
    }
    /** Same backup/replace/fsync protocol on Android and the JVM; no mocked database/file API. */
    private static final class Manifest {
        final File base,backup;
        Manifest(File file){base=file;backup=new File(file.getPath()+".bak");}
        FileInputStream openRead()throws IOException{if(backup.exists()){base.delete();if(!backup.renameTo(base))throw new IOException();}return new FileInputStream(base);}
        FileOutputStream startWrite()throws IOException{
            if(base.exists()){if(!backup.exists()){if(!base.renameTo(backup))throw new IOException();}else if(!base.delete())throw new IOException();}
            return new FileOutputStream(base);
        }
        void finishWrite(FileOutputStream out)throws IOException{out.getFD().sync();out.close();backup.delete();}
        void failWrite(FileOutputStream out){try{out.close();}catch(IOException ignored){}base.delete();backup.renameTo(base);}
    }
    public void close(){for(Source s:sources.values())synchronized(s){abort(s);}sources.clear();}
    private File index(Source s){return new File(root,s.key+".idx");}
    private Source source(String key){
        if(!key.matches("[A-Za-z0-9:_-]{1,180}"))throw new IllegalArgumentException("CACHE_CLE");
        Source s=sources.computeIfAbsent(key,Source::new);
        synchronized(s){if(!s.loaded){load(s);s.loaded=true;}}return s;
    }
    private static void checkMemory(){Runtime rt=Runtime.getRuntime();if(rt.maxMemory()-(rt.totalMemory()-rt.freeMemory())<4L*1024*1024){System.gc();if(rt.maxMemory()-(rt.totalMemory()-rt.freeMemory())<4L*1024*1024)throw new IllegalStateException("CACHE_MEMOIRE");}}
    private void load(Source s){
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(new Manifest(index(s)).openRead(),65536))){
            if(in.readInt()!=MAGIC)throw new IOException();s.updated=in.readLong();String name=in.readUTF();
            if(name.contains("/")||name.contains("\\")||!name.endsWith(".data"))throw new IOException();
            Snapshot snap=new Snapshot(new File(root,name));if(!snap.file.isFile())throw new IOException();
            int count=in.readInt();if(count<0||count>1000000)throw new IOException();long length=snap.file.length();
            for(int n=0;n<count;n++){String title=in.readUTF();long offset=in.readLong();if(offset<0||offset>=length)throw new IOException();snap.titles.add(title);snap.offsets.add(offset);if((n&4095)==0)checkMemory();}
            int q=in.readInt();if(q<0||q>64)throw new IOException();for(int n=0;n<q;n++)s.queries.put(in.readUTF(),new Mark(in.readLong(),in.readBoolean()));
            s.saved=snap;
        }catch(IOException e){s.saved=null;s.updated=0;s.queries.clear();}
    }
    private void publish(Source s)throws IOException{
        Manifest atomic=new Manifest(index(s));FileOutputStream stream=null;
        try{
            stream=atomic.startWrite();DataOutputStream out=new DataOutputStream(new BufferedOutputStream(stream,65536));
            out.writeInt(MAGIC);out.writeLong(s.updated);out.writeUTF(s.saved.file.getName());out.writeInt(s.saved.titles.size());
            for(int i=0;i<s.saved.titles.size();i++){out.writeUTF(s.saved.titles.get(i));out.writeLong(s.saved.offsets.get(i));}
            while(s.queries.size()>64)s.queries.remove(s.queries.keySet().iterator().next());
            out.writeInt(s.queries.size());for(Map.Entry<String,Mark> e:s.queries.entrySet()){out.writeUTF(e.getKey());out.writeLong(e.getValue().at);out.writeBoolean(e.getValue().limited);}
            out.flush();atomic.finishWrite(stream);
        }catch(IOException e){if(stream!=null)atomic.failWrite(stream);throw e;}
    }
    private void budget(){long size=0;File[] files=root.listFiles();if(files!=null)for(File f:files)size+=f.length();if(size>512L*1024*1024||root.getUsableSpace()<8L*1024*1024)throw new IllegalStateException("CACHE_ESPACE");checkMemory();}
    private static String boundedTitle(String title){String f=SearchText.fold(title);return f.length()>4096?f.substring(0,4096):f;}
    private void write(Writer w,String id,String title,byte[] payload)throws IOException{
        if(payload.length>MAX_PAYLOAD)throw new IOException("CACHE_ENTREE");
        long offset=w.out.size();w.out.writeUTF(id);w.out.writeUTF(title);w.out.writeInt(payload.length);w.out.write(payload);
        Integer previous=w.ids.put(id,w.snapshot.titles.size());
        if(previous==null){w.snapshot.titles.add(title);w.snapshot.offsets.add(offset);}
        else{w.ids.put(id,previous);w.snapshot.titles.set(previous,title);w.snapshot.offsets.set(previous,offset);}
    }
    private Writer writer(Source s,String stamp)throws IOException{
        if(s.working!=null&&s.working.stamp.equals(stamp))return s.working;
        if(s.working!=null){s.working.out.close();s.working.snapshot.file.delete();s.working=null;}
        Writer w=new Writer(new File(root,s.key+"-"+UUID.randomUUID()+".part"),stamp);s.working=w;
        // Native search results are incremental across queries, not a complete replacement catalog.
        if("native".equals(stamp)&&s.saved!=null){
            try(RandomAccessFile in=new RandomAccessFile(s.saved.file,"r")){
                for(long offset:s.saved.offsets){in.seek(offset);String id=in.readUTF();String title=in.readUTF();int len=in.readInt();if(len<0||len>MAX_PAYLOAD)throw new IOException();byte[] payload=new byte[len];in.readFully(payload);write(w,id,title,payload);}
            }
        }
        return w;
    }
    public long updated(String key){Source s=source(key);synchronized(s){return s.saved==null||!s.saved.file.isFile()||s.updated<=invalidated?0:s.updated;}}
    public int queryState(String key,String query,long since){Source s=source(key);synchronized(s){Mark mark=s.queries.get(SearchText.fold(query));return mark!=null&&mark.at>=since&&mark.at>invalidated?(mark.limited?1:0):-1;}}
    public void put(String key,String stamp,List<Row> rows){Source s=source(key);synchronized(s){
        try{budget();Writer w=writer(s,stamp);for(Row row:rows)write(w,row.identity,boundedTitle(row.title),row.payload.getBytes(StandardCharsets.UTF_8));w.out.flush();revision.incrementAndGet();}
        catch(IOException e){abort(s);throw new IllegalStateException("CACHE_ECRITURE");}
    }}
    private void abort(Source s){if(s.working!=null){try{s.working.out.close();}catch(IOException ignored){}s.working.snapshot.file.delete();s.working=null;}}
    public void complete(String key,String stamp){Source s=source(key);synchronized(s){
        Snapshot old=s.saved;long oldTime=s.updated;Writer w=null;
        try{
            w=writer(s,stamp);w.out.flush();w.stream.getFD().sync();w.out.close();File data=new File(root,w.snapshot.file.getName().replace(".part",".data"));
            if(!w.snapshot.file.renameTo(data))throw new IOException();w.snapshot.file=data;
            s.saved=w.snapshot;s.updated=System.currentTimeMillis();publish(s);s.working=null;w.ids.clear();
            if(old!=null&&!old.file.equals(data))old.file.delete();revision.incrementAndGet();
        }catch(IOException e){s.saved=old;s.updated=oldTime;abort(s);throw new IllegalStateException("CACHE_PUBLICATION");}
    }}
    public void completeQuery(String key,String query,boolean limited){Source s=source(key);synchronized(s){
        String folded=SearchText.fold(query);Mark previous=s.queries.put(folded,new Mark(System.currentTimeMillis(),limited));
        try{complete(key,"native");}catch(RuntimeException e){if(previous==null)s.queries.remove(folded);else s.queries.put(folded,previous);throw e;}
    }}
    private void collect(Snapshot snapshot,String[] tokens,String folded,LinkedHashMap<String,String> exact,LinkedHashMap<String,String> rest)throws IOException{
        if(snapshot==null)return;if(!snapshot.file.isFile())throw new IOException();
        ArrayList<Integer> hits=new ArrayList<>(),exactHits=new ArrayList<>();
        for(int i=0;i<snapshot.titles.size();i++){
            String title=snapshot.titles.get(i);boolean matches=true;for(String token:tokens)if(!title.contains(token)){matches=false;break;}
            if(!matches)continue;if(title.equals(folded)){if(exactHits.size()<=PER_LIST_LIMIT)exactHits.add(i);}else if(hits.size()<=PER_LIST_LIMIT)hits.add(i);
        }
        exactHits.addAll(hits);
        try(RandomAccessFile in=new RandomAccessFile(snapshot.file,"r")){
            for(int i:exactHits){in.seek(snapshot.offsets.get(i));String id=in.readUTF();String title=in.readUTF();int len=in.readInt();if(len<0||len>MAX_PAYLOAD)throw new IOException();
                if(exact.containsKey(id)||rest.containsKey(id)){continue;}byte[] bytes=new byte[len];in.readFully(bytes);
                if(title.equals(folded))exact.put(id,new String(bytes,StandardCharsets.UTF_8));else rest.put(id,new String(bytes,StandardCharsets.UTF_8));
                if(exact.size()>PER_LIST_LIMIT)break;
            }
        }
    }
    public List<String> find(String key,String query){Source s=source(key);synchronized(s){
        String[] tokens=SearchText.tokens(query);if(tokens.length==0)return Collections.emptyList();
        LinkedHashMap<String,String> exact=new LinkedHashMap<>(),rest=new LinkedHashMap<>();
        try{if(s.working!=null)collect(s.working.snapshot,tokens,SearchText.fold(query),exact,rest);collect(s.saved,tokens,SearchText.fold(query),exact,rest);}
        catch(IOException e){abort(s);s.saved=null;s.updated=0;s.queries.clear();index(s).delete();revision.incrementAndGet();return Collections.emptyList();}
        ArrayList<String> out=new ArrayList<>(exact.values());for(String value:rest.values()){if(out.size()>PER_LIST_LIMIT)break;out.add(value);}return out.size()>PER_LIST_LIMIT+1?out.subList(0,PER_LIST_LIMIT+1):out;
    }}
    public void invalidate(){invalidated=System.currentTimeMillis();try{Manifest f=new Manifest(new File(root,"invalidate"));FileOutputStream stream=f.startWrite();DataOutputStream out=new DataOutputStream(stream);out.writeLong(invalidated);out.flush();f.finishWrite(stream);}catch(IOException e){throw new IllegalStateException("CACHE_INVALIDATION");}revision.incrementAndGet();}
    public void removeInactive(String[] active){HashSet<String> keys=new HashSet<>(Arrays.asList(active));for(String key:new ArrayList<>(sources.keySet()))if(!keys.contains(key)){Source s=sources.remove(key);if(s!=null)synchronized(s){abort(s);if(s.saved!=null)s.saved.file.delete();index(s).delete();}}}
}
