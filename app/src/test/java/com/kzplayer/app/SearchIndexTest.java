package com.kzplayer.app;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.BufferedReader;
import java.io.StringReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Runs before the official APK is signed/published. No provider credentials required. */
public class SearchIndexTest {
    private List<SearchM3u.Entry> parse(String text)throws IOException {
        List<SearchM3u.Entry> out=new ArrayList<>();
        SearchM3u.read(new BufferedReader(new StringReader(text)),out::add);return out;
    }
    @Test public void accents(){assertEquals("l ete a noel",SearchText.fold("L'Été à Noël"));}
    @Test public void punctuation(){assertTrue(SearchText.matches("Monte-Cristo (2024)","monte cristo"));}
    @Test public void substring(){assertTrue(SearchText.matches("Harry Potter","arry"));}
    @Test public void allWords(){assertFalse(SearchText.matches("Star Wars","star trek"));}
    @Test public void ligatures(){assertEquals("coeur and aeon",SearchText.fold("CŒUR & ÆON"));}
    @Test public void unicode(){assertTrue(SearchText.matches("千と千尋の神隠し","千尋"));}
    @Test public void noPunctuationWildcard(){assertEquals(0,SearchText.tokens("!!!").length);}
    @Test public void interiorGrams(){assertTrue(SearchText.grams("Madmax").contains("cmax"));}
    @Test public void gramBoundaries(){assertEquals("\"char\" AND \"crry\"",SearchText.gramQuery("Harry"));}
    @Test public void numberSelectivity(){assertEquals("\"c153\" AND \"c219\"",SearchText.gramQuery("film numero 153219"));}
    @Test public void boundedExpression(){assertTrue(SearchText.gramQuery("one two three four five six seven eight nine ten eleven twelve").split(" AND ").length<=2);}
    @Test public void exactTitleFirst(){assertTrue(SearchText.rank("avatar","avatar")<SearchText.rank("avatar 2","avatar"));}
    @Test public void episodeFilm(){assertEquals("movie",SearchM3u.classify("Films","https://fixture.invalid/movie/1.mp4","Episode 42"));}
    @Test public void genericFilm(){assertEquals("movie",SearchM3u.classify("","https://fixture.invalid/1.mkv","A film"));}
    @Test public void series(){assertEquals("series",SearchM3u.classify("","https://fixture.invalid/1.mkv","A S01E02"));}
    @Test public void live(){assertEquals("live",SearchM3u.classify("","https://fixture.invalid/live/1.ts","A channel"));}
    @Test public void commaTitle()throws Exception {
        List<SearchM3u.Entry> out=parse("\uFEFF#EXTM3U\r\n#EXTINF:-1 group-title=\"Films, FR\",Un film, vraiment\r\nhttps://fixture.invalid/1.mp4\r\n");
        assertEquals(1,out.size());assertEquals("Un film, vraiment",out.get(0).name);assertEquals("Films, FR",out.get(0).group);
    }
    @Test public void quotedAttributes()throws Exception {
        assertEquals("Fallback",parse("#EXTM3U\n#EXTINF:-1 group-title='Films' tvg-name='Fallback',\nhttps://fixture.invalid/1.mp4\n").get(0).name);
    }
    @Test public void perEntryUA()throws Exception {
        List<SearchM3u.Entry> out=parse("#EXTM3U\n#EXTINF:-1 group-title=Films,A\n#EXTVLCOPT:http-user-agent=Fixture-UA\nhttps://fixture.invalid/1.mp4\n#EXTINF:-1 group-title=Films,B\nhttps://fixture.invalid/2.mp4\n");
        assertEquals("Fixture-UA",out.get(0).userAgent);assertEquals("",out.get(1).userAgent);
    }
    @Test(expected=IOException.class) public void htmlIsNotAnEmptyCatalog()throws Exception {parse("<html>access denied</html>");}
    @Test public void emptyM3u()throws Exception {assertTrue(parse("#EXTM3U\n").isEmpty());}
    @Test public void inlineUrl()throws Exception {assertEquals("A film",parse("#EXTM3U\n#EXTINF:-1,A film https://fixture.invalid/1.mp4\n").get(0).name);}
}
