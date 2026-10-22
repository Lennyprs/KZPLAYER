package com.kzplayer.app;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public final class ReplayUrls {
    private ReplayUrls() { }
    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8").replace("+", "%20"); }
        catch (java.io.UnsupportedEncodingException e) { throw new IllegalStateException(e); }
    }
    public static List<String> build(String base, String user, String pass, String stream,
                                      String stamp, long startSeconds, int minutes, boolean hls) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        if (base == null || stream == null || stream.trim().isEmpty() || stamp == null || stamp.isEmpty()) return new ArrayList<>();
        String root = base.replaceAll("/+$", "");
        String prefix = root + "/timeshift/" + enc(user) + "/" + enc(pass) + "/" + Math.max(1, minutes) + "/" + stamp + "/" + enc(stream);
        if (hls) urls.add(prefix + ".m3u8");
        urls.add(prefix + ".ts");
        // Certains panels n'exposent l'archive que par la route a timestamp UNIX.
        String epochPrefix = root + "/timeshift/" + enc(user) + "/" + enc(pass) + "/" + Math.max(1,minutes) + "/" + startSeconds + "/" + enc(stream);
        if (startSeconds > 0L) {
            if (hls) urls.add(epochPrefix + ".m3u8");
            urls.add(epochPrefix + ".ts");
        }
        String query = "?username=" + enc(user) + "&password=" + enc(pass) + "&stream=" + enc(stream);
        for (String start : new String[]{stamp, String.valueOf(startSeconds)}) {
            String tail = query + "&start=" + enc(start) + "&duration=" + Math.max(1, minutes);
            urls.add(root + "/streaming/timeshift.php" + tail);
            urls.add(root + "/timeshift.php" + tail);
            if (hls) urls.add(root + "/streaming/timeshift.php" + tail + "&type=m3u8");
        }
        urls.add(prefix);
        if (startSeconds > 0L) urls.add(epochPrefix);
        return new ArrayList<>(urls);
    }
    public static String alternateUserAgent(String current) {
        return current != null && current.startsWith("VLC")
            ? "IPTVSmartersPro" : "VLC/3.0.20 LibVLC/3.0.20";
    }
    public static boolean replayStarted(boolean ready, int videoWidth, long positionMs) {
        return ready && (videoWidth > 0 || positionMs >= 1000L);
    }
    public static boolean isArchiveUrl(String url) {
        try {
            URI uri = URI.create(url);
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) return false;
            String path = uri.getPath().toLowerCase(Locale.US);
            return path.contains("/timeshift/") || path.endsWith("/timeshift.php");
        } catch (Exception e) { return false; }
    }
    public static String safeDiagnosticUrl(String url) {
        if (url == null) return "";
        return url.replaceAll("(?i)(/timeshift/)[^/]+/[^/]+/", "$1***/***/")
            .replaceAll("(?i)([?&](?:username|password|token|play_token)=)[^&#]*", "$1***");
    }
    public static boolean isDecodeFailure(String name) {
        return "ERROR_CODE_DECODING_FAILED".equals(name) ||
            "ERROR_CODE_DECODER_INIT_FAILED".equals(name) ||
            "ERROR_CODE_DECODING_FORMAT_UNSUPPORTED".equals(name) ||
            "ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES".equals(name);
    }
    public static boolean isSoftwareDecoder(String name) {
        String n = name.toLowerCase(Locale.US);
        return n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.startsWith("c2.google.");
    }
}
