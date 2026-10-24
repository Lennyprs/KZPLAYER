package com.kzplayer.app;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Streaming M3U parser used by the search index; bounded one entry at a time. */
public final class SearchM3u {
    private SearchM3u() {}
    private static final Pattern ATTR=Pattern.compile("([a-zA-Z_-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s,]+))");
    private static final Pattern INLINE=Pattern.compile("(?i)\\s(https?://[^\\s]+)$");
    private static final Pattern EPISODE=Pattern.compile("(?i)\\s*(?:s\\s*\\d{1,2}\\s*e\\s*\\d{1,3}|saison\\s*\\d+|season\\s*\\d+|episode\\s*\\d+|\\b\\d{1,2}x\\d{1,3}\\b).*$");
    private static final Pattern INFER_EPISODE=Pattern.compile("(?i)\\b(?:s\\s*\\d{1,2}\\s*e\\s*\\d{1,3}|\\d{1,2}x\\d{1,3})\\b");
    private static final Pattern VIDEO=Pattern.compile("(?i)\\.(?:mp4|mkv|avi|m4v|mov|webm)(?:[?&#]|$)");
    private static final Pattern UA=Pattern.compile("(?i)(?:http-user-agent\\s*=\\s*([^\\r\\n]+)|\"user-agent\"\\s*:\\s*\"([^\"]+)\")");
    public static String classify(String group,String url,String name) {
        String g=SearchText.fold(group);String u=url.toLowerCase(Locale.ROOT);
        if(u.contains("/series/")||g.contains("serie")||g.contains("tv show")||g.contains("shows")) return "series";
        if(u.contains("/movie/")||u.contains("/movies/")||u.contains("/vod/")||g.contains("film")||g.contains("movie")||g.contains("vod")||g.contains("cinema")) return "movie";
        if(INFER_EPISODE.matcher(name).find()) return "series";
        if(VIDEO.matcher(url).find()) return "movie";
        return "live";
    }
    public static String seriesName(String name) {
        String s=EPISODE.matcher(name).replaceFirst("").trim();
        s=s.replaceAll("^[\\s\\-–|:]+|[\\s\\-–|:]+$","");return s.isEmpty()?name:s;
    }
    public static final class Entry {
        public final String name,logo,group,summary,url,userAgent,kind;
        Entry(String name,String logo,String group,String summary,String url,String ua) {
            this.kind=classify(group,url,name);this.name=kind.equals("series")?seriesName(name):name;
            this.logo=logo;this.group=group;this.summary=summary;this.url=url;this.userAgent=ua;
        }
    }
    public static void read(BufferedReader input,Consumer<Entry> consumer) throws IOException {
        String name="",logo="",group="",summary="",ua="";boolean valid=false;String raw;
        while((raw=input.readLine())!=null) {
            if(raw.length()>1048576) throw new IOException("Entree M3U trop longue");
            String line=raw.trim();if(line.startsWith("\uFEFF")) line=line.substring(1).trim();
            if(line.regionMatches(true,0,"#EXTM3U",0,7)) {valid=true;continue;}
            if(line.regionMatches(true,0,"#EXTINF",0,7)) {
                valid=true;name="";logo="";group="";summary="";ua="";
                String inline="";Matcher m=INLINE.matcher(line);
                if(m.find()) {inline=m.group(1);line=line.substring(0,m.start()).trim();}
                boolean quoted=false;char quote=0;int comma=-1;
                for(int i=0;i<line.length();i++) {
                    char ch=line.charAt(i);
                    if((ch=='\"'||ch=='\'')&&(!quoted||quote==ch)) {quoted=!quoted;quote=ch;}
                    else if(ch==','&&!quoted) {comma=i;break;}
                }
                if(comma>=0) name=line.substring(comma+1).trim();
                HashMap<String,String> attrs=new HashMap<>();Matcher a=ATTR.matcher(comma>=0?line.substring(0,comma):line);
                while(a.find()) attrs.put(a.group(1).toLowerCase(Locale.ROOT),a.group(2)!=null?a.group(2):a.group(3)!=null?a.group(3):a.group(4));
                logo=attrs.getOrDefault("tvg-logo","");group=attrs.getOrDefault("group-title",attrs.getOrDefault("tvg-group",attrs.getOrDefault("category","")));
                if(name.isEmpty()) name=attrs.getOrDefault("tvg-name","");
                summary=attrs.getOrDefault("plot",attrs.getOrDefault("description",attrs.getOrDefault("desc",attrs.getOrDefault("overview",attrs.getOrDefault("synopsis","")))));
                if(!inline.isEmpty()) {consumer.accept(new Entry(name.isEmpty()?"Contenu sans titre":name,logo,group,summary,inline,ua));name="";logo="";group="";summary="";}
            } else if(line.regionMatches(true,0,"#EXTVLCOPT",0,10)||line.regionMatches(true,0,"#EXTHTTP",0,8)) {
                Matcher m=UA.matcher(line);if(m.find()) ua=(m.group(1)!=null?m.group(1):m.group(2)).trim().replaceAll("^['\"]|['\"]$","");
            } else if(line.startsWith("http://")||line.startsWith("https://")) {
                valid=true;consumer.accept(new Entry(name.isEmpty()?"Contenu sans titre":name,logo,group,summary,line,ua));name="";logo="";group="";summary="";ua="";
            }
        }
        if(!valid) throw new IOException("Format M3U invalide");
    }
}
