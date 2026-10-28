package com.kzplayer.app;
import org.junit.Test;import static org.junit.Assert.*;
public class ReferenceTransportGuardTest {
 private boolean ok(String a,String b){return ReferenceTransportGuard.sameResource(a,b);}
 @Test public void exactUrlAlwaysPreserved(){assertTrue(ok("http://example.invalid/movie/42.mkv","http://example.invalid/movie/42.mkv"));}
 @Test public void numericFileTransportCanChange(){assertTrue(ok("http://example.invalid/movie/42.mkv","http://example.invalid/movie/42.ts"));}
 @Test public void fileStemCanBeNonNumeric(){assertTrue(ok("http://example.invalid/film-name.mkv","http://example.invalid/film-name.mp4"));}
 @Test public void extensionlessVariantAllowed(){assertTrue(ok("http://example.invalid/movie/42.mkv","http://example.invalid/movie/42"));}
 @Test public void otherMovieRejected(){assertFalse(ok("http://example.invalid/movie/42.mkv","http://example.invalid/movie/43.ts"));}
 @Test public void otherHostRejected(){assertFalse(ok("http://example.invalid/42.mkv","http://other.invalid/42.ts"));}
 @Test public void differentSchemeRejected(){assertFalse(ok("https://example.invalid/42.mkv","http://example.invalid/42.ts"));}
 @Test public void opaqueQueryUnchanged(){assertTrue(ok("https://example.invalid/42.mkv?token=fixture%2Bsig","https://example.invalid/42.ts?token=fixture%2Bsig"));}
 @Test public void tokenCannotBeRewritten(){assertFalse(ok("https://example.invalid/42.mkv?token=fixture.mkv","https://example.invalid/42.ts?token=fixture.mp4"));}
 @Test public void numericStreamParameterCanChange(){assertTrue(ok("http://example.invalid/play?stream=42.mkv&token=fixture","http://example.invalid/play?stream=42.ts&token=fixture"));}
 @Test public void streamIdCannotChange(){assertFalse(ok("http://example.invalid/play?stream=42.mkv","http://example.invalid/play?stream=43.mkv"));}
 @Test public void nonNumericStreamValueIsOpaque(){assertFalse(ok("http://example.invalid/play?stream=fixture.mkv","http://example.invalid/play?stream=fixture.ts"));}
 @Test public void parameterOrderCannotChange(){assertFalse(ok("http://example.invalid/42.mkv?a=1&b=2","http://example.invalid/42.ts?b=2&a=1"));}
 @Test public void localFilesCannotBeInvented(){assertFalse(ok("file:///storage/42.mkv","file:///storage/42.mp4"));}
 @Test public void malformedInitialUrlStillPassed(){assertTrue(ok("fixture url","fixture url"));}
 @Test public void nullRejected(){assertFalse(ok(null,"fixture"));assertFalse(ok("fixture",null));}
 @Test public void fragmentCannotChange(){assertFalse(ok("http://example.invalid/42.mkv#one","http://example.invalid/42.ts#two"));}
 @Test public void actualPortCannotChange(){assertFalse(ok("http://example.invalid:8080/42.mkv","http://example.invalid:80/42.ts"));}
}
