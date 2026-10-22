package com.kzplayer.app;

import android.net.Uri;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** V119 : validation sur la connexion de lecture, sans GET de sonde supplementaire. */
public final class ReplayDataSource implements DataSource {
    public static final class RejectedArchive extends IOException {
        public final String reason;
        RejectedArchive(String reason) { super(reason); this.reason=reason; }
    }
    public static final class Factory implements DataSource.Factory {
        private final DataSource.Factory upstream;
        private final Set<String> roots;
        private final long startMs, endMs;
        private final boolean declaredCatchup;
        public Factory(DataSource.Factory upstream, List<String> roots, long startMs, long endMs) {
            this(upstream, roots, startMs, endMs, false);
        }
        public Factory(DataSource.Factory upstream, List<String> roots, long startMs, long endMs, boolean declaredCatchup) {
            this.declaredCatchup=declaredCatchup;
            this.upstream=upstream; this.roots=new HashSet<>(roots);
            this.startMs=startMs; this.endMs=endMs;
        }
        @Override public DataSource createDataSource() {
            return new ReplayDataSource(upstream.createDataSource(),roots,startMs,endMs,declaredCatchup);
        }
    }
    private final DataSource upstream;
    private final Set<String> roots;
    private final long startMs, endMs;
    private final boolean declaredCatchup;
    private byte[] prefix=new byte[0];
    private int prefixOffset=0;
    private boolean opened=false;
    private ReplayDataSource(DataSource upstream,Set<String> roots,long startMs,long endMs,boolean declaredCatchup) {
        this.upstream=upstream;this.roots=roots;this.startMs=startMs;this.endMs=endMs;this.declaredCatchup=declaredCatchup;
    }
    @Override public void addTransferListener(TransferListener listener) {
        upstream.addTransferListener(listener);
    }
    @Override public long open(DataSpec spec) throws IOException {
        prefix=new byte[0];prefixOffset=0;
        long wait=Math.max(ReplayRateLimit.remainingMs(spec.uri.toString()),ReplayRateLimit.remainingMs(roots));
        if(wait>0L)throw new ReplayRateLimit.Limited(wait);
        opened=true;
        try {
            long length=upstream.open(spec);
            // Les segments HLS ne sont pas des manifestes : ne pas les lire/decaler.
            if (!roots.contains(spec.uri.toString())) return length;
            Uri actual=upstream.getUri();
            String path=actual==null?"":actual.getEncodedPath();
            if (!declaredCatchup && path!=null && path.toLowerCase(Locale.US).contains("/live/"))
                throw new RejectedArchive("REDIRECTION_VERS_DIRECT");
            String type="";
            for (Map.Entry<String,List<String>> h:upstream.getResponseHeaders().entrySet())
                if (h.getKey()!=null && h.getKey().equalsIgnoreCase("Content-Type"))
                    type=(h.getValue().isEmpty()?"":h.getValue().get(0)).toLowerCase(Locale.US);
            if (type.contains("html") || type.contains("json") || type.contains("xml"))
                throw new RejectedArchive("REPONSE_NON_VIDEO");
            // Un seek TS/MP4 peut reprendre au milieu d'un paquet : ces octets
            // ne sont pas un en-tete de fichier et ne doivent pas etre rejetes.
            if (spec.position != 0L) return length;
            // Une courte signature sur la MEME connexion reconnait aussi le HLS
            // servi par PHP sans Content-Type. Les octets sont ensuite restitues.
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            byte[] buf=new byte[1024];
            while (out.size()<16) {
                int n=upstream.read(buf,0,Math.min(buf.length,16-out.size()));
                if (n==-1) break;
                if (n==0) throw new IOException("ARCHIVE_SANS_PROGRESSION");
                out.write(buf,0,n);
            }
            String first=new String(out.toByteArray(),StandardCharsets.UTF_8).trim();
            if (first.startsWith("\uFEFF")) first=first.substring(1).trim();
            if (first.startsWith("<") || first.startsWith("{") || first.startsWith("["))
                throw new RejectedArchive("REPONSE_NON_VIDEO");
            String requestPath=spec.uri.getPath();
            boolean manifest=type.contains("mpegurl") || first.startsWith("#EXTM3U") ||
                (requestPath!=null && requestPath.toLowerCase(Locale.US).endsWith(".m3u8"));
            if (manifest) {
                while (out.size()<8192) {
                    int n=upstream.read(buf,0,Math.min(buf.length,8192-out.size()));
                    if (n==-1) break;
                    if (n==0) throw new IOException("ARCHIVE_SANS_PROGRESSION");
                    out.write(buf,0,n);
                }
                String body=new String(out.toByteArray(),StandardCharsets.UTF_8).trim();
                if (body.startsWith("\uFEFF")) body=body.substring(1).trim();
                if (body.startsWith("#EXTM3U") && ReplayArchiveGuard.isOutsideProgram(body,startMs,endMs))
                    throw new RejectedArchive("ARCHIVE_HORS_HORAIRE");
            }
            prefix=out.toByteArray();
            return length;
        } catch (IOException | RuntimeException e) {
            ReplayRateLimit.record(spec.uri.toString(),e);
            for(String root:roots)ReplayRateLimit.record(root,e);
            try { close(); } catch (IOException ignored) { }
            throw e;
        }
    }
    @Override public int read(byte[] buffer,int offset,int length) throws IOException {
        if (length==0) return 0;
        if (prefixOffset<prefix.length) {
            int n=Math.min(length,prefix.length-prefixOffset);
            System.arraycopy(prefix,prefixOffset,buffer,offset,n);prefixOffset+=n;return n;
        }
        return upstream.read(buffer,offset,length);
    }
    @Override public Uri getUri() { return upstream.getUri(); }
    @Override public Map<String,List<String>> getResponseHeaders() { return upstream.getResponseHeaders(); }
    @Override public void close() throws IOException {
        prefix=new byte[0];prefixOffset=0;
        if(opened){opened=false;upstream.close();}
    }
}
