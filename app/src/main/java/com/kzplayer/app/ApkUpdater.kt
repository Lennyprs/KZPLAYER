package com.kzplayer.app

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Protocol
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

// v145 : telechargement + installation en direct des mises a jour APK.
//
// Pourquoi : sur beaucoup d'Android TV / boitiers, ACTION_VIEW ouvre un navigateur
// (souvent absent) ou un gestionnaire qui refuse le .apk. En telechargeant
// l'APK a l'interieur de l'app puis en lancant l'installeur systeme via un
// FileProvider (ACTION_INSTALL_PACKAGE), l'installation passe sur tous les
// appareils, y compris les boitiers TV sans navigateur.
//
// L'utilisateur doit autoriser "Sources inconnues" pour KZ Player la premiere fois :
// on l'y guide automatiquement via ACTION_MANAGE_UNKNOWN_APP_SOURCES (Android O+).
object ApkUpdater {

    // Client OkHttp permissif (memes tolerances SSL que Api.kt : GitHub Releases
    // redirige vers *.githubusercontent.com, no probleme, mais certains miroirs
    // clients ont des certs incomplets).
    private val client: OkHttpClient by lazy { buildClient() }

    private fun buildClient(): OkHttpClient {
        return try {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            val ssl = SSLContext.getInstance("TLS")
            ssl.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
            OkHttpClient.Builder()
                .sslSocketFactory(ssl.socketFactory, trustAll)
                .hostnameVerifier(HostnameVerifier { _, _ -> true })
                .followRedirects(true)
                .followSslRedirects(true)
                .protocols(listOf(Protocol.HTTP_1_1))
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .build()
        } catch (e: Exception) {
            OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .protocols(listOf(Protocol.HTTP_1_1))
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build()
        }
    }

    private var currentJob: Job? = null

    /**
     * Point d'entree : verifie l'autorisation "Sources inconnues", puis telecharge
     * l'APK depuis [downloadUrl] avec dialogue de progression et lance l'installeur.
     */
    fun install(activity: Activity, downloadUrl: String, latestLabel: String = "") {
        if (downloadUrl.isBlank()) {
            Toast.makeText(activity, "Lien de t\u00e9l\u00e9chargement introuvable", Toast.LENGTH_LONG).show()
            return
        }
        if (!canInstallUnknownApps(activity)) {
            promptUnknownSources(activity, downloadUrl, latestLabel)
            return
        }
        startDownload(activity, downloadUrl, latestLabel)
    }

    private fun canInstallUnknownApps(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.packageManager.canRequestPackageInstalls()
        } else true
    }

    private fun promptUnknownSources(activity: Activity, downloadUrl: String, latestLabel: String) {
        AlertDialog.Builder(activity)
            .setTitle("Autoriser l'installation")
            .setMessage("Pour installer la mise \u00e0 jour, autorise KZ Player \u00e0 installer depuis des sources inconnues, puis reviens dans l'app et relance la mise \u00e0 jour.")
            .setPositiveButton("Ouvrir les param\u00e8tres") { _, _ ->
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val i = Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + activity.packageName)
                        )
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        activity.startActivity(i)
                    } else {
                        val i = Intent(Settings.ACTION_SECURITY_SETTINGS)
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        activity.startActivity(i)
                    }
                } catch (e: Exception) {
                    // Certains TV : ouvre directement le lien en dernier recours.
                    try {
                        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)))
                    } catch (_: Exception) {
                        Toast.makeText(activity, "Impossible d'ouvrir les param\u00e8tres", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun startDownload(activity: Activity, downloadUrl: String, latestLabel: String) {
        currentJob?.cancel()
        val title = if (latestLabel.isNotBlank()) "T\u00e9l\u00e9chargement $latestLabel" else "T\u00e9l\u00e9chargement de la mise \u00e0 jour"
        @Suppress("DEPRECATION")
        val dlg = ProgressDialog(activity).apply {
            setTitle(title)
            setMessage("D\u00e9marrage\u2026")
            setCancelable(true)
            setCanceledOnTouchOutside(false)
            isIndeterminate = false
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            setOnCancelListener { currentJob?.cancel() }
            show()
        }

        currentJob = CoroutineScope(Dispatchers.Main).launch {
            val target = try {
                withContext(Dispatchers.IO) { downloadToCache(activity, downloadUrl) { pct, downloaded, total ->
                    activity.runOnUiThread {
                        if (dlg.isShowing) {
                            if (total > 0) {
                                dlg.progress = pct
                                dlg.setMessage("${formatMb(downloaded)} / ${formatMb(total)}")
                            } else {
                                dlg.isIndeterminate = true
                                dlg.setMessage(formatMb(downloaded))
                            }
                        }
                    }
                } }
            } catch (e: Exception) {
                if (dlg.isShowing) try { dlg.dismiss() } catch (_: Exception) {}
                val msg = when {
                    e.message?.contains("cancel", true) == true -> null
                    else -> "T\u00e9l\u00e9chargement impossible : " + (e.message ?: e.javaClass.simpleName)
                }
                if (msg != null) {
                    AlertDialog.Builder(activity)
                        .setTitle("Erreur de t\u00e9l\u00e9chargement")
                        .setMessage(msg + "\n\nTu peux r\u00e9essayer, ou t\u00e9l\u00e9charger l'APK depuis un autre appareil.")
                        .setPositiveButton("OK", null)
                        .setNeutralButton("Ouvrir le lien") { _, _ ->
                            try { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))) } catch (_: Exception) {}
                        }
                        .show()
                }
                return@launch
            }
            if (dlg.isShowing) try { dlg.dismiss() } catch (_: Exception) {}
            launchInstaller(activity, target)
        }
    }

    private fun formatMb(bytes: Long): String {
        if (bytes <= 0) return "0 Mo"
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        return String.format("%.1f Mo", mb)
    }

    private fun downloadToCache(
        ctx: Context,
        url: String,
        onProgress: (pct: Int, downloaded: Long, total: Long) -> Unit
    ): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        // Nettoyage : on ne garde qu'un seul APK a la fois
        dir.listFiles()?.forEach { if (it.isFile) it.delete() }
        val fileName = "kzplayer-update-" + System.currentTimeMillis() + ".apk"
        val out = File(dir, fileName)

        val req = Request.Builder()
            .url(url)
            .header("User-Agent", Config.USER_AGENT)
            .header("Accept", "application/vnd.android.package-archive, application/octet-stream, */*")
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP " + resp.code)
            val body = resp.body ?: throw RuntimeException("R\u00e9ponse vide")
            val total = body.contentLength()
            body.byteStream().use { input ->
                FileOutputStream(out).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var downloaded = 0L
                    var lastPct = -1
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        downloaded += n
                        val now = System.currentTimeMillis()
                        if (total > 0) {
                            val pct = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct && now - lastReport > 80) {
                                lastPct = pct; lastReport = now
                                onProgress(pct, downloaded, total)
                            }
                        } else if (now - lastReport > 250) {
                            lastReport = now
                            onProgress(0, downloaded, 0)
                        }
                    }
                    output.flush()
                    onProgress(100, downloaded, if (total > 0) total else downloaded)
                }
            }
        }

        if (out.length() <= 0) throw RuntimeException("Fichier t\u00e9l\u00e9charg\u00e9 vide")
        return out
    }

    private fun launchInstaller(activity: Activity, apk: File) {
        try {
            BrandIdentity.verify(activity, apk)
            val authority = activity.packageName + ".fileprovider"
            val uri: Uri = FileProvider.getUriForFile(activity, authority, apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            AlertDialog.Builder(activity)
                .setTitle("Installation impossible")
                .setMessage("L'installeur syst\u00e8me a refus\u00e9 l'APK : " + (e.message ?: e.javaClass.simpleName))
                .setPositiveButton("OK", null)
                .show()
        }
    }
}
