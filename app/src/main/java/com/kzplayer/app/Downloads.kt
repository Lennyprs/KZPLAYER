package com.kzplayer.app

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// v359 : telechargement des films et episodes via le gestionnaire de
// telechargement d Android. La copie continue meme si on quitte l ecran.
// Le fichier est range dans le dossier Films de l application.
object Downloads {
    private fun safeName(title: String, url: String): String {
        var base = title.trim()
        if (base.isBlank()) base = "video"
        base = base.replace(Regex("[^A-Za-z0-9 ._-]"), " ").replace(Regex(" +"), " ").trim().take(80)
        val clean = url.substringBefore("?")
        val ext = when {
            clean.endsWith(".mkv", true) -> "mkv"
            clean.endsWith(".avi", true) -> "avi"
            clean.endsWith(".ts", true) -> "ts"
            else -> "mp4"
        }
        return base + "." + ext
    }

    private fun toast(ctx: Context, msg: String) {
        try { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() } catch (e: Exception) {}
    }

    // v359 : telechargement direct depuis une fiche (film ou episode de serie).
    // Resout d abord le lien du serveur (Stalker) puis lance le telechargement.
    fun requestItem(ctx: Context, item: Item, notify: Boolean = true) {
        val direct = item.directUrl
        if (!direct.isNullOrBlank()) {
            val msg = enqueue(ctx, item.name, direct, item.cmd ?: "")
            if (notify) toast(ctx, msg)
            return
        }
        val pl = Session.current
        val cmd = item.cmd
        if (pl == null || cmd.isNullOrBlank()) {
            toast(ctx, "Lien de t\u00e9l\u00e9chargement introuvable pour ce titre.")
            return
        }
        if (notify) toast(ctx, "Pr\u00e9paration du t\u00e9l\u00e9chargement...")
        CoroutineScope(Dispatchers.Main).launch {
            val link = try { Api.stalkerLink(pl, cmd, "movie") } catch (e: Exception) { null }
            val msg = enqueue(ctx, item.name, link ?: "", cmd)
            if (notify) toast(ctx, msg)
        }
    }

    // v368 : dossier ou sont ranges les telechargements de l application.
    fun dir(ctx: Context): java.io.File? =
        try { ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) } catch (e: Exception) { null }

    // v368 : liste des fichiers telecharges, les plus recents en premier.
    fun list(ctx: Context): List<java.io.File> {
        val d = dir(ctx) ?: return emptyList()
        val all = try { d.listFiles() } catch (e: Exception) { null } ?: return emptyList()
        return all.filter { it.isFile && it.length() > 0L }
            .sortedByDescending { it.lastModified() }
    }

    // v368 : suppression d un titre telecharge.
    fun remove(f: java.io.File): Boolean = try { f.delete() } catch (e: Exception) { false }

    // v370 : suppression COMPLETE d un titre.
    // Avant, seul le fichier etait efface : le gestionnaire Android gardait la
    // tache et reecrivait le fichier (le film supprime revenait tout seul).
    // Ici on annule d abord toutes les taches liees a ce fichier, puis on efface.
    fun removeAll(ctx: Context, f: java.io.File): Boolean {
        val nom = f.name
        DownloadService.annuler(nom)
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        if (dm != null) {
            for (t in tasks(ctx)) {
                if (t.fileName == nom || t.name == nom) {
                    try { dm.remove(t.id) } catch (e: Exception) {}
                }
            }
        }
        // Le gestionnaire peut avoir deja efface le fichier : c est bien aussi.
        if (!f.exists()) return true
        return remove(f)
    }

    // v370 : annulation d une tache ET nettoyage du fichier partiel restant.
    fun cancelTask(ctx: Context, t: Task): Boolean {
        val ok = if (t.internal) { DownloadService.annuler(t.fileName); true } else cancel(ctx, t.id)
        val d = dir(ctx)
        if (d != null && t.fileName.isNotBlank()) {
            val f = java.io.File(d, t.fileName)
            if (f.exists()) remove(f)
        }
        return ok
    }

    // v368 : taille lisible (Mo / Go).
    fun human(bytes: Long): String {
        val mo = bytes.toDouble() / (1024.0 * 1024.0)
        if (mo >= 1024.0) return String.format(Locale.US, "%.2f Go", mo / 1024.0)
        return String.format(Locale.US, "%.0f Mo", mo)
    }

    fun totalSize(ctx: Context): Long = list(ctx).sumOf { it.length() }

    // v369 : un telechargement en cours (gestionnaire Android).
    class Task(
        val id: Long,
        val name: String,
        val status: Int,
        val done: Long,
        val total: Long,
        val fileName: String,
        val internal: Boolean = false
    )

    // v369 : liste des telechargements connus du gestionnaire Android.
    fun tasks(ctx: Context): List<Task> {
        val out = ArrayList<Task>()
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        if (dm != null) try {
            val c = dm.query(DownloadManager.Query())
            c?.use { cur ->
                while (cur.moveToNext()) {
                    val id = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                    val st = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val done = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val tot = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val uri = cur.getString(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)) ?: ""
                    var nom = cur.getString(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)) ?: ""
                    val fic = Uri.decode(uri.substringAfterLast(chr47()))
                    if (nom.isBlank()) nom = fic
                    out.add(Task(id, nom, st, done, tot, fic, false))
                }
            }
        } catch (e: Exception) {}
        val known = out.map { it.fileName }.toHashSet()
        for (job in DownloadService.jobs.values) {
            if (known.contains(job.fileName)) continue
            val fakeId = -kotlin.math.abs(job.fileName.hashCode().toLong()) - 1L
            out.add(Task(fakeId, job.title, job.status, job.done, job.total, job.fileName, true))
        }
        return out
    }

    private fun chr47(): Char = 47.toChar()

    // Telechargements pas encore termines (en attente, en cours, en pause, echoues).
    fun pending(ctx: Context): List<Task> = tasks(ctx).filter {
        it.status == DownloadManager.STATUS_PENDING ||
            it.status == DownloadManager.STATUS_RUNNING ||
            it.status == DownloadManager.STATUS_PAUSED ||
            it.status == DownloadManager.STATUS_FAILED
    }

    fun percent(t: Task): Int {
        if (t.total <= 0L) return 0
        val p = (t.done.toDouble() * 100.0 / t.total.toDouble()).toInt()
        return if (p < 0) 0 else if (p > 100) 100 else p
    }

    // ---------------------------------------------------------------------
    // v372 : memoire de la source d un telechargement + relance automatique.
    // Cause du bug : sur un serveur Stalker/Xtream le lien de telechargement
    // contient un jeton qui expire ; au bout d un moment le gestionnaire Android
    // recoit une erreur HTTP et affiche "Echec du telechargement", ou reste bloque
    // sur "En attente de connexion" quand la connexion est vue comme limitee.
    // On memorise donc de quoi refaire un lien tout neuf et on relance tout seul.
    // ---------------------------------------------------------------------
    private const val PREF = "kz_dl_src"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun rememberSource(ctx: Context, fileName: String, title: String, url: String, cmd: String) {
        if (fileName.isBlank()) return
        try {
            prefs(ctx).edit()
                .putString("t_" + fileName, title)
                .putString("u_" + fileName, url)
                .putString("c_" + fileName, cmd)
                .apply()
        } catch (e: Exception) {}
    }

    private fun retryCount(ctx: Context, fileName: String): Int =
        try { prefs(ctx).getInt("r_" + fileName, 0) } catch (e: Exception) { 0 }

    private fun bumpRetry(ctx: Context, fileName: String) {
        try {
            prefs(ctx).edit().putInt("r_" + fileName, retryCount(ctx, fileName) + 1).apply()
        } catch (e: Exception) {}
    }

    /**
     * Relance un telechargement echoue : on supprime la tache ratee, on redemande
     * un lien FRAIS au serveur quand c est possible (jeton expire) puis on relance.
     * Le fichier repart proprement, sans intervention de l utilisateur.
     */
    fun retryTask(ctx: Context, t: Task, notify: Boolean = false) {
        val fic = t.fileName
        val p = try { prefs(ctx) } catch (e: Exception) { null }
        val titre = p?.getString("t_" + fic, null) ?: t.name
        val ancienUrl = p?.getString("u_" + fic, null) ?: ""
        val cmd = p?.getString("c_" + fic, null) ?: ""
        bumpRetry(ctx, fic)
        // On enleve la tache ratee et le fichier partiel avant de repartir.
        try { cancelTask(ctx, t) } catch (e: Exception) {}
        val pl = Session.current
        if (cmd.isNotBlank() && pl != null) {
            CoroutineScope(Dispatchers.Main).launch {
                val frais = try { Api.stalkerLink(pl, cmd, "movie") } catch (e: Exception) { null }
                val url = if (!frais.isNullOrBlank()) frais else ancienUrl
                val msg = if (t.internal) enqueueInternal(ctx, titre, url, cmd) else enqueue(ctx, titre, url, cmd)
                if (notify) toast(ctx, msg)
            }
            return
        }
        if (ancienUrl.isBlank()) {
            if (notify) toast(ctx, "Impossible de relancer ce t\u00e9l\u00e9chargement : relance-le depuis la fiche du titre.")
            return
        }
        val msg = if (t.internal) enqueueInternal(ctx, titre, ancienUrl, cmd) else enqueue(ctx, titre, ancienUrl, cmd)
        if (notify) toast(ctx, msg)
    }

    /**
     * Relance automatiquement les telechargements en echec (3 essais maximum par
     * titre, pour ne pas boucler a l infini sur un serveur vraiment mort).
     * Renvoie true si au moins une relance a ete lancee.
     */
    fun autoRetryFailed(ctx: Context): Boolean {
        var relance = false
        for (t in tasks(ctx)) {
            if (t.status != DownloadManager.STATUS_FAILED) continue
            if (retryCount(ctx, t.fileName) >= 3) continue
            retryTask(ctx, t, notify = false)
            relance = true
        }
        return relance
    }

    // Texte affiche sous le titre pendant le telechargement.
    fun statusText(t: Task): String {
        if (t.status == DownloadManager.STATUS_FAILED) return "\u00c9chec du t\u00e9l\u00e9chargement"
        if (t.status == DownloadManager.STATUS_PAUSED) return "En pause  \u2022  " + human(t.done)
        if (t.status == DownloadManager.STATUS_PENDING) return "En attente de d\u00e9marrage..."
        val tot = if (t.total > 0L) human(t.total) else "taille inconnue"
        return "T\u00e9l\u00e9chargement " + percent(t).toString() + " %  \u2022  " +
            human(t.done) + " / " + tot
    }

    // Annulation d un telechargement en cours (supprime aussi le fichier partiel).
    fun cancel(ctx: Context, id: Long): Boolean {
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return false
        return try { dm.remove(id) > 0 } catch (e: Exception) { false }
    }

    // Renvoie le message a afficher a l utilisateur.
    // cmd : commande du serveur (Stalker) qui permet de refabriquer un lien frais
    // si le jeton expire pendant le telechargement.
    fun enqueueInternal(ctx: Context, title: String, url: String, cmd: String = ""): String {
        if (url.isBlank()) return "Lien de telechargement introuvable."
        if (url.contains(".m3u8", true)) return "Format HLS non telechargeable sur ce serveur."
        return try {
            val name = safeName(title, url)
            rememberSource(ctx, name, if (title.isBlank()) name else title, url, cmd)
            val started = DownloadService.demarrer(ctx.applicationContext, name, if (title.isBlank()) name else title, url, cmd)
            if (started) "T\u00e9l\u00e9chargement lanc\u00e9 : " + name
            else "Impossible de demarrer le service de telechargement."
        } catch (e: Exception) { "Telechargement impossible : " + (e.message ?: "erreur") }
    }

    fun enqueue(ctx: Context, title: String, url: String, cmd: String = ""): String {
        if (url.isBlank()) return "Lien de t\u00e9l\u00e9chargement introuvable."
        if (url.contains(".m3u8")) return "Ce contenu est diffus\u00e9 en direct : t\u00e9l\u00e9chargement impossible."
        return try {
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            if (dm == null) return "T\u00e9l\u00e9chargement indisponible sur cet appareil."
            val name = safeName(title, url)
            val req = DownloadManager.Request(Uri.parse(url))
            req.addRequestHeader("User-Agent", Config.USER_AGENT)
            req.setTitle(if (title.isBlank()) name else title)
            req.setDescription("KZ Player")
            // v372 : correction du blocage "En attente de connexion".
            // Certains boitiers / telephones annoncent leur connexion comme limitee
            // ou en itinerance : le gestionnaire Android mettait alors le
            // telechargement en attente indefiniment. On autorise tout type de reseau.
            req.setAllowedOverRoaming(true)
            try { req.setAllowedOverMetered(true) } catch (e: Exception) {}
            try {
                req.setAllowedNetworkTypes(
                    DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE
                )
            } catch (e: Exception) {}
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                try { req.setRequiresCharging(false) } catch (e: Exception) {}
                try { req.setRequiresDeviceIdle(false) } catch (e: Exception) {}
            }
            // Notification visible pendant TOUT le telechargement : Android traite la
            // tache en priorite et ne l endort pas quand on quitte l ecran.
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            req.setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_MOVIES, name)
            dm.enqueue(req)
            // v372 : on garde de quoi relancer tout seul si le jeton du serveur expire.
            rememberSource(ctx, name, if (title.isBlank()) name else title, url, cmd)
            "T\u00e9l\u00e9chargement lanc\u00e9 : " + name +
                " - suis la progression dans Param\u00e8tres > T\u00e9l\u00e9chargement"
        } catch (e: Exception) {
            "T\u00e9l\u00e9chargement impossible : " + (e.message ?: "erreur")
        }
    }
}
