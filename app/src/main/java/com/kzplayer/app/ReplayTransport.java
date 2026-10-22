package com.kzplayer.app;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.nio.charset.StandardCharsets;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** V118 : sondes sequentielles bornees, jamais une condition obligatoire de lecture. */
public final class ReplayTransport {
    private ReplayTransport() { }
    public static final class Result {
        public final String url, mime, userAgent, diagnostic;
        public final ArrayList<String> candidates;
        private Result(String u, String m, String a, String d, List<String> c) {
            url=u; mime=m; userAgent=a; diagnostic=d; candidates=new ArrayList<>(c);
        }
    }
    private static Result result(String chosen, String mime, String ua, String log,
                                  List<String> urls, Set<String> rejected) {
        ArrayList<String> rest = new ArrayList<>();
        if (!chosen.isEmpty()) rest.add(chosen);
        for (String u : urls)
            if (ReplayUrls.isArchiveUrl(u) && !rejected.contains(u) && !rest.contains(u)) rest.add(u);
        if (chosen.isEmpty() && !rest.isEmpty()) chosen=rest.get(0);
        return new Result(chosen, mime, ua, log, rest);
    }
    public static Result resolve(OkHttpClient shared, List<String> urls, long startMs, long endMs) {
        StringBuilder log = new StringBuilder();
        Set<String> rejected = new LinkedHashSet<>();
        // Donner sa chance aux DEUX identites sur la premiere URL avant les variantes.
        // V117 pouvait consommer tout son delai avant d'avoir essaye VLC.
        String[] agents = {"VLC/3.0.20 LibVLC/3.0.20", "IPTVSmartersPro"};
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        search: for (int i=0; i<urls.size(); i++) {
            String url = urls.get(i);
            if (!ReplayUrls.isArchiveUrl(url)) continue;
            for (String ua : agents) {
                if (Thread.currentThread().isInterrupted())
                    return new Result("", "", ua, log + "Recherche annulee.", new ArrayList<>());
                long remaining = TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime());
                if (remaining <= 0) break search;
                OkHttpClient client = shared.newBuilder().connectTimeout(4, TimeUnit.SECONDS)
                    .readTimeout(4, TimeUnit.SECONDS)
                    .callTimeout(Math.max(1L, Math.min(5000L, remaining)), TimeUnit.MILLISECONDS).build();
                String tag="ERREUR", mime="";
                boolean good=false, reject=false;
                Request req=new Request.Builder().url(url).header("User-Agent", ua)
                    .header("Accept", "*/*").build();
                try (Response r=client.newCall(req).execute()) {
                    if (!r.isSuccessful()) {
                        tag="HTTP"+r.code();
                        // Authentification / quota / panne transitoire : laisser le vrai
                        // lecteur essayer, avec ses reprises, plutot que bloquer la fiche.
                        reject=r.code()==400 || r.code()==404 || r.code()==410;
                    } else if (r.request().url().encodedPath().toLowerCase(Locale.US).contains("/live/")) {
                        tag="DIRECT-REDIRECTION"; reject=true;
                    } else {
                        String type=r.header("Content-Type", "").toLowerCase(Locale.US);
                        // Ne pas attendre 32 Ko sur une archive lente ou a faible debit.
                        byte[] bytes=r.peekBody(1024L).bytes();
                        String text=new String(bytes, StandardCharsets.UTF_8).trim();
                        if (text.startsWith("\uFEFF")) text=text.substring(1).trim();
                        if (bytes.length<8) tag="REPONSE-INCOMPLETE";
                        else if (type.contains("html") || type.contains("json") || type.contains("xml") ||
                                 text.startsWith("<") || text.startsWith("{") || text.startsWith("[")) {
                            tag="PAGE-ERREUR"; reject=true;
                        } else if (text.startsWith("#EXTM3U")) {
                            if (ReplayArchiveGuard.isOutsideProgram(text, startMs, endMs)) {
                                tag="HORAIRE-INCORRECT"; reject=true;
                            } else {good=true; mime="application/x-mpegURL"; tag="OK-HLS";}
                        } else if ((bytes[0]&255)==0x47) {
                            good=true; mime="video/mp2t"; tag="OK-TS";
                        } else if ((bytes.length>8 && new String(bytes,4,4,StandardCharsets.ISO_8859_1).equals("ftyp")) ||
                                   ((bytes[0]&255)==0x1a && (bytes[1]&255)==0x45 && (bytes[2]&255)==0xdf && (bytes[3]&255)==0xa3)) {
                            good=true; tag="OK-CONTENEUR";
                        } else tag="FORMAT-A-CONFIRMER-PAR-LECTEUR";
                    }
                } catch (Exception e) {tag="RESEAU-"+e.getClass().getSimpleName();}
                log.append("Candidat ").append(i+1).append(" / ")
                    .append(ua.startsWith("VLC")?"VLC":"IPTV").append(" : ").append(tag).append('\n');
                if (reject) rejected.add(url);
                if (good) {
                    rejected.remove(url);
                    return result(url,mime,ua,log.toString(),urls,rejected);
                }
            }
        }
        // Une sonde n'est pas une lecture : tokens, quota de connexions, delai et
        // format incomplet ne prouvent pas l'absence d'archive. Essai reel Media3.
        // En revanche, jamais de reprise sur une reponse identifiee comme du direct,
        // un mauvais programme, une page d'erreur ou un lien inexistant.
        log.append("Essai lecteur des variantes d'archive restantes, sans basculement vers le direct.\n");
        return result("","",agents[0],log.toString(),urls,rejected);
    }
}
