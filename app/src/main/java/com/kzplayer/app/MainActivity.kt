package com.kzplayer.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : BaseActivity() {
    private lateinit var codeTv: TextView
    private lateinit var statusTv: TextView
    private lateinit var reloadBtn: Button
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SecurityChecks.enforce(this)) return
        setContentView(R.layout.activity_main)

        codeTv = findViewById(R.id.codeTv)
        statusTv = findViewById(R.id.statusTv)
        reloadBtn = findViewById(R.id.reloadBtn)
        progress = findViewById(R.id.progress)

        codeTv.text = deviceCode()
        reloadBtn.setOnClickListener { checkLicense() }
        checkLicense()
    }

    private fun deviceId(): String = DeviceIdentity.stableId(this)

    private fun deviceCode(): String = DeviceIdentity.licenseCode(this)

    private fun checkLicense() {
        setLoading(true)
        statusTv.text = "V\u00e9rification de la licence..."
        lifecycleScope.launch {
            try {
                val res = Api.checkLicense(
                    deviceId = deviceId(),
                    deviceCode = deviceCode(),
                    deviceName = Build.MODEL ?: "Android TV",
                    appVersion = "1.0"
                )
                if (res.ok && res.active) {
                    LicenseGuard.rememberOk(this@MainActivity, res.expiration)
                    Session.playlists = LocalPlaylists.merge(res.playlists)
                    Session.expiration = res.expiration
                    val selectedId = Session.current?.id
                    Session.current = Session.playlists.firstOrNull { it.id == selectedId }
                        ?: Session.playlists.firstOrNull()
                    SessionCache.save(this@MainActivity) // v375 : cache local des serveurs
                    startActivity(Intent(this@MainActivity, ThemePref.homeClass(this@MainActivity)))
                } else if (LicenseGuard.wasRecentlyActive(this@MainActivity)) {
                    // Fenetre de grace : licence vue active il y a moins de 24h.
                    // On ne bloque pas l'utilisateur sur un hoquet backend / cold start.
                    Session.expiration = LicenseGuard.lastExpiration(this@MainActivity) ?: res.expiration
                    startActivity(Intent(this@MainActivity, ThemePref.homeClass(this@MainActivity)))
                } else {
                    codeTv.text = res.deviceCode.ifBlank { deviceCode() }
                    statusTv.text = res.message.ifBlank {
                        "Licence en attente d\u2019activation. Ouvre le panel admin pour activer cet appareil."
                    }
                }
            } catch (e: Exception) {
                statusTv.text = "Erreur de v\u00e9rification : ${e.message}"
            }
            setLoading(false)
        }
    }

    private fun setLoading(b: Boolean) {
        progress.visibility = if (b) View.VISIBLE else View.GONE
        reloadBtn.isEnabled = !b
    }
}
