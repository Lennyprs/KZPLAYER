package com.kzplayer.app;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
/** Pure JVM tests: no Uri, DataSpec or Android framework calls. */
public class VodSourceHintsTest {
 private static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
 private static VodSourceHints.Kind kind(String s){byte[] b=bytes(s);return VodSourceHints.classify(b,b.length);}
 @Test public void hlsWithoutExtension(){assertEquals(VodSourceHints.Kind.HLS,kind("#EXTM3U\n#EXT-X-VERSION:3\n"));}
 @Test public void hlsBomWhitespace(){assertEquals(VodSourceHints.Kind.HLS,kind("\uFEFF  #EXTM3U\r\n"));}
 @Test public void notAnArbitraryM3uPrefix(){assertEquals(VodSourceHints.Kind.UNKNOWN,kind("#EXTM3U_FAKE"));}
 @Test public void dashDetected(){assertEquals(VodSourceHints.Kind.DASH,kind("<MPD type=\"static\">"));}
 @Test public void dashAfterXmlDeclaration(){assertEquals(VodSourceHints.Kind.DASH,kind("<?xml version=\"1.0\"?>\n<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\">"));}
 @Test public void dashWithNamespace(){assertEquals(VodSourceHints.Kind.DASH,kind("<d:MPD xmlns:d=\"urn:mpeg:dash:schema:mpd:2011\">"));}
 @Test public void ignoreFakeMpdInsideComment(){assertEquals(VodSourceHints.Kind.HTML,kind("<!-- <MPD> --> <html>error</html>"));}
 @Test public void truncatedXmlNotInvented(){assertEquals(VodSourceHints.Kind.UNKNOWN,kind("<?xml version=\"1.0\""));}
 @Test public void mpdPrefixNotEnough(){assertEquals(VodSourceHints.Kind.UNKNOWN,kind("<MPDFake>"));}
 @Test public void htmlNotVideo(){assertEquals(VodSourceHints.Kind.HTML,kind("<!DOCTYPE html><html>denied</html>"));}
 @Test public void jsonObjectNotVideo(){assertEquals(VodSourceHints.Kind.JSON,kind("{\"error\":true}"));}
 @Test public void jsonArrayNotVideo(){assertEquals(VodSourceHints.Kind.JSON,kind("[1,2]"));}
 @Test public void mkvSignature(){byte[] b={(byte)0x1a,0x45,(byte)0xdf,(byte)0xa3};assertEquals(VodSourceHints.Kind.MKV,VodSourceHints.classify(b,4));}
 @Test public void mp4Signature(){assertEquals(VodSourceHints.Kind.MP4,kind("0000ftypisom"));}
 @Test public void aviSignature(){assertEquals(VodSourceHints.Kind.AVI,kind("RIFF0000AVI "));}
 @Test public void tsSignature(){byte[] b=new byte[377];b[0]=b[188]=b[376]=0x47;assertEquals(VodSourceHints.Kind.TS,VodSourceHints.classify(b,b.length));}
 @Test public void randomBinaryNotCalledHls(){assertEquals(VodSourceHints.Kind.UNKNOWN,VodSourceHints.classify(new byte[]{1,2,3,4},4));}
 @Test public void hlsOnlyAfterParsingError(){assertEquals("application/x-mpegURL",VodSourceHints.retryMime("ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED",VodSourceHints.Kind.HLS,"",false));}
 @Test public void dashOnlyAfterParsingError(){assertEquals("application/dash+xml",VodSourceHints.retryMime("ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED",VodSourceHints.Kind.DASH,"",false));}
 @Test public void noNetworkErrorRouting(){assertEquals("",VodSourceHints.retryMime("ERROR_CODE_IO_BAD_HTTP_STATUS",VodSourceHints.Kind.HLS,"",false));}
 @Test public void noCodecErrorRouting(){assertEquals("",VodSourceHints.retryMime("ERROR_CODE_DECODING_FAILED",VodSourceHints.Kind.HLS,"",false));}
 @Test public void oneCorrectionOnly(){assertEquals("",VodSourceHints.retryMime("ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED",VodSourceHints.Kind.HLS,"",true));}
 @Test public void doNotRetryAlreadyUsedHls(){assertEquals("",VodSourceHints.retryMime("ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED",VodSourceHints.Kind.HLS,"application/x-mpegURL",false));}
 @Test public void noGuessForUnknownBody(){assertEquals("",VodSourceHints.retryMime("ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED",VodSourceHints.Kind.UNKNOWN,"",false));}
 @Test public void noCodecTestsForHtml(){assertEquals("",VodSourceHints.retryMime("ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED",VodSourceHints.Kind.HTML,"",false));}
 @Test public void noCodecTestsForJson(){assertEquals("",VodSourceHints.retryMime("ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED",VodSourceHints.Kind.JSON,"",false));}
 @Test public void noMkvToMp4Pretence(){assertEquals("",VodSourceHints.retryMime("ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED",VodSourceHints.Kind.MKV,"",false));}
 @Test public void onlyWhitelistedOutput(){for(VodSourceHints.Kind k:VodSourceHints.Kind.values()){assertFalse(k.label.contains("fixture"));assertFalse(k.label.contains("token"));assertFalse(k.label.contains("https://"));}}
}
