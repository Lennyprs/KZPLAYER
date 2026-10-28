package com.kzplayer.app;
import org.junit.Test;
import static org.junit.Assert.*;

public class V360PolicyTest {
 @Test public void sameChannelIdIgnoresChangingPlaybackUrl(){assertEquals(LiveRecentKey.of("list-a","42","original","url-a","TV"),LiveRecentKey.of("list-a","42","changed","url-b","TV HD"));}
 @Test public void differentListsKeepSameChannelIdsSeparate(){assertFalse(LiveRecentKey.of("list-a","42",null,null,"TV").equals(LiveRecentKey.of("list-b","42",null,null,"TV")));}
 @Test public void stablePortalCommandIgnoresResolvedTokenUrl(){assertEquals(LiveRecentKey.of("list-a",null,"source-command","resolved-a","TV"),LiveRecentKey.of("list-a",null,"source-command","resolved-b","TV"));}
 @Test public void m3uSourcesRemainDistinct(){assertFalse(LiveRecentKey.of("list-a",null,null,"fixture-a","TV").equals(LiveRecentKey.of("list-a",null,null,"fixture-b","TV")));}
 @Test public void blankIdentifiersFallBackToTheName(){assertEquals(LiveRecentKey.of("list-a",null,null,null,"TV"),LiveRecentKey.of("list-a","","","","TV"));}
 @Test public void identityDoesNotRevealSourceInformation(){String key=LiveRecentKey.of("list-a","42","synthetic-command","fixture","TV");assertTrue(key.matches("[0-9a-f]{64}"));}
}
