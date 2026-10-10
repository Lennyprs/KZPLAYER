package com.kzplayer.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.appcompat.app.AlertDialog

object CrashDiagnostics {
    private const val PREFS = "kz_crash_diagnostic"
    private const val REPORT = "pending"
    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try { record(app, error) } catch (_: Throwable) {}
            // Garder le traitement Android normal ; aucun redemarrage automatique.
            if (previous != null) previous.uncaughtException(thread, error)
            else android.os.Process.killProcess(android.os.Process.myPid())
        }
    }
    fun record(ctx: Context, error: Throwable) {
        val text = StringBuilder("KZ Player 3.4.0\n")
            .append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
            .append(" / Android ").append(Build.VERSION.RELEASE).append("\n")
        var cause: Throwable? = error
        repeat(4) {
            val current = cause ?: return@repeat
            // Les messages d'erreur peuvent contenir des liens avec mot de passe.
            // Ne conserver que le type et les positions dans le code.
            text.append(current.javaClass.name).append("\n")
            current.stackTrace.take(12).forEach { frame ->
                text.append("  ").append(frame.className).append(".")
                    .append(frame.methodName).append(":").append(frame.lineNumber).append("\n")
            }
            cause = current.cause?.takeIf { it !== current }
        }
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(REPORT, text.toString()).commit()
    }
    fun showPending(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val text = prefs.getString(REPORT, null) ?: return
        AlertDialog.Builder(activity)
            .setTitle("Rapport d'erreur KZ Player")
            .setMessage("Une erreur a ete enregistree. Ce rapport ne contient pas les identifiants IPTV.\n\n" + text)
            .setPositiveButton("Copier") { _, _ ->
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Diagnostic KZ Player", text))
                prefs.edit().remove(REPORT).apply()
            }
            .setNegativeButton("Fermer") { _, _ -> prefs.edit().remove(REPORT).apply() }
            .show()
    }
}
