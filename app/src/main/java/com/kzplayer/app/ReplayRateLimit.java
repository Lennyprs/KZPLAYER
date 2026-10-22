package com.kzplayer.app;

import androidx.media3.datasource.HttpDataSource;
import java.io.IOException;
import java.net.URI;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** V120 : protection locale par hote, sans conserver d'identifiant IPTV. */
public final class ReplayRateLimit {
    private ReplayRateLimit() { }
    private static final long DEFAULT_MS=300000L;
    private static final long MAX_MS=86400000L;
    private static final ConcurrentHashMap<String,Long> deadlines=new ConcurrentHashMap<>();
    public interface Store {long read(String host);void write(String host,long untilMs);}
    private static volatile Store store;
    public static void setStore(Store next) {store=next;}
    public static final class Limited extends IOException {
        public final long remainingMs;
        public Limited(long remainingMs) {super("REPLAY_HTTP_429_ATTENTE");this.remainingMs=remainingMs;}
    }
    public static final class HttpFailure extends IOException {
        public final int responseCode;
        public HttpFailure(int code) { super("HTTP_"+code); responseCode=code; }
    }
    public static synchronized void recordStatus(String url,int status,Map<String,List<String>> headers) {
        if(status!=429)return;
        String key=host(url);if(key.isEmpty())return;
        long next=System.currentTimeMillis()+retryDelayMs(headers,System.currentTimeMillis());
        Long old=deadlines.get(key);long until=old==null?next:Math.max(old,next);deadlines.put(key,until);
        Store s=store;if(s!=null)try{s.write(key,Math.max(s.read(key),until));}catch(RuntimeException ignored){}
    }
    private static String host(String url) {
        try {String h=URI.create(url).getHost();return h==null?"":h.toLowerCase(Locale.US);}catch(Exception e){return "";}
    }
    public static long remainingMs(String url) {return remainingMs(url,System.currentTimeMillis());}
    public static long remainingMs(String url,long now) {
        String key=host(url);if(key.isEmpty())return 0L;
        long until=deadlines.containsKey(key)?deadlines.get(key):0L;
        Store s=store;if(s!=null)try{until=Math.max(until,s.read(key));}catch(RuntimeException ignored){}
        return Math.max(0L,Math.min(MAX_MS,until-now));
    }
    public static long remainingMs(Iterable<String> urls) {
        long remaining=0L;for(String url:urls)remaining=Math.max(remaining,remainingMs(url));return remaining;
    }
    private static HttpDataSource.InvalidResponseCodeException http(Throwable error) {
        Throwable e=error;
        for(int n=0;e!=null&&n<12;n++){
            if(e instanceof HttpDataSource.InvalidResponseCodeException)return (HttpDataSource.InvalidResponseCodeException)e;
            if(e.getCause()==e)break;e=e.getCause();
        }
        return null;
    }
    public static boolean isLimited(Throwable error) {
        Throwable e=error;
        for(int n=0;e!=null&&n<12;n++){
            if(e instanceof Limited)return true;
            if(e instanceof HttpDataSource.InvalidResponseCodeException &&
                ((HttpDataSource.InvalidResponseCodeException)e).responseCode==429)return true;
            if(e.getCause()==e)break;e=e.getCause();
        }
        return false;
    }
    public static long retryDelayMs(Map<String,List<String>> headers,long now) {
        if(headers!=null)for(Map.Entry<String,List<String>> h:headers.entrySet()){
            if(h.getKey()==null||!h.getKey().equalsIgnoreCase("Retry-After")||h.getValue()==null)continue;
            for(String value:h.getValue()){
                if(value==null)continue;
                try {
                    long sec=Long.parseLong(value.trim());
                    if(sec>=0)return Math.max(1000L,Math.min(86400L,sec)*1000L);
                }catch(NumberFormatException ignored){}
                try {
                    long delta=ZonedDateTime.parse(value.trim(),DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant().toEpochMilli()-now;
                    if(delta>=0)return Math.max(1000L,Math.min(MAX_MS,delta));
                }catch(Exception ignored){}
            }
        }
        return DEFAULT_MS;
    }
    public static void record(String url,Throwable error) {record(url,error,System.currentTimeMillis());}
    public static synchronized void record(String url,Throwable error,long now) {
        HttpDataSource.InvalidResponseCodeException h=http(error);
        if(h==null||h.responseCode!=429)return;
        String key=host(url);if(key.isEmpty())return;
        long next=now+retryDelayMs(h.headerFields,now);
        Long old=deadlines.get(key);long until=old==null?next:Math.max(old,next);deadlines.put(key,until);
        Store s=store;if(s!=null)try{s.write(key,Math.max(s.read(key),until));}catch(RuntimeException ignored){}
    }
}
