package com.kzplayer.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * v372 : TELECHARGEUR INTERNE KZ PLAYER.
 *
 * Pourquoi : le gestionnaire de telechargement d Android (DownloadManager) ne
 * supporte pas les serveurs IPTV. Resultat en v371 : "En attente de connexion",
 * "Echec du telechargement" ou "En attente pour reessayer" en boucle.
 *
 * Ici on telecharge nous-memes, avec :
 *  - reprise exacte a l octet ou on s est arrete (en-tete Range) au lieu de tout refaire,
 *  - relance illimitee et silencieuse sur coupure reseau,
 *  - lien du serveur refabrique automatiquement quand le jeton expire (Stalker),
 *  - service au premier plan : la copie continue quand on quitte l ecran.
 */
class DownloadService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        creerCanal()
        majNotif()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val fic = intent?.getStringExtra("file").orEmpty()
        val url = intent?.getStringExtra("url").orEmpty()
        val titre = intent?.getStringExtra("title").orEmpty()
        val cmd = intent?.getStringExtra("cmd").orEmpty()
        if (fic.isNotBlank() && url.isNotBlank()) {
            annules.remove(fic)
            val p = jobs[fic]
            if (p == null) {
                jobs[fic] = Prog(fic, titre.ifBlank { fic }, 0L, 0L, ST_PENDING, url, cmd)
                pool.execute { travailler(fic) }
            } else {
                // Deja connu : on remet a jour le lien et on relance si besoin.
                p.url = url
                if (cmd.isNotBlank()) p.cmd = cmd
                if (p.status != ST_RUNNING) {
                    p.status = ST_PENDING
                    pool.execute { travailler(fic) }
                }
            }
        }
        majNotif()
        return START_STICKY
    }

    // ----------------------------------------------------------- notification

    private fun creerCanal() {
        if (Build.VERSION.SDK_INT < 26) return
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (nm.getNotificationChannel(CANAL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CANAL, "Telechargements", NotificationManager.IMPORTANCE_LOW)
                )
            }
        } catch (e: Throwable) {}
    }

    private fun texteNotif(): String {
        val actifs = jobs.values.filter { it.status == ST_RUNNING || it.status == ST_PENDING || it.status == ST_PAUSED }
        if (actifs.isEmpty()) return "Telechargements termines"
        val p = actifs.first()
        val pc = if (p.total > 0L) (p.done * 100L / p.total).toInt() else 0
        val reste = if (actifs.size > 1) "  (+" + (actifs.size - 1) + ")" else ""
        return p.title + "  " + pc + " %" + reste
    }

    private fun majNotif() {
        try {
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CANAL)
            else @Suppress("DEPRECATION") Notification.Builder(this)
            b.setContentTitle("KZ Player")
            b.setContentText(texteNotif())
            b.setSmallIcon(android.R.drawable.stat_sys_download)
            b.setOngoing(true)
            val n = b.build()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Throwable) {
            // Notification refusee (Android 13+ sans permission) : le telechargement
            // continue quand meme, on ne plante jamais pour ca.
        }
    }

    private fun finirSiPlusRien() {
        val actifs = jobs.values.any { it.status == ST_RUNNING || it.status == ST_PENDING }
        if (!actifs) {
            try { stopForeground(true) } catch (e: Throwable) {}
            try { stopSelf() } catch (e: Throwable) {}
        } else {
            majNotif()
        }
    }

    // ------------------------------------------------------------ travail

    private fun dossier(): File? =
        try { getExternalFilesDir(Environment.DIRECTORY_MOVIES) } catch (e: Throwable) { null }

    private fun travailler(fic: String) {
        val p = jobs[fic] ?: return
        val dir = dossier()
        if (dir == null) { p.status = ST_FAILED; finirSiPlusRien(); return }
        if (!dir.exists()) try { dir.mkdirs() } catch (e: Throwable) {}
        val f = File(dir, fic)
        p.status = ST_RUNNING
        var essais = 0
        var derniereNotif = 0L
        while (essais < 5000) {
            if (annules.contains(fic)) { jobs.remove(fic); finirSiPlusRien(); return }
            essais++
            val deja = if (f.exists()) f.length() else 0L
            p.done = deja
            try {
                val rb = Request.Builder().url(p.url)
                    .header("User-Agent", Config.USER_AGENT)
                    .header("Accept", "*/*")
                if (deja > 0L) rb.header("Range", "bytes=" + deja + "-")
                val rep = client.newCall(rb.build()).execute()
                val code = rep.code
                if (code == 416) {
                    // Le serveur dit qu il n y a plus rien apres : c est fini.
                    rep.close()
                    p.total = deja
                    p.status = ST_SUCCESS
                    finirSiPlusRien()
                    return
                }
                if (code == 401 || code == 403 || code == 404 || code == 410 || code >= 500) {
                    rep.close()
                    // Jeton expire cote serveur : on refabrique un lien tout neuf.
                    if (!rafraichirLien(p)) {
                        p.status = ST_PAUSED
                        majNotif()
                        dormir(5000L)
                        continue
                    }
                    continue
                }
                val corps = rep.body
                if (corps == null) { rep.close(); p.status = ST_PAUSED; dormir(3000L); continue }
                val longueur = corps.contentLength()
                p.total = if (longueur > 0L) deja + longueur else 0L
                val reprise = code == 206 || deja == 0L
                val sortie = java.io.FileOutputStream(f, reprise && deja > 0L)
                if (!reprise) p.done = 0L
                var recu = if (reprise) deja else 0L
                val buf = ByteArray(131072)
                corps.byteStream().use { entree ->
                    sortie.use { out ->
                        while (true) {
                            if (annules.contains(fic)) {
                                rep.close()
                                try { f.delete() } catch (e: Throwable) {}
                                jobs.remove(fic)
                                finirSiPlusRien()
                                return
                            }
                            val n = entree.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            recu += n
                            p.done = recu
                            p.status = ST_RUNNING
                            val maintenant = System.currentTimeMillis()
                            if (maintenant - derniereNotif > 2000L) {
                                derniereNotif = maintenant
                                majNotif()
                            }
                        }
                    }
                }
                rep.close()
                // Fin de flux : termine si on a tout recu, ou si le serveur
                // n annonce pas de taille (il ferme quand c est fini).
                if (p.total <= 0L || p.done >= p.total) {
                    p.total = if (p.total > 0L) p.total else p.done
                    p.status = ST_SUCCESS
                    finirSiPlusRien()
                    return
                }
                // Coupure au milieu : on repart a l octet suivant, sans tout refaire.
                p.status = ST_PAUSED
                majNotif()
                dormir(2000L)
            } catch (e: Throwable) {
                // Coupure reseau / timeout : on reprend au meme endroit.
                p.status = ST_PAUSED
                majNotif()
                dormir(4000L)
            }
        }
        p.status = ST_FAILED
        finirSiPlusRien()
    }

    private fun dormir(ms: Long) {
        try { Thread.sleep(ms) } catch (e: InterruptedException) {}
    }

    /** Redemande un lien frais au serveur (Stalker) quand le jeton a expire. */
    private fun rafraichirLien(p: Prog): Boolean {
        val pl = Session.current ?: return false
        val cmd = p.cmd
        if (cmd.isBlank()) return false
        val neuf = try {
            runBlocking { Api.stalkerLink(pl, cmd, "movie") }
        } catch (e: Throwable) { null }
        if (neuf.isNullOrBlank() || neuf == p.url) return false
        p.url = neuf
        return true
    }

    companion object {
        private const val CANAL = "kz_dl"
        private const val NOTIF_ID = 4711

        // Memes valeurs que le gestionnaire Android, pour ne rien changer a l affichage.
        const val ST_PENDING = 1
        const val ST_RUNNING = 2
        const val ST_PAUSED = 4
        const val ST_SUCCESS = 8
        const val ST_FAILED = 16

        class Prog(
            val fileName: String,
            @Volatile var title: String,
            @Volatile var done: Long,
            @Volatile var total: Long,
            @Volatile var status: Int,
            @Volatile var url: String,
            @Volatile var cmd: String
        )

        val jobs = ConcurrentHashMap<String, Prog>()
        private val annules = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

        // Deux telechargements en parallele au maximum : plus, ca sature les serveurs IPTV.
        private val pool = Executors.newFixedThreadPool(2)

        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }

        fun demarrer(ctx: Context, fileName: String, title: String, url: String, cmd: String): Boolean {
            annules.remove(fileName)
            jobs[fileName] = Prog(fileName, title.ifBlank { fileName }, 0L, 0L, ST_PENDING, url, cmd)
            val i = Intent(ctx, DownloadService::class.java)
                .putExtra("file", fileName).putExtra("title", title).putExtra("url", url).putExtra("cmd", cmd)
            var started = false
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
                started = true
            } catch (e: Throwable) {
                try { ctx.startService(i); started = true } catch (e2: Throwable) {}
            }
            if (!started) jobs.remove(fileName)
            return started
        }

        fun annuler(fileName: String) {
            annules.add(fileName)
            jobs.remove(fileName)
        }
    }
}
