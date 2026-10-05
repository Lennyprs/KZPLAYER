package com.kzplayer.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

// Ecran Parametres : menu avec sous-menus (Theme + Couleur + Liste de lecture +
// Recharger + Mise a jour). Le meme ecran est ouvert par les deux themes.
class SettingsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        // v391 : bouton pour quitter completement l application.
        findViewById<android.view.View>(R.id.exitMenu)?.setOnClickListener { confirmExit() }
        findViewById<View>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<View>(R.id.themeMenu).setOnClickListener {
            startActivity(Intent(this, ThemeActivity::class.java))
        }
        findViewById<View>(R.id.colorMenu).setOnClickListener {
            startActivity(Intent(this, ColorThemeActivity::class.java))
        }
        findViewById<View>(R.id.playlistMenu).setOnClickListener {
            startActivity(Intent(this, PlaylistSettingsActivity::class.java))
        }
        findViewById<View>(R.id.reloadMenu).setOnClickListener { reloadPlaylists() }
        // v359 : choix entre une seule liste active et toutes les listes en meme temps.
        findViewById<View>(R.id.multiListMenu).setOnClickListener { pickListMode() }
        refreshMultiListLabel()
        // v359 : vidage du cache de l application.
        findViewById<View>(R.id.cacheMenu).setOnClickListener { clearCache() }
        // v368 : ecran des telechargements (lecture hors ligne + suppression)
        findViewById<View>(R.id.downloadsMenu).setOnClickListener {
            startActivity(Intent(this, DownloadsActivity::class.java))
        }
        refreshCacheLabel()
        // Mise a jour : carte TOUJOURS visible (jamais masquee) qui affiche la
        // version installee et permet de verifier la derniere version publiee
        // depuis le panel admin.
        findViewById<View>(R.id.updateMenu).setOnClickListener { checkUpdate() }
        // v356 : acces administrateur cache. Un appui long affiche l empreinte de la
        // licence de cet appareil, et permet d activer le journal de diagnostic
        // uniquement si cette licence est declaree administrateur.
        findViewById<View>(R.id.updateMenu).setOnLongClickListener { showAdminDialog(); true }
        findViewById<TextView>(R.id.updateStateTv).text = "Version installee : ${currentVersion()}"
        findViewById<View>(R.id.panelCfMenu).setOnClickListener { editCfProxyUrl() }
        refreshCfProxyLabel()
        findViewById<View>(R.id.videoDecoderMenu).setOnClickListener { pickVideoDecoder() }
        refreshVideoDecoderLabel()
        // v149 : selection du DNS-over-HTTPS applique a toute l'app (portails + flux).
        findViewById<View>(R.id.dnsMenu).setOnClickListener { pickDns() }
        refreshDnsLabel()
        findViewById<View>(R.id.themeMenu).requestFocus()
    }

    // v354 : on affiche uniquement le numero de version (ex : 3.2.1),
    // sans le code de build entre parentheses.
    private fun currentVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
    } catch (e: Exception) { "1.0" }

    // v359 : libelle de la carte Mode des listes.
    private fun refreshMultiListLabel() {
        findViewById<TextView>(R.id.multiListStateTv).text = MultiListPref.label(this)
        val dl = Downloads.list(this)
        val enCours = Downloads.pending(this).size
        val base = if (dl.isEmpty()) "Aucun titre t\u00e9l\u00e9charg\u00e9"
            else dl.size.toString() + (if (dl.size > 1) " titres" else " titre") +
                "  \u2022  " + Downloads.human(Downloads.totalSize(this))
        findViewById<TextView>(R.id.downloadsStateTv).text =
            if (enCours > 0) base + "  \u2022  " + enCours.toString() + " en cours" else base
    }

    // Choix du mode : une seule liste (historique) ou toutes en meme temps.
    private fun pickListMode() {
        val labels = arrayOf<CharSequence>(
            "Une seule liste (liste active)",
            "Toutes les listes en m\u00eame temps"
        )
        val current = if (MultiListPref.isAll(this)) 1 else 0
        AlertDialog.Builder(this)
            .setTitle("Mode des listes")
            .setSingleChoiceItems(labels, current) { d, which ->
                MultiListPref.setAll(this, which == 1)
                refreshMultiListLabel()
                d.dismiss()
                Toast.makeText(this, MultiListPref.label(this), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    // v359 : libelle de la carte Vider le cache (taille actuelle).
    private fun refreshCacheLabel() {
        val size = try { CacheCleaner.cacheBytes(this) } catch (e: Exception) { 0L }
        findViewById<TextView>(R.id.cacheStateTv).text = "Cache actuel : " + CacheCleaner.human(size)
    }

    // Vide le cache apres confirmation. Aucun reglage ni favori n est touche.
    private fun clearCache() {
        val size = try { CacheCleaner.cacheBytes(this) } catch (e: Exception) { 0L }
        AlertDialog.Builder(this)
            .setTitle("Vider le cache")
            .setMessage("Cache actuel : " + CacheCleaner.human(size) + ". Les r\u00e9glages, la licence et les favoris ne sont pas touch\u00e9s.")
            .setPositiveButton("Vider") { d, _ ->
                d.dismiss()
                val freed = try { CacheCleaner.clear(this) } catch (e: Exception) { 0L }
                refreshCacheLabel()
                Toast.makeText(this, "Cache vid\u00e9 : " + CacheCleaner.human(freed) + " lib\u00e9r\u00e9s", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // Boite administrateur (appui long sur la carte Mise a jour).
    private fun showAdminDialog() {
        val nl = System.lineSeparator()
        val lic = DeviceIdentity.licenseCode(this)
        val fp = AdminMode.fingerprint(this)
        if (!AdminMode.isAdmin(this)) {
            val msg = "Licence : " + lic + nl + nl + "Empreinte :" + nl + fp + nl + nl +
                "Cet appareil n est pas administrateur."
            AlertDialog.Builder(this)
                .setTitle("Appareil")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val on = AdminMode.diagEnabled(this)
        val etat = if (on) "active" else "desactive"
        val msg = "Licence : " + lic + nl + nl + "Journal de diagnostic : " + etat
        val btn = if (on) "Desactiver" else "Activer"
        AlertDialog.Builder(this)
            .setTitle("Mode administrateur")
            .setMessage(msg)
            .setPositiveButton(btn) { d, _ ->
                d.dismiss()
                val next = AdminMode.toggleDiag(this)
                val txt = if (next) "Journal de diagnostic active" else "Journal de diagnostic desactive"
                Toast.makeText(this, txt, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun currentVersionCode(): Int = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt()
        else @Suppress("DEPRECATION") info.versionCode
    } catch (e: Exception) { 0 }

    // Recharge la licence + les serveurs (playlists), comme l'ancien bouton "Recharger" de l'accueil.
    private fun reloadPlaylists() {
        val stateTv = findViewById<TextView>(R.id.reloadStateTv)
        stateTv.text = "Rechargement\u2026"
        lifecycleScope.launch {
            try {
                val res = Api.checkLicense(
                    DeviceIdentity.stableId(this@SettingsActivity),
                    DeviceIdentity.licenseCode(this@SettingsActivity),
                    Build.MODEL ?: "Android TV", "1.0"
                )
                if (res.ok && res.active) {
                    LicenseGuard.rememberOk(this@SettingsActivity, res.expiration)
                    Session.playlists = LocalPlaylists.merge(res.playlists)
                    Session.expiration = res.expiration
                    val selectedId = Session.current?.id
                    Session.current = Session.playlists.firstOrNull { it.id == selectedId }
                        ?: Session.playlists.firstOrNull()
                    SessionCache.save(this@SettingsActivity)
                    stateTv.text = "Listes recharg\u00e9es \u2713"
                    Toast.makeText(this@SettingsActivity, "Listes de lecture recharg\u00e9es", Toast.LENGTH_SHORT).show()
                    val plName = Session.current?.nom
                    findViewById<TextView>(R.id.playlistStateTv).text =
                        if (!plName.isNullOrBlank()) "Active : $plName" else "Choisir le serveur / la liste active"
                } else if (LicenseGuard.wasRecentlyActive(this@SettingsActivity)) {
                    // Fenetre de grace : hoquet backend, on ne dit pas "inactive" a tort.
                    stateTv.text = "Backend indisponible, licence conserv\u00e9e"
                } else {
                    stateTv.text = res.message.ifBlank { "Licence inactive" }
                }
            } catch (e: Exception) {
                stateTv.text = "Erreur de rechargement"
            }
        }
    }

    // Verifie la derniere version publiee via le panel admin (best-effort, ne casse
    // rien si le backend ne connait pas encore cette action).
    private fun checkUpdate() {
        val stateTv = findViewById<TextView>(R.id.updateStateTv)
        val cur = currentVersion()
        stateTv.text = "V\u00e9rification en cours\u2026"
        lifecycleScope.launch {
            try {
                val info = Api.checkForUpdate(
                    DeviceIdentity.licenseCode(this@SettingsActivity), cur, currentVersionCode()
                )
                if (info.hasUpdate && info.downloadUrl.isNotBlank()) {
                    val version = if (info.latestVersion.isNotBlank()) info.latestVersion else "nouvelle"
                    stateTv.text = "Mise a jour disponible : $version"
                    val msg = buildString {
                        append("Version installee : ").append(cur).append("\n")
                        append("Nouvelle version : ").append(version)
                        if (info.notes.isNotBlank()) { append("\n\n").append(info.notes) }
                    }
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle("Mise \u00e0 jour disponible")
                        .setMessage(msg)
                        .setPositiveButton("T\u00e9l\u00e9charger") { _, _ ->
                            // v145 : telechargement + installation en direct via ApkUpdater
                            // (marche sur les Android TV / boitiers sans navigateur).
                            ApkUpdater.install(this@SettingsActivity, info.downloadUrl, info.latestVersion)
                        }
                        .setNegativeButton("Plus tard", null)
                        .show()
                } else if (info.latestVersion.isNotBlank()) {
                    stateTv.text = "A jour (version installee : $cur)"
                    Toast.makeText(this@SettingsActivity, "L'application est \u00e0 jour", Toast.LENGTH_SHORT).show()
                } else {
                    // Backend ne repond pas / action inconnue : on garde la carte visible avec la version.
                    stateTv.text = "Version installee : $cur"
                    Toast.makeText(this@SettingsActivity, "Aucune mise \u00e0 jour trouv\u00e9e", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                stateTv.text = "Version installee : $cur"
                Toast.makeText(this@SettingsActivity, "V\u00e9rification impossible", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Ouvre une boite de dialogue pour saisir l'URL du proxy Cloudflare (fallback DNS).
    // Ex : https://kzplayer.pages.dev  (ne pas mettre /api/kz a la fin, c'est ajoute).
    private fun editCfProxyUrl() {
        val current = Config.currentCfProxyUrl(this)
        val input = EditText(this).apply {
            setText(current)
            hint = "https://kzplayer.pages.dev"
            setSingleLine(true)
        }
        val pad = (resources.displayMetrics.density * 20).toInt()
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Panel Cloudflare (fallback DNS)")
            .setMessage("URL du panel Cloudflare (sans /api/kz). Utilise en secours quand la box du client bloque script.google.com.")
            .setView(wrap)
            .setPositiveButton("Enregistrer") { _, _ ->
                val v = input.text?.toString()?.trim().orEmpty()
                Config.saveCfProxyUrl(this, v)
                refreshCfProxyLabel()
                Toast.makeText(this, if (v.isBlank()) "Fallback d\u00e9sactiv\u00e9" else "URL enregistr\u00e9e", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annuler", null)
            .setNeutralButton("Effacer") { _, _ ->
                Config.saveCfProxyUrl(this, "")
                refreshCfProxyLabel()
                Toast.makeText(this, "Fallback d\u00e9sactiv\u00e9", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun refreshCfProxyLabel() {
        val cur = Config.currentCfProxyUrl(this)
        findViewById<TextView>(R.id.panelCfStateTv).text =
            if (cur.isBlank()) "URL de secours si le DNS est bloqu\u00e9" else cur
    }

    // v147 : selection du decodeur video (Auto / Logiciel / Materiel).
    // Repare les box TV ou l'image reste figee (son OK, image bloquee) en donnant
    // la priorite au decodeur logiciel systeme, sans casser les box qui fonctionnent
    // bien avec le materiel (elles peuvent basculer sur "Materiel").
    private fun pickVideoDecoder() {
        val current = VideoDecoderPref.current(this)
        val values = arrayOf(VideoDecoderPref.AUTO, VideoDecoderPref.SOFTWARE, VideoDecoderPref.HARDWARE)
        val labels = arrayOf(
            VideoDecoderPref.label(VideoDecoderPref.AUTO) + "  \u2014  recommand\u00e9",
            VideoDecoderPref.label(VideoDecoderPref.SOFTWARE),
            VideoDecoderPref.label(VideoDecoderPref.HARDWARE)
        )
        val checked = values.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("D\u00e9codage vid\u00e9o")
            .setSingleChoiceItems(labels, checked) { d, which ->
                VideoDecoderPref.set(this, values[which])
                refreshVideoDecoderLabel()
                Toast.makeText(this, "Appliqu\u00e9 au prochain lancement du lecteur", Toast.LENGTH_SHORT).show()
                d.dismiss()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun refreshVideoDecoderLabel() {
        val cur = VideoDecoderPref.current(this)
        findViewById<TextView>(R.id.videoDecoderStateTv).text = VideoDecoderPref.label(cur)
    }

    // v149 : selection du DNS-over-HTTPS integre (Cloudflare, Google, Quad9, AdGuard, personnalise).
    // S'applique a TOUT le trafic de l'app (portails IPTV + flux video ExoPlayer + panel + mises a jour).
    // Ne change PAS le DNS des autres apps du boitier : c'est un DNS applicatif, pas systeme.
    // Aucun VPN ni root necessaire.
    private fun pickDns() {
        val values = arrayOf(
            DnsPref.SYSTEM, DnsPref.CLOUDFLARE, DnsPref.GOOGLE, DnsPref.QUAD9,
            DnsPref.ADGUARD, DnsPref.CUSTOM
        )
        val labels = values.map { DnsPref.label(it) }.toTypedArray()
        val current = DnsPref.current(this)
        val checked = values.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("DNS de l'application")
            .setSingleChoiceItems(labels, checked) { d, which ->
                val picked = values[which]
                d.dismiss()
                if (picked == DnsPref.CUSTOM) askCustomDns() else applyDns(picked)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun applyDns(provider: String) {
        DnsPref.set(this, provider)
        refreshDnsLabel()
        // v151 : le pool OkHttp est purge par DnsPref.set() -> effet immediat sur les
        // prochains appels reseau (portails, panel, updates). Pour un flux VIDEO deja en
        // train de jouer, il faut rouvrir la chaine (le lecteur garde son propre socket ouvert).
        val msg = if (provider == DnsPref.SYSTEM) {
            "DNS syst\u00e8me r\u00e9tabli - actif imm\u00e9diatement"
        } else {
            DnsPref.label(provider) + " - actif imm\u00e9diatement (rouvre la cha\u00eene pour les flux en cours)"
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun askCustomDns() {
        val input = EditText(this).apply {
            hint = "https://exemple.com/dns-query"
            setText(DnsPref.customUrl(this@SettingsActivity))
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("URL DoH personnalisee")
            .setMessage("Colle l'URL DNS-over-HTTPS complete (RFC 8484, format wire).\nEx NextDNS : https://dns.nextdns.io/xxxxxx/dns-query")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val url = input.text.toString().trim()
                if (!url.startsWith("https://")) {
                    Toast.makeText(this, "URL invalide (doit commencer par https://)", Toast.LENGTH_LONG).show()
                } else {
                    DnsPref.setCustomUrl(this, url)
                    applyDns(DnsPref.CUSTOM)
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun refreshDnsLabel() {
        val cur = DnsPref.current(this)
        findViewById<TextView>(R.id.dnsStateTv).text = DnsPref.label(cur)
    }

    override fun onResume() {
        super.onResume()
        // Met a jour les libelles avec les valeurs actuelles (theme + liste active).
        // v342 : le libelle doit aussi connaitre le theme Netflix.
        val themeName = when (ThemePref.get(this)) {
            ThemePref.NETFLIX -> "Netflix"
            ThemePref.NEWTIVI -> "NewTivi"
            else -> "Classique"
        }
        findViewById<TextView>(R.id.themeStateTv).text = "Actuel : $themeName"
        findViewById<TextView>(R.id.colorStateTv).text = "Actuel : " + ColorThemePref.current(this).label
        val plName = Session.current?.nom
        findViewById<TextView>(R.id.playlistStateTv).text =
            if (!plName.isNullOrBlank()) "Active : $plName" else "Choisir le serveur / la liste active"
        // Toujours re-afficher la version installee (jamais de carte vide).
        findViewById<TextView>(R.id.updateStateTv).text = "Version installee : ${currentVersion()}"
        refreshCfProxyLabel()
        refreshVideoDecoderLabel()
        refreshDnsLabel()
    }

    // v391 : fermeture complete de KZ Player depuis les parametres.
    private fun confirmExit() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Quitter KZ Player")
            .setMessage("Fermer compl\u00e8tement l application ?")
            .setPositiveButton("Quitter") { _, _ ->
                try { finishAffinity() } catch (e: Throwable) { finish() }
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    android.os.Process.killProcess(android.os.Process.myPid())
                }, 200L)
            }
            .setNegativeButton("Annuler") { d, _ -> d.dismiss() }
            .show()
    }
}
