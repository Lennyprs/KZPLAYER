package com.kzplayer.app;
import java.io.*;
import java.nio.file.Files;
import org.junit.Test;
import static org.junit.Assert.*;
public class CategorySnapshotTest {
 private File dir()throws Exception{return Files.createTempDirectory("kz-category-test-").toFile();}
 private String key(){return CategorySnapshotStore.key("synthetic-owner","movie");}
 @Test public void survivesANewStoreInstance()throws Exception{File d=dir();assertTrue(new CategorySnapshotStore(d).write(key(),"[{\"id\":\"1\",\"name\":\"Films\"}]",1000));assertEquals("[{\"id\":\"1\",\"name\":\"Films\"}]",new CategorySnapshotStore(d).read(key(),2000));}
 @Test public void emptyReplyDoesNotEraseCategories()throws Exception{CategorySnapshotStore s=new CategorySnapshotStore(dir());s.write(key(),"[1]",1000);assertFalse(s.write(key(),"[]",2000));assertEquals("[1]",s.read(key(),2000));}
 @Test public void categoriesNeverCrossOwnerOrSection()throws Exception{CategorySnapshotStore s=new CategorySnapshotStore(dir());s.write(key(),"[1]",1000);assertEquals(null,s.read(CategorySnapshotStore.key("synthetic-owner","series"),2000));assertEquals(null,s.read(CategorySnapshotStore.key("another-owner","movie"),2000));}
 @Test public void corruptedPayloadIsNotRendered()throws Exception{File d=dir();CategorySnapshotStore s=new CategorySnapshotStore(d);s.write(key(),"[1]",1000);try(RandomAccessFile f=new RandomAccessFile(new File(d,key()+".cat"),"rw")){f.seek(49);f.write(88);}assertEquals(null,s.read(key(),2000));}
 @Test public void oversizedReplyPreservesTheLastGoodCopy()throws Exception{CategorySnapshotStore s=new CategorySnapshotStore(dir());s.write(key(),"[1]",1000);assertFalse(s.write(key(),"x".repeat(524289),2000));assertEquals("[1]",s.read(key(),2000));}
 @Test public void expiryIsBoundedToOneDay()throws Exception{CategorySnapshotStore s=new CategorySnapshotStore(dir());s.write(key(),"[1]",1000);assertEquals(null,s.read(key(),86401001));}
 @Test public void clockGoingBackDoesNotReviveSnapshots()throws Exception{CategorySnapshotStore s=new CategorySnapshotStore(dir());s.write(key(),"[1]",2000);assertEquals(null,s.read(key(),1000));}
 @Test public void refreshReplacesOneCompleteSnapshot()throws Exception{CategorySnapshotStore s=new CategorySnapshotStore(dir());assertTrue(s.write(key(),"[1]",1000));assertTrue(s.write(key(),"[2]",2000));assertEquals("[2]",s.read(key(),3000));}
 @Test public void fiveMinuteFreshnessDoesNotExtendFallbackLifetime()throws Exception{CategorySnapshotStore s=new CategorySnapshotStore(dir());s.write(key(),"[1]",1000);assertTrue(s.fresh(key(),2000));assertFalse(s.fresh(key(),301000));assertEquals("[1]",s.read(key(),301000));}
 @Test(expected=IllegalArgumentException.class) public void pathTraversalCannotBecomeASnapshotName()throws Exception{new CategorySnapshotStore(dir()).read("../other",2000);}
 @Test public void snapshotNamesDoNotContainProviderIdentity(){String k=key();assertEquals(64,k.length());assertFalse(k.contains("synthetic"));assertFalse(k.equals(CategorySnapshotStore.key("synthetic-owner","series")));}
 @Test public void corruptedLengthIsBoundedBeforeAllocation()throws Exception{File d=dir();CategorySnapshotStore s=new CategorySnapshotStore(d);s.write(key(),"[1]",1000);try(RandomAccessFile f=new RandomAccessFile(new File(d,key()+".cat"),"rw")){f.seek(12);f.writeInt(Integer.MAX_VALUE);}assertEquals(null,s.read(key(),2000));}
}
