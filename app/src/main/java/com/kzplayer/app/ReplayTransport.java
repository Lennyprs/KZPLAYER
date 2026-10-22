package com.kzplayer.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.nio.charset.StandardCharsets;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** V117 : une seule sonde a la fois, fermee avant la suivante. */
public final class ReplayTransport {
    private ReplayTransport() { }
    public static final class Result {
        public final String url, mime, userAgent, diagnostic;
        public final ArrayList<String> candidates;
        private Result(String u, String m, String a, String d, List<String> c) {
            url=u; mime=m; userAgent=a; diagnostic=d; candidates=new ArrayList<>(c);
        }
    }
    public static Result resolve(OkHttpClient shared, List<String> urls, long startMs, long endMs) {
        OkHttpClient client = shared.newBuilder().connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS).build();
        StringBuilder log = new StringBuilder();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
        String[] agents = {"IPTVSmartersPro", "VLC/3.0.20 LibVLC/3.0.20"};
        for (String ua : agents) {
            for (int i=0; i<urls.size(); i++) {
                if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted())
                    return new Result("", "", ua, log + "Delai de recherche atteint.", new ArrayList<>());
                String url = urls.get(i);
                if (!ReplayUrls.isArchiveUrl(url)) continue;
                String tag = "ERREUR";
                String mime = "";
                boolean good = false;
                // Pas de Range : certains panels refusent les sondes partielles.
                Request req = new Request.Builder().url(url).header("User-Agent", ua)
                    .header("Accept", "*/*").build();
                try (Response r = client.newCall(req).execute()) {
                    if (!r.isSuccessful()) tag = "HTTP" + r.code();
                    else if (r.request().url().encodedPath().contains("/live/")) tag = "DIRECT-REDIRECTION";
                    else {
                        String type = r.header("Content-Type", "").toLowerCase(java.util.Locale.US);
                        byte[] bytes = r.peekBody(32768L).bytes();
                        String text = new String(bytes, StandardCharsets.ISO_8859_1).trim();
                        if (bytes.length < 8) tag = "VIDE";
                        else if (type.contains("html") || type.contains("json") || text.startsWith("<") || text.startsWith("{") || text.startsWith("[")) tag = "PAGE-ERREUR";
                        else if (text.startsWith("#EXTM3U")) {
                            if (ReplayArchiveGuard.isOutsideProgram(text, startMs, endMs)) tag = "HORAIRE-INCORRECT";
                            else { good=true; mime="application/x-mpegURL"; tag="OK-HLS"; }
                        } else if ((bytes[0] & 255) == 0x47) { good=true; mime="video/mp2t"; tag="OK-TS"; }
                        else if ((bytes.length>8 && new String(bytes, 4, 4, StandardCharsets.ISO_8859_1).equals("ftyp")) ||
                                 ((bytes[0]&255)==0x1a && (bytes[1]&255)==0x45 && (bytes[2]&255)==0xdf && (bytes[3]&255)==0xa3)) {
                            good=true; tag="OK-CONTENEUR";
                        } else tag="CONTENU-NON-VIDEO";
                    }
                } catch (Exception e) { tag = "RESEAU-" + e.getClass().getSimpleName(); }
                // Jamais d'identifiant/mot de passe dans les journaux affiches.
                log.append("Candidat ").append(i+1).append(" / ").append(ua.startsWith("VLC") ? "VLC" : "IPTV").append(" : ").append(tag).append('\n');
                if (good) {
                    ArrayList<String> rest = new ArrayList<>(); rest.add(url);
                    for (int j=i+1; j<urls.size(); j++) if (ReplayUrls.isArchiveUrl(urls.get(j))) rest.add(urls.get(j));
                    return new Result(url, mime, ua, log.toString(), rest);
                }
            }
        }
        return new Result("", "", "IPTVSmartersPro", log.toString(), new ArrayList<>());
    }
}
