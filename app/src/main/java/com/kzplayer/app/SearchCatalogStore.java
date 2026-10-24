package com.kzplayer.app;

import android.content.Context;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** App-private, bounded disk index. No catalog-count LRU and no credentials in filenames. */
public final class SearchCatalogStore {
    private final File file;
    private SQLiteDatabase db;
    public final AtomicLong revision = new AtomicLong();
    public static final int PER_LIST_LIMIT = 350;
    public SearchCatalogStore(Context app) {
        File directory = new File(app.getCacheDir(), "search-v123");
        directory.mkdirs();
        file = new File(directory, "catalogs.db");
    }
    private synchronized SQLiteDatabase database() {
        if (db != null && db.isOpen()) return db;
        SQLiteDatabase candidate = SQLiteDatabase.openOrCreateDatabase(file, null);
        try {
        candidate.enableWriteAheadLogging();
        // 512 MiB maximum main DB; no all-catalog copies in the Java heap.
        candidate.setMaximumSize(512L * 1024L * 1024L);
        for (String sql : DDL) candidate.execSQL(sql);
        db = candidate;
        return db;
        } catch (RuntimeException error) { candidate.close(); throw error; }
    }
    public static final String[] DDL = {
        "CREATE TABLE IF NOT EXISTS catalogs (catalog TEXT PRIMARY KEY, updated INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS queries (catalog TEXT, query TEXT, updated INTEGER NOT NULL, limited INTEGER NOT NULL, PRIMARY KEY(catalog,query))",
        "CREATE TABLE IF NOT EXISTS items (_id INTEGER PRIMARY KEY, catalog TEXT NOT NULL, identity TEXT NOT NULL, title TEXT NOT NULL, terms TEXT NOT NULL, payload TEXT NOT NULL, stamp TEXT NOT NULL, UNIQUE(catalog,identity))",
        "CREATE INDEX IF NOT EXISTS items_catalog_title ON items(catalog,title)",
        "CREATE VIRTUAL TABLE IF NOT EXISTS search_fts USING fts4(terms,content='items',tokenize=unicode61)",
        "CREATE TRIGGER IF NOT EXISTS search_insert AFTER INSERT ON items BEGIN INSERT INTO search_fts(docid,terms) VALUES(new._id,new.terms); END",
        "CREATE TRIGGER IF NOT EXISTS search_before_update BEFORE UPDATE ON items BEGIN DELETE FROM search_fts WHERE docid=old._id; END",
        "CREATE TRIGGER IF NOT EXISTS search_update AFTER UPDATE ON items BEGIN INSERT INTO search_fts(docid,terms) VALUES(new._id,new.terms); END",
        "CREATE TRIGGER IF NOT EXISTS search_delete BEFORE DELETE ON items BEGIN DELETE FROM search_fts WHERE docid=old._id; END"
    };
    public long updated(String key) {
        try (Cursor c = database().rawQuery("SELECT updated FROM catalogs WHERE catalog=?",new String[]{key})) {
            return c.moveToFirst() ? c.getLong(0) : 0L;
        }
    }
    public int queryState(String key,String query,long since) {
        try (Cursor c = database().rawQuery("SELECT limited FROM queries WHERE catalog=? AND query=? AND updated>=?",new String[]{key,SearchText.fold(query),Long.toString(since)})) {
            return c.moveToFirst() ? c.getInt(0) : -1;
        }
    }
    public void completeQuery(String key,String query,boolean limited) {
        ContentValues v = new ContentValues();v.put("catalog",key);v.put("query",SearchText.fold(query));v.put("updated",System.currentTimeMillis());v.put("limited",limited?1:0);
        database().insertWithOnConflict("queries",null,v,SQLiteDatabase.CONFLICT_REPLACE);
        revision.incrementAndGet();
    }
    public static final class Row {
        public final String identity,title,payload;
        public Row(String identity,String title,String payload) {this.identity=identity;this.title=title;this.payload=payload;}
    }
    public void put(String key,String stamp,List<Row> rows) {
        SQLiteDatabase d=database();d.beginTransaction();
        try {
            for(Row row:rows) {
                ContentValues v=new ContentValues();v.put("catalog",key);v.put("identity",row.identity);v.put("title",SearchText.fold(row.title));v.put("terms",SearchText.grams(row.title));v.put("payload",row.payload);v.put("stamp",stamp);
                // UPDATE, not REPLACE: FTS external-content delete triggers need the old row.
                if(d.update("items",v,"catalog=? AND identity=?",new String[]{key,row.identity})==0) d.insertOrThrow("items",null,v);
            }
            d.setTransactionSuccessful();
        } finally {d.endTransaction();}
        revision.incrementAndGet();
    }
    public void complete(String key,String stamp) {
        SQLiteDatabase d=database();d.beginTransaction();
        try {
            d.delete("items","catalog=? AND stamp<>?",new String[]{key,stamp});
            ContentValues v=new ContentValues();v.put("catalog",key);v.put("updated",System.currentTimeMillis());
            d.insertWithOnConflict("catalogs",null,v,SQLiteDatabase.CONFLICT_REPLACE);
            d.setTransactionSuccessful();
        } finally {d.endTransaction();}
        revision.incrementAndGet();
    }
    public List<String> find(String key,String query) {
        ArrayList<String> out=new ArrayList<>();String expr=SearchText.gramQuery(query);
        if(expr.isEmpty()) return out;
        java.util.HashSet<Long> ids=new java.util.HashSet<>();
        try(Cursor c=database().rawQuery("SELECT _id,payload FROM items WHERE catalog=? AND title=? LIMIT "+(PER_LIST_LIMIT+1),new String[]{key,SearchText.fold(query)})) {
            while(c.moveToNext()) {ids.add(c.getLong(0));out.add(c.getString(1));}
        }
        if(out.size()>PER_LIST_LIMIT) return out;
        String baseSql="SELECT i._id,i.payload FROM search_fts CROSS JOIN items i ON i._id=search_fts.docid WHERE search_fts MATCH ? AND i.catalog=?";
        StringBuilder sql=new StringBuilder(baseSql);
        ArrayList<String> args=new ArrayList<>();args.add(expr);args.add(key);
        for(String token:SearchText.tokens(query)) {sql.append(" AND i.title LIKE ?");args.add("%"+token+"%");}
        sql.append(" LIMIT ").append(PER_LIST_LIMIT+1);
        try(Cursor c=database().rawQuery(sql.toString(),args.toArray(new String[0]))) {
            while(c.moveToNext()) {if(ids.add(c.getLong(0))) out.add(c.getString(1));if(out.size()>PER_LIST_LIMIT) break;}
        }
        return out;
    }
    public void invalidate() {
        database().execSQL("UPDATE catalogs SET updated=0");database().delete("queries",null,null);revision.incrementAndGet();
    }
    public void removeInactive(String[] activeKeys) {
        // Retain every currently configured list, however many there are.
        SQLiteDatabase d=database();ArrayList<String> old=new ArrayList<>();
        java.util.HashSet<String> active=new java.util.HashSet<>(java.util.Arrays.asList(activeKeys));
        try(Cursor c=d.rawQuery("SELECT DISTINCT catalog FROM items",null)) {while(c.moveToNext()) if(!active.contains(c.getString(0))) old.add(c.getString(0));}
        for(String key:old) {d.delete("items","catalog=?",new String[]{key});d.delete("catalogs","catalog=?",new String[]{key});d.delete("queries","catalog=?",new String[]{key});}
    }
}
