package com.kzplayer.app;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Pure, shared normalization. No network, no per-item regex compilation. */
public final class SearchText {
    private SearchText() {}
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern SEPARATORS = Pattern.compile("[^\\p{L}\\p{N}]+");
    public static String fold(String raw) {
        String s = raw == null ? "" : raw;
        s = Normalizer.normalize(s, Normalizer.Form.NFKD).toLowerCase(Locale.ROOT);
        s = MARKS.matcher(s).replaceAll("").replace("œ", "oe").replace("æ", "ae").replace("ß", "ss").replace("&", " and ");
        return SEPARATORS.matcher(s).replaceAll(" ").trim();
    }
    public static String[] tokens(String raw) {
        String folded = fold(raw);
        return folded.isEmpty() ? new String[0] : folded.split(" +");
    }
    public static String fts(String raw) {
        String[] tokens = tokens(raw);
        StringBuilder out = new StringBuilder();
        // Every character comes from fold(), never interpolate raw FTS syntax.
        for (String token : tokens) {
            if (out.length() != 0) out.append(" AND ");
            out.append('"').append(token).append("*\"");
        }
        return out.toString();
    }
    private static int[] points(String s) {
        int[] out=new int[s.codePointCount(0,s.length())];int n=0;
        for(int i=0;i<s.length();) {int cp=s.codePointAt(i);out[n++]=cp;i+=Character.charCount(cp);}
        return out;
    }
    /** Linear-size unigram/bigram/trigram index; supports interior fragments too. */
    public static String grams(String raw) {
        java.util.LinkedHashSet<String> out=new java.util.LinkedHashSet<>();
        for(String word:tokens(raw)) {
            int[] points=points(word);
            for(int width=1;width<=3;width++) for(int i=0;i+width<=points.length;i++)
                out.add(String.valueOf((char)('a'+width-1))+new String(points,i,width));
        }
        StringBuilder joined=new StringBuilder();for(String term:out) {if(joined.length()>0) joined.append(' ');joined.append(term);}return joined.toString();
    }
    private static boolean numeric(String s) {for(int i=0;i<s.length();i++) if(!Character.isDigit(s.charAt(i))) return false;return !s.isEmpty();}
    public static String gramQuery(String raw) {
        java.util.List<String> words=new java.util.ArrayList<>(java.util.Arrays.asList(tokens(raw)));
        java.util.Collections.sort(words,(a,b)-> {
            int length=Integer.compare(b.codePointCount(0,b.length()),a.codePointCount(0,a.length()));
            if(length!=0) return length;
            return Boolean.compare(numeric(b),numeric(a));
        });
        java.util.LinkedHashSet<String> terms=new java.util.LinkedHashSet<>();
        for(String word:words) {
            int[] p=points(word);int width=Math.min(3,p.length);
            if(width==0) continue;
            terms.add(String.valueOf((char)('a'+width-1))+new String(p,0,width));
            terms.add(String.valueOf((char)('a'+width-1))+new String(p,p.length-width,width));
            break; // Longest, most selective word; LIKE validates every remaining word.
        }
        StringBuilder out=new StringBuilder();int n=0;
        for(String term:terms) {if(n++>=2) break;if(out.length()>0) out.append(" AND ");out.append('"').append(term).append('"');}
        // At most two AND terms: compatible with Android FTS expression-depth limits.
        // Exact LIKE checks below validate all words, so grams cannot create false hits.
        return out.toString();
    }
    public static boolean matches(String title, String query) {
        String folded = fold(title);
        String[] tokens = tokens(query);
        if (tokens.length == 0) return false;
        for (String token : tokens) if (!folded.contains(token)) return false;
        return true;
    }
    public static int rank(String foldedTitle, String foldedQuery) {
        if (foldedTitle.equals(foldedQuery)) return 0;
        if (foldedTitle.startsWith(foldedQuery + " ")) return 1;
        if (foldedTitle.contains(" " + foldedQuery + " ")) return 2;
        if (foldedTitle.contains(foldedQuery)) return 3;
        return 4;
    }
}
