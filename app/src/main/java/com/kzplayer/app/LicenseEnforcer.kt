package com.kzplayer.app

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Contrôle de révocation commun au player officiel et à toutes les APK personnalisées.
 * Une réponse serveur explicite inactive/bloquée/expirée/supprimée coupe immédiatement
 * la session. Une panne réseau conserve l'accès uniquement pendant la grâce existante.
 */
object LicenseEnforcer {
    private const val CHECK_EVERY_MS = 60_000L
    private const val MIN_GAP_MS = 15_000L
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val checking = AtomicBoolean(false)
    @Volatile private var lastCheckMs = 0L
    private var resumed = WeakReference<Activity>(null)

    private val tick = object : Runnable {
        override fun run() {
            resumed.get()?.let { check(it) }
            if (resumed.get() != null) handler.postDelayed(this, CHECK_EVERY_MS)
        }
    }

    fun resume(activity: Activity) {
        if (!eligible(activity)) return
        resumed = WeakReference(activity)
        handler.removeCallbacks(tick)
        check(activity)
        handler.postDelayed(tick, CHECK_EVERY_MS)
    }

    fun pause(activity: Activity) {
        if (resumed.get() === activity) {
            resumed.clear()
            handler.removeCallbacks(tick)
        }
    }

    private fun eligible(activity: Activity): Boolean =
        activity !is MainActivity && activity !is PlaylistSetupActivity && !activity.isFinishing

    private fun check(activity: Activity) {
        if (!eligible(activity)) return
        val now = System.currentTimeMillis()
        if (now - lastCheckMs < MIN_GAP_MS) return
        if (!checking.compareAndSet(false, true)) return
        lastCheckMs = now
        scope.launch {
            try {
                val result = Api.checkLicense(
                    DeviceIdentity.stableId(activity),
                    DeviceIdentity.licenseCode(activity),
                    Build.MODEL ?: "Android TV",
                    BuildConfig.VERSION_NAME
                )
                if (result.ok && result.active) {
                    LicenseGuard.rememberOk(activity, result.expiration)
                } else if (result.ok) {
                    block(resumed.get() ?: activity, result.message)
                } else if (!LicenseGuard.wasRecentlyActive(activity)) {
                    block(resumed.get() ?: activity, "Vérification de licence requise.")
                }
            } catch (_: Exception) {
                if (!LicenseGuard.wasRecentlyActive(activity)) {
                    block(resumed.get() ?: activity, "Vérification de licence requise.")
                }
            } finally {
                checking.set(false)
            }
        }
    }

    fun block(activity: Activity, message: String = "") {
        LicenseGuard.revoke(activity)
        val intent = Intent(activity, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            if (message.isNotBlank()) putExtra("license_status_message", message)
        }
        activity.startActivity(intent)
        activity.finishAffinity()
    }
}
