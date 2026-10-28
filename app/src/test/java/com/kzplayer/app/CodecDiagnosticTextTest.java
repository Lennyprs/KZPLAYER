package com.kzplayer.app;import org.junit.Test;import static org.junit.Assert.*;
public class CodecDiagnosticTextTest {
 @Test public void standardPositive(){assertEquals("android.media.MediaCodec.error_1100",CodecDiagnosticText.diagnostic("android.media.MediaCodec.error_1100"));}
 @Test public void standardNegative(){assertEquals("android.media.MediaCodec.error_neg_1010",CodecDiagnosticText.diagnostic("android.media.MediaCodec.error_neg_1010"));}
 @Test public void nullSafe(){assertFalse(CodecDiagnosticText.diagnostic(null).contains("null"));}
 @Test public void urlsHidden(){assertFalse(CodecDiagnosticText.diagnostic("https://example.invalid/private").contains("https"));}
 @Test public void secretTextHidden(){assertFalse(CodecDiagnosticText.diagnostic("password=secret").contains("secret"));}
 @Test public void suffixRejected(){assertFalse(CodecDiagnosticText.diagnostic("android.media.MediaCodec.error_1 token=secret").contains("secret"));}
 @Test public void newlineRejected(){assertFalse(CodecDiagnosticText.diagnostic("android.media.MediaCodec.error_1\nsecret").contains("secret"));}
 @Test public void hugeDiagnosticRejected(){assertFalse(CodecDiagnosticText.diagnostic("android.media.MediaCodec.error_1234567890123456").contains("1234567890123456"));}
 @Test public void knownCause(){assertEquals("CodecException",CodecDiagnosticText.causeName("CodecException"));}
 @Test public void arbitraryCauseHidden(){assertEquals("autre exception",CodecDiagnosticText.causeName("secret"));}
 @Test public void nullCauseSafe(){assertEquals("autre exception",CodecDiagnosticText.causeName(null));}
 @Test public void knownOperation(){assertEquals("dequeueOutputBuffer",CodecDiagnosticText.operation("dequeueOutputBuffer"));}
 @Test public void arbitraryOperationHidden(){assertEquals("non fournie",CodecDiagnosticText.operation("secret"));}
 @Test public void nullOperationSafe(){assertEquals("non fournie",CodecDiagnosticText.operation(null));}
}
