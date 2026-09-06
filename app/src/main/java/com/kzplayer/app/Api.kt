package com.kzplayer.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.UnknownHostException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object Api {

    // Fallback proxy Cloudflare (v144). Charge par KzApp au demarrage depuis
    // les SharedPreferences ("kz_config.cf_proxy_url"), avec Config.CF_PROXY_URL_DEFAULT
    // comme secours. Utilise par callText quand script.google.com est bloque cote DNS.
    @Volatile var cfProxyBase: String = Config.CF_PROXY_URL_DEFAULT

    // Cookies en memoire : indispensables pour passer la protection anti-bot du free host.
    private val cookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()
    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val list = cookieStore.getOrPut(url.host) { mutableListOf() }
            for (ck in cookies) { list.removeAll { it.name == ck.name }; list.add(ck) }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            cookieStore[url.host]?.toList() ?: emptyList()
    }

    private val client: OkHttpClient = buildLenientClient()

    /** Client OkHttp permissif reutilise par Coil pour charger logos/affiches. */
    fun imageClient(): OkHttpClient = client

    // v151 : purge le pool de connexions OkHttp pour que le prochain appel force une
    // nouvelle resolution DNS. Appele par DnsPref.set() quand l'utilisateur change de DNS,
    // ainsi le changement est immediat sans redemarrer l'app ni cliquer sur Recharger.
    fun evictConnections() {
        try { client.connectionPool.evictAll() } catch (_: Exception) {}
        try { DohDns.clearCache() } catch (_: Exception) {}
    }

    // Stalker renvoie souvent un logo RELATIF ("123.png" ou "/misc/logos/123.png") que
    // Coil ne peut pas charger -> icone par defaut. On reconstruit une URL absolue a partir
    // du portail. Les URL deja completes (Xtream/M3U) sont laissees telles quelles.
    // 100% additif (affichage uniquement) : n'impacte ni les flux, ni create_link, ni l'EPG.
    private fun absLogo(portal: String, raw: String): String {
        val s = raw.trim()
        if (s.isBlank()) return ""
        if (s.startsWith("http://", true) || s.startsWith("https://", true)) return s
        val schemeEnd = portal.indexOf("://")
        if (schemeEnd < 0) return s
        if (s.startsWith("//")) return portal.substring(0, schemeEnd) + ":" + s
        val slash = portal.indexOf('/', schemeEnd + 3)
        val base = if (slash >= 0) portal.substring(0, slash) else portal
        return if (s.startsWith("/")) base + s
            else "$base/stalker_portal/misc/logos/320/$s"
    }

    // Date d'ajout Stalker (pour le tri "Recents"). Accepte un epoch ou "yyyy-MM-dd HH:mm:ss".
    private fun parseStalkerAdded(o: JSONObject): Long {
        val raw = o.optString("added").ifBlank { o.optString("date_add") }.trim()
        if (raw.isBlank()) return 0L
        raw.toLongOrNull()?.let { return it }
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).parse(raw)?.time ?: 0L
        } catch (e: Exception) { 0L }
    }

    // Categories masquees imposees par le panel (champ JSON "hidden_categories": ["Adultes", ...]).
    private fun parseHidden(p: JSONObject): List<String> {
        val arr = p.optJSONArray("hidden_categories") ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) { val s = arr.optString(i).trim(); if (s.isNotBlank()) out.add(s) }
        return out
    }

    // Liste blanche des categories a AFFICHER imposee par le panel, PAR SECTION.
    // Format attendu : "shown_categories": { "live": [...], "movie": [...], "series": [...] }.
    // Vide = aucune restriction (tout est affiche).
    private fun parseShownMap(p: JSONObject): Map<String, List<String>> {
        val obj = p.optJSONObject("shown_categories") ?: return emptyMap()
        val out = HashMap<String, List<String>>()
        for (k in listOf("live", "movie", "series")) {
            val arr = obj.optJSONArray(k) ?: continue
            val l = ArrayList<String>()
            for (i in 0 until arr.length()) { val s = arr.optString(i).trim(); if (s.isNotBlank()) l.add(s) }
            if (l.isNotEmpty()) out[k] = l
        }
        return out
    }

    // Beaucoup d'hebergeurs gratuits (0hi.me, etc.) servent une chaine de
    // certificat SSL incomplete -> Android refuse avec "Trust anchor for
    // certification path not found". On accepte donc tous les certificats : il
    // s'agit de ton propre serveur et aucune donnee sensible n'y transite.
    private fun buildLenientClient(): OkHttpClient {
        return try {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
            OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAll)
                .hostnameVerifier(HostnameVerifier { _, _ -> true })
                .dispatcher(Dispatcher().apply { maxRequestsPerHost = 10; maxRequests = 64 })
                .cookieJar(cookieJar)
                // v149 : DNS-over-HTTPS (mode SYSTEM par defaut -> DNS Android natif inchange).
                .dns(DohDns)
                .followRedirects(true)
                .protocols(listOf(Protocol.HTTP_1_1))
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        } catch (e: Exception) {
            OkHttpClient.Builder()
                .cookieJar(cookieJar)
                .dns(DohDns)
                .protocols(listOf(Protocol.HTTP_1_1))
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }

    private val JSON = "application/json; charset=utf-8".toMediaType()

    // UA navigateur : certains free hosts (WAF openresty) rejettent les requetes non-navigateur (HTTP 400).
    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    data class LoginResult(
        val ok: Boolean,
        val message: String,
        val playlists: List<Playlist>,
        val expiration: String?,
        val httpCode: Int = 0,
        val raw: String = ""
    )


    data class LicenseResult(
        val ok: Boolean,
        val active: Boolean,
        val message: String,
        val deviceCode: String,
        val playlists: List<Playlist>,
        val expiration: String?
    )

    private fun parsePlaylists(obj: JSONObject): List<Playlist> {
        val pls = ArrayList<Playlist>()
        val arr = obj.optJSONArray("playlists") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            val macSn = splitMacAndSn(p.optString("mac"))
            pls.add(
                normalizePlaylist(Playlist(
                    id = p.optString("id"),
                    type = p.optString("type", "xtream").lowercase(),
                    nom = p.optString("nom", p.optString("name", "Playlist")),
                    serverUrl = cleanBase(p.optString("server_url")),
                    username = p.optString("username", p.optString("xuser")),
                    password = p.optString("password", p.optString("xpass")),
                    mac = macSn.first,
                    m3uUrl = p.optString("m3u_url"),
                    stalkerSn = p.optString("sn", p.optString("stalker_sn")).ifBlank { macSn.second },
                    stalkerDeviceId = p.optString("device_id", p.optString("stalker_device_id")),
                    stalkerDeviceId2 = p.optString("device_id2", p.optString("stalker_device_id2")),
                    stalkerSignature = p.optString("signature", p.optString("stalker_signature")),
                    stalkerMetrics = p.optString("metrics", p.optString("stalker_metrics")),
                    stalkerHwVersion2 = p.optString("hw_version_2", p.optString("stalker_hw_version_2")),
                    stalkerTimestamp = p.optString("timestamp", p.optString("stalker_timestamp")),
                    stalkerPrehash = p.optString("prehash", p.optString("stalker_prehash")),
                    stalkerApiSignature = p.optString("api_signature", p.optString("stalker_api_signature")),
                    stalkerImageVersion = p.optString("image_version", p.optString("stalker_image_version")),
                    stalkerVer = p.optString("ver", p.optString("stalker_ver")), hiddenCategories = parseHidden(p), shownByKind = parseShownMap(p)
                ))
            )
        }
        return pls
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    // Nettoie une server_url qui contiendrait deja un chemin colle par erreur.
    fun cleanBase(raw: String): String {
        var s = raw.trim().trimEnd('/')
        val cut = Regex("(?i)(/live/|/movie/|/series/|/player_api\\.php|/get\\.php|/c(?:/|$)|/stalker_portal/).*$")
        s = cut.replace(s, "")
        return s.trimEnd('/')
    }

    private fun splitMacAndSn(raw: String): Pair<String, String> {
        val s = raw.trim()
        if (s.isBlank()) return Pair("", "")
        val separators = listOf("|sn=", "|SN=", ";sn=", ";SN=", ",sn=", ",SN=", "#sn=", "#SN=")
        for (sep in separators) {
            val i = s.indexOf(sep)
            if (i > 0) return Pair(s.substring(0, i).trim(), s.substring(i + sep.length).trim())
        }
        val pipe = s.indexOf('|')
        if (pipe > 0) return Pair(s.substring(0, pipe).trim(), s.substring(pipe + 1).trim())
        return Pair(s, "")
    }

    // Un lien M3U de type Xtream (get.php?...&type=m3u_plus) est en realite un serveur Xtream complet.
    // On le convertit pour utiliser l'API Xtream (vraies categories, films, series, metadonnees).
    private fun normalizePlaylist(pl: Playlist): Playlist {
        // v165 : pour un serveur Stalker, si le user a renseigne URL + MAC mais pas de SN,
        // on genere automatiquement le SN cut (md5(MAC)[:13].upper) - le meme que SFVip / Titan.
        // Ca active le profil MAG complet (avec api_signature=262) sans que l'user ait a le taper.
        if (pl.type == "stalker" && pl.mac.isNotBlank() && pl.stalkerSn.isBlank()) {
            val autoSn = md5Hex(pl.mac).uppercase().substring(0, 13)
            return pl.copy(stalkerSn = autoSn)
        }
        if (pl.type != "m3u") return pl
        val link = pl.m3uUrl
        if (!link.contains("get.php", true) && !link.contains("player_api", true)) return pl
        val server = cleanBase(link)
        val query = link.substringAfter("?", "")
        val params = HashMap<String, String>()
        for (part in query.split("&")) {
            val i = part.indexOf('=')
            if (i > 0) params[part.substring(0, i).lowercase()] = part.substring(i + 1)
        }
        val rawUser = params["username"] ?: pl.username
        val rawPass = params["password"] ?: pl.password
        val user = try { java.net.URLDecoder.decode(rawUser, "UTF-8") } catch (e: Exception) { rawUser }
        val pass = try { java.net.URLDecoder.decode(rawPass, "UTF-8") } catch (e: Exception) { rawPass }
        if (server.isBlank() || user.isBlank()) return pl
        return pl.copy(type = "xtream", serverUrl = server, username = user, password = pass)
    }

    // ---------- Protection anti-bot (challenge aes.js -> cookie __test) ----------
    private fun isChallenge(body: String): Boolean =
        body.contains("slowAES", true) ||
        (body.contains("toNumbers(", true) && body.contains("__test", true))

    private fun hexToBytes(h: String): ByteArray {
        val out = ByteArray(h.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(h[i * 2], 16) shl 4) + Character.digit(h[i * 2 + 1], 16)).toByte()
        }
        return out
    }

    private fun toHexStr(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            val v = b.toInt() and 0xff
            if (v < 16) sb.append('0')
            sb.append(Integer.toHexString(v))
        }
        return sb.toString()
    }

    // Resout le defi JavaScript du free host : decrypte AES-CBC puis stocke le cookie __test.
    private fun solveChallenge(url: HttpUrl, html: String): Boolean {
        return try {
            val nums = Regex("toNumbers\\(\"([0-9a-fA-F]+)\"\\)")
                .findAll(html).map { it.groupValues[1] }.toList()
            if (nums.size < 3) return false
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(hexToBytes(nums[0]), "AES"),
                IvParameterSpec(hexToBytes(nums[1]))
            )
            val testVal = toHexStr(cipher.doFinal(hexToBytes(nums[2])))
            val cookie = Cookie.Builder().domain(url.host).path("/")
                .name("__test").value(testVal).expiresAt(Long.MAX_VALUE).build()
            val list = cookieStore.getOrPut(url.host) { mutableListOf() }
            list.removeAll { it.name == "__test" }
            list.add(cookie)
            true
        } catch (e: Exception) { false }
    }

    // Execute la requete ; si le free host renvoie le defi JS, le resout puis reessaie.
    // v144 : si l'host est script.google.com et qu'il y a un UnknownHostException
    // (DNS bloque par la box du client) ou un IOException reseau, on bascule
    // automatiquement sur le proxy Cloudflare (cfProxyBase) en POST JSON /api/kz.
    private fun callText(req0: Request): Pair<Int, String> {
        val host = req0.url.host
        val req = req0.newBuilder()
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "application/json, text/plain, text/html, */*")
            .header("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.8")
            .header("Referer", "https://$host/")
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (isChallenge(body) && solveChallenge(req.url, body)) {
                    client.newCall(req.newBuilder().build()).execute().use { r2 ->
                        return Pair(r2.code, r2.body?.string() ?: "")
                    }
                }
                Pair(resp.code, body)
            }
        } catch (e: UnknownHostException) {
            callViaCfProxy(req0) ?: throw e
        } catch (e: IOException) {
            // Autres erreurs reseau bas niveau (connexion refusee, timeout DNS...).
            // On tente le fallback SEULEMENT pour l'host Apps Script.
            if (host.endsWith("script.google.com", ignoreCase = true)) {
                callViaCfProxy(req0) ?: throw e
            } else throw e
        }
    }

    // Rejoue la requete Apps Script en POST JSON sur le proxy Cloudflare (fallback DNS).
    // Retourne null si le proxy n'est pas configure ou n'a pas une base URL exploitable.
    private fun callViaCfProxy(req0: Request): Pair<Int, String>? {
        val base = cfProxyBase.trim().trimEnd('/')
        if (base.isBlank()) return null
        if (!base.startsWith("http://", true) && !base.startsWith("https://", true)) return null
        val fb = req0.body as? FormBody ?: return null
        val jo = JSONObject()
        for (i in 0 until fb.size) {
            try { jo.put(fb.name(i), fb.value(i)) } catch (_: Exception) {}
        }
        val proxyUrl = "$base/api/kz"
        val body = jo.toString().toRequestBody(JSON)
        val proxyReq = Request.Builder()
            .url(proxyUrl)
            .post(body)
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Accept", "application/json")
            .header("User-Agent", Config.USER_AGENT)
            .build()
        return try {
            client.newCall(proxyReq).execute().use { resp ->
                Pair(resp.code, resp.body?.string() ?: "")
            }
        } catch (e: Exception) { null }
    }

    private fun httpText(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", Config.USER_AGENT).build()
        return callText(req).second
    }

    private fun httpArray(url: String): JSONArray =
        try { JSONArray(httpText(url)) } catch (e: Exception) { JSONArray() }

    private fun httpObject(url: String): JSONObject =
        try { JSONObject(httpText(url)) } catch (e: Exception) { JSONObject() }

    // Verification de licence avec re-essais internes : Apps Script renvoie parfois
    // une reponse vide/HTML au cold start ou en cas de pic (-> ok=false a tort). On
    // fait donc jusqu'a 3 tentatives (0 / 700 ms / 1500 ms) avant de rendre le
    // resultat. Cela supprime la plupart des faux "licence en attente d'activation" /
    // "licence inactive" quand la licence est bel et bien activee cote panel.
    suspend fun checkLicense(deviceId: String, deviceCode: String, deviceName: String, appVersion: String): LicenseResult {
        var last: LicenseResult? = null
        val delays = longArrayOf(0L, 700L, 1500L)
        for (i in delays.indices) {
            if (delays[i] > 0L) kotlinx.coroutines.delay(delays[i])
            val r = try { checkLicenseOnce(deviceId, deviceCode, deviceName, appVersion) } catch (e: Exception) {
                LicenseResult(ok = false, active = false, message = e.message ?: "",
                    deviceCode = deviceCode, playlists = emptyList(), expiration = null)
            }
            last = r
            if (r.ok && r.active) return r
        }
        return last ?: LicenseResult(ok = false, active = false, message = "",
            deviceCode = deviceCode, playlists = emptyList(), expiration = null)
    }

    private suspend fun checkLicenseOnce(deviceId: String, deviceCode: String, deviceName: String, appVersion: String): LicenseResult =
        withContext(Dispatchers.IO) {
            val payload = FormBody.Builder()
                .add("action", "checkLicense")
                .add("device_id", deviceId)
                .add("device_code", deviceCode)
                .add("device_name", deviceName)
                .add("app_version", appVersion)
                .build()
            val req = Request.Builder()
                .url(Config.LOGIN_URL)
                .post(payload)
                .build()
            val (_, txt) = callText(req)
            val obj = try { JSONObject(txt) } catch (e: Exception) { JSONObject() }
            val ok = obj.optBoolean("ok", false)
            val active = obj.optBoolean("active", false)
            val msg = obj.optString("message").ifBlank { obj.optString("error") }
            LicenseResult(
                ok = ok,
                active = active,
                message = msg,
                deviceCode = obj.optString("device_code", deviceCode),
                playlists = parsePlaylists(obj),
                expiration = obj.optString("expiration").ifBlank { null }
            )
        }

    // ---------------- MISE A JOUR (GitHub Releases) ----------------
    // Source prioritaire : l'API publique GitHub Releases du depot KZ Player. Le workflow
    // GitHub Actions tague chaque build "v{run_number}" et attache l'APK signe -> on
    // recupere ainsi la derniere version en une seule requete rapide, sans dependre du
    // panel Apps Script (qui pouvait mettre 20-30 s a repondre au cold start, donnant
    // l'impression que le bouton "Mise a jour" bloque).
    //
    // - Timeout global strict (6 s) : le bouton repond TOUJOURS vite.
    // - Compare le "v{N}" du tag au versionCode installe (fourni par SettingsActivity).
    // - Fallback best-effort sur le backend Apps Script si GitHub echoue.
    private const val GITHUB_RELEASES_URL =
        "https://api.github.com/repos/Lennyprs/KZPLAYER/releases/latest"

    suspend fun checkForUpdate(license: String, currentVersion: String, currentVersionCode: Int = 0): UpdateInfo = withContext(Dispatchers.IO) {
        // 1) Essaye GitHub Releases avec un timeout court : reponse rapide garantie.
        val gh = kotlinx.coroutines.withTimeoutOrNull(6000L) {
            try { queryGithubRelease(currentVersion, currentVersionCode) } catch (e: Exception) { null }
        }
        if (gh != null && (gh.hasUpdate || gh.downloadUrl.isNotBlank() || gh.latestVersion.isNotBlank())) {
            return@withContext gh
        }
        // 2) Fallback best-effort : ancien chemin (Apps Script), avec timeout global.
        val backend = kotlinx.coroutines.withTimeoutOrNull(8000L) {
            val actions = listOf("getUpdate", "checkUpdate", "latestVersion")
            for (action in actions) {
                val info = try { queryUpdate(action, license, currentVersion) } catch (e: Exception) { null }
                if (info != null && (info.hasUpdate || info.latestVersion.isNotBlank() || info.downloadUrl.isNotBlank())) {
                    return@withTimeoutOrNull info
                }
            }
            null
        }
        backend ?: UpdateInfo(ok = true, hasUpdate = false, latestVersion = "", currentVersion = currentVersion,
            downloadUrl = "", notes = "", message = "")
    }

    // Interroge directement l'API GitHub Releases (public, pas d'auth requise). On
    // fabrique un client OkHttp dedie avec timeouts courts pour ne JAMAIS bloquer
    // l'UI si l'endpoint est lent.
    private fun queryGithubRelease(currentVersion: String, currentVersionCode: Int): UpdateInfo {
        val quick = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
        val req = Request.Builder()
            .url(GITHUB_RELEASES_URL)
            .header("User-Agent", "KZPlayer-App")
            .header("Accept", "application/vnd.github+json")
            .build()
        val body = quick.newCall(req).execute().use { it.body?.string() ?: "" }
        val obj = try { JSONObject(body) } catch (e: Exception) { return emptyUpdate(currentVersion) }
        val tag = obj.optString("tag_name").ifBlank { obj.optString("name") } // ex "v135"
        val notes = obj.optString("body")
        val htmlUrl = obj.optString("html_url")
        // Trouve l'asset APK (fichier .apk).
        val assets = obj.optJSONArray("assets")
        var apkUrl = ""
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val name = a.optString("name")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = a.optString("browser_download_url")
                    if (apkUrl.isNotBlank()) break
                }
            }
        }
        // Compare la version : le tag est de la forme "v{run_number}" == versionCode.
        val tagNum = tag.trimStart('v', 'V').toIntOrNull() ?: 0
        val has = when {
            apkUrl.isBlank() -> false
            currentVersionCode > 0 && tagNum > 0 -> tagNum > currentVersionCode
            else -> tag.isNotBlank() // fallback : offre le telechargement si on ne peut pas comparer
        }
        return UpdateInfo(
            ok = true,
            hasUpdate = has,
            latestVersion = tag,
            currentVersion = currentVersion,
            downloadUrl = if (apkUrl.isNotBlank()) apkUrl else htmlUrl,
            notes = notes,
            message = ""
        )
    }

    private fun emptyUpdate(currentVersion: String) = UpdateInfo(
        ok = false, hasUpdate = false, latestVersion = "", currentVersion = currentVersion,
        downloadUrl = "", notes = "", message = ""
    )

    private suspend fun queryUpdate(action: String, license: String, currentVersion: String): UpdateInfo {
        val payload = FormBody.Builder()
            .add("action", action)
            .add("license", license)
            .add("current_version", currentVersion)
            .add("app_version", currentVersion)
            .build()
        val req = Request.Builder().url(Config.LOGIN_URL).post(payload).build()
        val (_, txt) = callText(req)
        val obj = try { JSONObject(txt) } catch (e: Exception) { JSONObject() }
        val ok = obj.optBoolean("ok", true)
        val latest = obj.optString("latest_version")
            .ifBlank { obj.optString("version") }
            .ifBlank { obj.optString("latestVersion") }
        val url = obj.optString("apk_url")
            .ifBlank { obj.optString("url") }
            .ifBlank { obj.optString("download_url") }
            .ifBlank { obj.optString("apkUrl") }
        val notes = obj.optString("notes")
            .ifBlank { obj.optString("changelog") }
            .ifBlank { obj.optString("release_notes") }
        val hasFlag = obj.optBoolean("update_available", false) || obj.optBoolean("has_update", false)
        val has = hasFlag || (url.isNotBlank() && latest.isNotBlank() && latest != currentVersion)
        return UpdateInfo(
            ok = ok, hasUpdate = has, latestVersion = latest, currentVersion = currentVersion,
            downloadUrl = url, notes = notes, message = obj.optString("message")
        )
    }

    // Envoie au backend la liste des categories d'un serveur (l'app sait les recuperer pour tous
    // les types, y compris Stalker). Le panel les affiche ensuite en cases a cocher. Best-effort.
    suspend fun reportCategories(license: String, playlistId: String, kind: String, names: List<String>) = withContext(Dispatchers.IO) {
        try {
            if (license.isBlank() || playlistId.isBlank() || names.isEmpty()) return@withContext
            val cats = JSONArray()
            for (n in names.take(400)) cats.put(n)
            val payload = FormBody.Builder()
                .add("action", "clientPlCatsReport")
                .add("license", license)
                .add("id", playlistId)
                .add("kind", kind)
                .add("cats", cats.toString())
                .build()
            callText(Request.Builder().url(Config.LOGIN_URL).post(payload).build())
        } catch (e: Exception) {}
        Unit
    }

    // Enregistre la liste blanche "a afficher" d'une section cote backend (comme le panel),
    // pour que app et panel restent synchronises. Best-effort.
    suspend fun setShown(license: String, playlistId: String, kind: String, names: List<String>) = withContext(Dispatchers.IO) {
        try {
            if (license.isBlank() || playlistId.isBlank()) return@withContext
            val arr = JSONArray()
            for (n in names) if (n.isNotBlank()) arr.put(n)
            val payload = FormBody.Builder()
                .add("action", "clientPlSetShown")
                .add("license", license)
                .add("id", playlistId)
                .add("kind", kind)
                .add("shown", arr.toString())
                .build()
            callText(Request.Builder().url(Config.LOGIN_URL).post(payload).build())
        } catch (e: Exception) {}
        Unit
    }

    // ---------------- v391 : listes ajoutees a la main + etat de sante ----------------

    // Enregistre sur le panel une liste ajoutee a la main depuis l application, afin
    // qu elle apparaisse aussi cote panel utilisateur. Best-effort : plusieurs noms
    // d action sont essayes, l app fonctionne meme si le backend ne les connait pas.
    suspend fun addPlaylistRemote(license: String, pl: Playlist): Boolean = withContext(Dispatchers.IO) {
        var done = false
        for (action in listOf("clientPlAdd", "addPlaylist", "clientAddPlaylist")) {
            try {
                val payload = FormBody.Builder()
                    .add("action", action)
                    .add("license", license)
                    .add("id", pl.id)
                    .add("type", pl.type)
                    .add("nom", pl.nom)
                    .add("name", pl.nom)
                    .add("server", pl.serverUrl)
                    .add("server_url", pl.serverUrl)
                    .add("username", pl.username)
                    .add("password", pl.password)
                    .add("mac", pl.mac)
                    .add("m3u", pl.m3uUrl)
                    .add("m3u_url", pl.m3uUrl)
                    .add("source", "app")
                    .build()
                val (code, txt) = callText(Request.Builder().url(Config.LOGIN_URL).post(payload).build())
                val obj = try { JSONObject(txt) } catch (e: Exception) { JSONObject() }
                if (code in 200..299 && (obj.optBoolean("ok", false) || obj.optString("status") == "ok")) {
                    done = true; break
                }
            } catch (e: Exception) {}
        }
        done
    }

    // Signale au panel qu une liste est expiree ou ne repond plus. Best-effort.
    suspend fun reportPlaylistStatus(license: String, playlistId: String, status: String, message: String) = withContext(Dispatchers.IO) {
        try {
            if (license.isBlank() || playlistId.isBlank()) return@withContext
            for (action in listOf("clientPlStatus", "clientPlHealth", "playlistStatus")) {
                val payload = FormBody.Builder()
                    .add("action", action)
                    .add("license", license)
                    .add("id", playlistId)
                    .add("status", status)
                    .add("message", message)
                    .build()
                val (code, txt) = callText(Request.Builder().url(Config.LOGIN_URL).post(payload).build())
                val obj = try { JSONObject(txt) } catch (e: Exception) { JSONObject() }
                if (code in 200..299 && (obj.optBoolean("ok", false) || obj.optString("status") == "ok")) break
            }
        } catch (e: Exception) {}
        Unit
    }

    private fun frDate(ms: Long): String = try {
        java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.FRANCE).format(java.util.Date(ms))
    } catch (e: Exception) { "" }

    // Verifie si une liste est active, expiree, ou ne repond plus.
    // Retourne (statut, message) avec statut = "ok" / "expired" / "down".
    suspend fun playlistHealth(pl: Playlist): Pair<String, String> = withContext(Dispatchers.IO) {
        try {
            when (pl.type) {
                "m3u" -> {
                    val cats = m3uCategories(pl, "live")
                    if (cats.isEmpty()) Pair("down", "Aucune cha\u00eene renvoy\u00e9e") else Pair("ok", "")
                }
                "stalker" -> {
                    val cats = stalkerCategories(pl, "live")
                    if (cats.isEmpty()) Pair("down", "Portail sans r\u00e9ponse") else Pair("ok", "")
                }
                else -> {
                    val url = pl.serverUrl.trimEnd(chr47()) + "/player_api.php?username=" + enc(pl.username) + "&password=" + enc(pl.password)
                    val obj = httpObject(url)
                    val info = obj.optJSONObject("user_info")
                    if (info == null) Pair("down", "Serveur injoignable")
                    else {
                        val auth = info.optInt("auth", 1)
                        val st = info.optString("status", "")
                        val expMs = (info.optString("exp_date", "").toLongOrNull() ?: 0L) * 1000L
                        val date = if (expMs > 0L) frDate(expMs) else ""
                        when {
                            auth == 0 -> Pair("expired", "Identifiants refus\u00e9s")
                            st.equals("Expired", true) -> Pair("expired", if (date.isBlank()) "" else "Fin : " + date)
                            st.equals("Banned", true) || st.equals("Disabled", true) -> Pair("expired", "Compte bloqu\u00e9")
                            expMs > 0L && expMs < System.currentTimeMillis() -> Pair("expired", "Fin : " + date)
                            else -> Pair("ok", if (date.isBlank()) "" else "Jusqu au " + date)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Pair("down", e.message ?: "Erreur r\u00e9seau")
        }
    }

    private fun chr47(): Char = 47.toChar()

    // ---------------- LOGIN (ton panel) ----------------
    suspend fun login(username: String, password: String, deviceId: String): LoginResult =
        withContext(Dispatchers.IO) {
            val payload = FormBody.Builder()
                .add("username", username)
                .add("password", password)
                .add("device_id", deviceId)
                .build()
            val req = Request.Builder()
                .url(Config.LOGIN_URL)
                .post(payload)
                .build()
            val (code, txt) = callText(req)
            run {
                val parsed = try { JSONObject(txt) } catch (e: Exception) { null }
                val obj = parsed ?: JSONObject()
                val ok = obj.optBoolean("ok", false) || obj.optString("status") == "ok"
                val serverMsg = obj.optString("message").ifBlank { obj.optString("error") }
                val msg = when {
                    serverMsg.isNotBlank() -> serverMsg
                    parsed == null -> "Reponse inattendue du serveur (HTTP $code) : " + txt.replace("\n", " ").trim().take(180)
                    ok -> "OK"
                    else -> "Identifiants invalides ou compte inactif"
                }
                val pls = ArrayList<Playlist>()
                val arr = obj.optJSONArray("playlists") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val p = arr.optJSONObject(i) ?: continue
                    val macSn = splitMacAndSn(p.optString("mac"))
                    pls.add(
                        normalizePlaylist(Playlist(
                            id = p.optString("id"),
                            type = p.optString("type", "xtream").lowercase(),
                            nom = p.optString("nom", p.optString("name", "Playlist")),
                            serverUrl = cleanBase(p.optString("server_url")),
                            username = p.optString("username"),
                            password = p.optString("password"),
                            mac = macSn.first,
                            m3uUrl = p.optString("m3u_url"),
                            stalkerSn = p.optString("sn", p.optString("stalker_sn")).ifBlank { macSn.second },
                            stalkerDeviceId = p.optString("device_id", p.optString("stalker_device_id")),
                            stalkerDeviceId2 = p.optString("device_id2", p.optString("stalker_device_id2")),
                            stalkerSignature = p.optString("signature", p.optString("stalker_signature")),
                            stalkerMetrics = p.optString("metrics", p.optString("stalker_metrics")),
                            stalkerHwVersion2 = p.optString("hw_version_2", p.optString("stalker_hw_version_2")),
                            stalkerTimestamp = p.optString("timestamp", p.optString("stalker_timestamp")),
                            stalkerPrehash = p.optString("prehash", p.optString("stalker_prehash")),
                            stalkerApiSignature = p.optString("api_signature", p.optString("stalker_api_signature")),
                            stalkerImageVersion = p.optString("image_version", p.optString("stalker_image_version")),
                            stalkerVer = p.optString("ver", p.optString("stalker_ver")), hiddenCategories = parseHidden(p), shownByKind = parseShownMap(p)
                        ))
                    )
                }
                val exp = obj.optString("expiration").ifBlank { null }
                LoginResult(ok, msg, pls, exp, code, txt.take(300))
            }
        }

    // ---------------- XTREAM ----------------
    private fun xtreamApi(pl: Playlist, action: String, extra: String = ""): String =
        "${pl.serverUrl}/player_api.php?username=${enc(pl.username)}&password=${enc(pl.password)}&action=$action$extra"

    suspend fun xtreamCategories(pl: Playlist, kind: String): List<Category> = withContext(Dispatchers.IO) {
        val action = when (kind) {
            "movie" -> "get_vod_categories"
            "series" -> "get_series_categories"
            else -> "get_live_categories"
        }
        val arr = httpArray(xtreamApi(pl, action))
        val out = ArrayList<Category>()
        out.add(Category("__all__", "Tout"))
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(Category(o.optString("category_id"), o.optString("category_name")))
        }
        out
    }

    suspend fun xtreamItems(pl: Playlist, kind: String, categoryId: String): List<Item> = withContext(Dispatchers.IO) {
        val action = when (kind) {
            "movie" -> "get_vod_streams"
            "series" -> "get_series"
            else -> "get_live_streams"
        }
        val extra = if (categoryId == "__all__") "" else "&category_id=${enc(categoryId)}"
        val arr = httpArray(xtreamApi(pl, action, extra))
        val out = ArrayList<Item>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            when (kind) {
                "movie" -> {
                    val id = o.optString("stream_id")
                    val ext = o.optString("container_extension", "mp4").ifBlank { "mp4" }
                    out.add(Item(
                        name = o.optString("name"),
                        logo = o.optString("stream_icon"),
                        kind = "movie",
                        directUrl = "${pl.serverUrl}/movie/${enc(pl.username)}/${enc(pl.password)}/$id.$ext",
                        streamId = id, containerExt = ext,
                        added = o.optString("added").toLongOrNull() ?: 0L
                    ))
                }
                "series" -> {
                    out.add(Item(
                        name = o.optString("name"),
                        logo = o.optString("cover"),
                        kind = "series",
                        seriesId = o.optString("series_id"),
                        added = o.optString("last_modified").toLongOrNull() ?: 0L
                    ))
                }
                else -> {
                    val id = o.optString("stream_id")
                    val catchup = o.optInt("tv_archive", 0) == 1 ||
                        o.optString("tv_archive").equals("1", true) ||
                        o.optString("tv_archive").equals("true", true) ||
                        o.optInt("catchup", 0) == 1
                    out.add(Item(
                        name = o.optString("name"),
                        logo = o.optString("stream_icon"),
                        kind = "live",
                        directUrl = "${pl.serverUrl}/live/${enc(pl.username)}/${enc(pl.password)}/$id.ts",
                        streamId = id,
                        catchup = catchup
                    ))
                }
            }
        }
        out
    }

    suspend fun xtreamSeriesExpanded(pl: Playlist, seriesId: String): List<Item> = withContext(Dispatchers.IO) {
        val obj = httpObject(xtreamApi(pl, "get_series_info", "&series_id=${enc(seriesId)}"))
        val episodes = obj.optJSONObject("episodes") ?: JSONObject()
        val keyList = ArrayList<String>()
        val keys = episodes.keys()
        while (keys.hasNext()) keyList.add(keys.next())
        keyList.sortBy { it.toIntOrNull() ?: Int.MAX_VALUE }
        val out = ArrayList<Item>()
        for (sk in keyList) {
            val eps = episodes.optJSONArray(sk) ?: continue
            if (eps.length() == 0) continue
            out.add(Item(name = "Saison $sk", kind = "header"))
            for (i in 0 until eps.length()) {
                val e = eps.optJSONObject(i) ?: continue
                val id = e.optString("id")
                val ext = e.optString("container_extension", "mp4").ifBlank { "mp4" }
                val epNum = e.optString("episode_num")
                val title = e.optString("title").ifBlank { "Episode $epNum" }
                val info = e.optJSONObject("info")
                val epLogo = e.optString("movie_image")
                    .ifBlank { e.optString("episode_image") }
                    .ifBlank { e.optString("cover") }
                    .ifBlank { e.optString("image") }
                    .ifBlank { e.optString("screenshot_uri") }
                    .ifBlank { info?.optString("movie_image") ?: "" }
                    .ifBlank { info?.optString("cover") ?: "" }
                    .ifBlank { info?.optString("image") ?: "" }
                    .ifBlank { info?.optString("screenshot_uri") ?: "" }
                val epSummary = e.optString("plot")
                    .ifBlank { e.optString("description") }
                    .ifBlank { e.optString("overview") }
                    .ifBlank { e.optString("synopsis") }
                    .ifBlank { info?.optString("plot") ?: "" }
                    .ifBlank { info?.optString("description") ?: "" }
                    .ifBlank { info?.optString("overview") ?: "" }
                    .ifBlank { info?.optString("synopsis") ?: "" }
                out.add(Item(
                    name = title,
                    logo = epLogo,
                    kind = "episode",
                    directUrl = "${pl.serverUrl}/series/${enc(pl.username)}/${enc(pl.password)}/$id.$ext",
                    summary = epSummary
                ))
            }
        }
        out
    }



    // Resume (synopsis) de la SERIE entiere pour Xtream : lu dans le bloc "info" de
    // get_series_info. 100% additif : renvoie "" en cas d'absence ou d'erreur.
    suspend fun xtreamSeriesPlot(pl: Playlist, seriesId: String): String = withContext(Dispatchers.IO) {
        if (seriesId.isBlank()) return@withContext ""
        val obj = try { httpObject(xtreamApi(pl, "get_series_info", "&series_id=${enc(seriesId)}")) } catch (e: Exception) { return@withContext "" }
        val info = obj.optJSONObject("info") ?: return@withContext ""
        val raw = info.optString("plot")
            .ifBlank { info.optString("description") }
            .ifBlank { info.optString("overview") }
            .ifBlank { info.optString("synopsis") }
        decodeXtreamText(raw).trim()
    }

    /** Nettoie un resume : gere un eventuel Base64 et rejette le charabia. */
    fun cleanPlot(raw: String): String = decodeXtreamText(raw)

    private fun looksReadable(s: String): Boolean {
        val t = s.trim()
        if (t.length < 2) return false
        var ok = 0
        for (ch in t) {
            if (ch.isLetterOrDigit() || ch.isWhitespace() || ch in ".,;:!?'\"()[]{}-\u2013\u2014\u2026%\u00b0&/+@#\u2019\u00ab\u00bb*") ok++
        }
        return ok.toDouble() / t.length >= 0.85
    }

    private fun decodeXtreamText(raw: String): String {
        val t = raw.trim()
        if (t.isBlank()) return ""
        // Deja lisible : ne surtout pas tenter de decoder (evite le charabia).
        if (looksReadable(t)) return t
        // Tenter Base64 seulement si le texte en a vraiment la forme.
        if (t.length >= 8 && t.length % 4 == 0 && t.matches(Regex("^[A-Za-z0-9+/=]+\$"))) {
            try {
                val decoded = String(android.util.Base64.decode(t, android.util.Base64.DEFAULT), Charsets.UTF_8).trim()
                if (looksReadable(decoded)) return decoded
            } catch (e: Exception) {}
        }
        // Ni lisible, ni Base64 valide : on n'affiche pas de charabia.
        return ""
    }

    // Decodage dedie a l'EPG Xtream : les titres/descriptions sont souvent en Base64.
    // On decode quand c'est possible ET lisible, sinon on renvoie le texte tel quel.
    // Contrairement a decodeXtreamText (resumes de series), on ne renvoie JAMAIS vide,
    // sinon les entrees EPG disparaissent completement.
    private fun decodeEpgText(raw: String): String {
        val t = raw.trim()
        if (t.isBlank()) return ""
        if (t.length >= 4 && t.length % 4 == 0 && t.matches(Regex("^[A-Za-z0-9+/=]+\$"))) {
            try {
                val decoded = String(android.util.Base64.decode(t, android.util.Base64.DEFAULT), Charsets.UTF_8).trim()
                if (decoded.isNotBlank() && looksReadable(decoded)) return decoded
            } catch (e: Exception) {}
        }
        return t
    }

    suspend fun xtreamShortEpg(pl: Playlist, streamId: String, limit: Int = 6): List<EpgEntry> = withContext(Dispatchers.IO) {
        if (streamId.isBlank()) return@withContext emptyList()
        val obj = httpObject(xtreamApi(pl, "get_short_epg", "&stream_id=${enc(streamId)}&limit=$limit"))
        val arr = obj.optJSONArray("epg_listings") ?: obj.optJSONArray("data") ?: JSONArray()
        val out = ArrayList<EpgEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = decodeEpgText(o.optString("title").ifBlank { o.optString("name") })
            val desc = decodeEpgText(o.optString("description").ifBlank { o.optString("descr") })
            val start = o.optString("start").ifBlank { o.optString("start_timestamp") }
            val end = o.optString("end").ifBlank { o.optString("stop").ifBlank { o.optString("end_timestamp") } }
            val time = when {
                start.isNotBlank() && end.isNotBlank() -> "$start - $end"
                start.isNotBlank() -> start
                else -> ""
            }
            if (title.isNotBlank() || desc.isNotBlank()) out.add(EpgEntry(title.ifBlank { "Programme" }, time, desc))
        }
        out
    }

    private val epgCache = ConcurrentHashMap<String, List<EpgEntry>>()

    private fun fmtClock(ms: Long): String {
        if (ms <= 0L) return ""
        return try {
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))
        } catch (e: Exception) { "" }
    }

    // EPG complet d'une chaine Xtream (get_simple_data_table) : horaires HH:mm + programme en cours.
    suspend fun xtreamFullEpg(pl: Playlist, streamId: String): List<EpgEntry> = withContext(Dispatchers.IO) {
        if (streamId.isBlank()) return@withContext emptyList()
        val key = pl.id + ":" + streamId
        epgCache[key]?.let { return@withContext it }
        val obj = try { httpObject(xtreamApi(pl, "get_simple_data_table", "&stream_id=" + enc(streamId))) } catch (e: Exception) { JSONObject() }
        val arr = obj.optJSONArray("epg_listings") ?: obj.optJSONArray("data") ?: JSONArray()
        val now = System.currentTimeMillis()
        val out = ArrayList<EpgEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = decodeEpgText(o.optString("title").ifBlank { o.optString("name") })
            if (title.isBlank()) continue
            val desc = decodeEpgText(o.optString("description").ifBlank { o.optString("descr") })
            val startTs = (o.optString("start_timestamp").toLongOrNull() ?: 0L) * 1000L
            val stopTs = (o.optString("stop_timestamp").ifBlank { o.optString("end_timestamp") }.toLongOrNull() ?: 0L) * 1000L
            val nowFlag = o.optString("now_playing") == "1" || (startTs in 1 until now && stopTs > now)
            val time = when {
                startTs > 0 && stopTs > 0 -> fmtClock(startTs) + " - " + fmtClock(stopTs)
                startTs > 0 -> fmtClock(startTs)
                else -> ""
            }
            out.add(EpgEntry(title, time, desc, nowFlag, startTs, stopTs))
        }
        val upcoming = out.filter { it.endMs == 0L || it.endMs >= now - 1800000L }
        val result = if (upcoming.isNotEmpty()) upcoming else out
        epgCache[key] = result
        result
    }

    // EPG Stalker/MAG : la plupart des portails exposent le guide via
    // type=itv&action=get_short_epg&ch_id=<id de la chaine>. On reutilise le meme
    // EpgEntry que pour Xtream pour que l'affichage de l'apercu soit identique.
    suspend fun stalkerShortEpg(pl: Playlist, chId: String): List<EpgEntry> = withContext(Dispatchers.IO) {
        if (chId.isBlank()) return@withContext emptyList()
        val key = "stalker:" + pl.id + ":" + chId
        epgCache[key]?.let { return@withContext it }
        val portal = ensureStalker(pl) ?: return@withContext emptyList()
        val txt = stbCall(pl, portal, "type=itv&action=get_short_epg&ch_id=${enc(chId)}&size=10&JsHttpRequest=1-xml")
        val out = ArrayList<EpgEntry>()
        try {
            val root = JSONObject(txt)
            val jsAny = root.opt("js")
            val arr = when (jsAny) {
                is JSONArray -> jsAny
                is JSONObject -> jsAny.optJSONArray("data") ?: jsAny.optJSONArray("epg") ?: JSONArray()
                else -> JSONArray()
            }
            val now = System.currentTimeMillis()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val title = o.optString("name").ifBlank { o.optString("title") }
                if (title.isBlank()) continue
                val desc = o.optString("descr").ifBlank { o.optString("description") }
                val startTs = (o.optString("start_timestamp").toLongOrNull() ?: 0L) * 1000L
                val stopTs = (o.optString("stop_timestamp").toLongOrNull() ?: 0L) * 1000L
                // t_time / t_time_to sont deja formates HH:mm par le portail quand presents.
                val tStart = o.optString("t_time")
                val tEnd = o.optString("t_time_to")
                val time = when {
                    tStart.isNotBlank() && tEnd.isNotBlank() -> "$tStart - $tEnd"
                    tStart.isNotBlank() -> tStart
                    startTs > 0 && stopTs > 0 -> fmtClock(startTs) + " - " + fmtClock(stopTs)
                    startTs > 0 -> fmtClock(startTs)
                    else -> ""
                }
                val nowFlag = (startTs in 1 until now && stopTs > now)
                out.add(EpgEntry(title, time, desc, nowFlag, startTs, stopTs))
            }
        } catch (e: Exception) {}
        if (out.isNotEmpty()) epgCache[key] = out
        out
    }

    suspend fun xtreamVodInfo(pl: Playlist, streamId: String): VodMeta = withContext(Dispatchers.IO) {
        val obj = httpObject(xtreamApi(pl, "get_vod_info", "&vod_id=${enc(streamId)}"))
        val info = obj.optJSONObject("info") ?: JSONObject()
        val plot = info.optString("plot").ifBlank { info.optString("description") }
        var dur = info.optString("duration")
        if (dur.isBlank()) {
            val secs = info.optString("duration_secs").toLongOrNull()
            if (secs != null && secs > 0) {
                val h = secs / 3600; val mn = (secs % 3600) / 60
                dur = if (h > 0) "${h}h ${mn}min" else "${mn}min"
            }
        }
        VodMeta(plot, dur)
    }

    // ---------------- STALKER / MAG PORTAL ----------------
    // Emule un boitier MAG (handshake -> token -> listes -> create_link).
    private const val STB_UA =
        "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG250 stbapp ver: 2 rev: 250 Safari/533.3"

    private data class StbProfile(val model: String, val ua: String, val imageVersion: String, val ver: String)
    private val STB_PROFILES = listOf(
        StbProfile("MAG250", STB_UA, "218", "ImageDescription: 0.2.18-r23-250; ImageDate: Wed Aug 29 2018; PORTAL version: 5.6.2; API Version: JS API version: 343;"),
        StbProfile("MAG254", "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG254 stbapp ver: 2 rev: 254 Safari/533.3", "220", "ImageDescription: 0.2.18-r23-254; ImageDate: Wed Aug 29 2018; PORTAL version: 5.6.2; API Version: JS API version: 343;"),
        StbProfile("MAG322", "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG322 stbapp ver: 2 rev: 322 Safari/533.3", "220", "ImageDescription: 0.2.18-r23-322; ImageDate: Wed Aug 29 2018; PORTAL version: 5.6.2; API Version: JS API version: 343;")
    )
    private var currentStbProfile: StbProfile = STB_PROFILES[0]

    // v355 : mode compatibilite pour les portails MAG250 "stricts" (releve HTTP reel).
    // Il n est JAMAIS actif au premier essai : on ne l active qu apres un echec complet
    // du mode historique, et on le memorise ensuite serveur par serveur. Les serveurs
    // qui fonctionnent aujourd hui reussissent au premier passage et ne passent jamais ici.
    private var stbCompat = false
    private val stbCompatServers = HashSet<String>()

    private fun stbKey(serverUrl: String): String = serverUrl.trim().trimEnd('/')

    // En-tetes supplementaires envoyes par les vrais boitiers MAG sur ces portails.
    private fun stbCompatExtras(pl: Playlist): Map<String, String> {
        if (!stbCompat) return emptyMap()
        val sn = pl.stalkerSn.ifBlank { stbSerial(pl) }
        val did = pl.stalkerDeviceId.ifBlank { stbDeviceId(pl) }
        val did2 = pl.stalkerDeviceId2.ifBlank { did }
        val m = LinkedHashMap<String, String>()
        m["X-Device-Id"] = did
        m["X-Device-Id-2"] = did2
        m["X-Serial-Number"] = sn
        return m
    }

    private var stalkerToken: String? = null
    private var stalkerBase: String? = null
    // Dernier diagnostic de resolution de flux (affiche dans le lecteur en cas d'echec).
    var lastStreamLog: String = ""
    var lastStalkerLog: String = ""

    private fun md5Hex(s: String): String =
        java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun sha256Hex(s: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    // Identite "boitier MAG" derivee du MAC (stable). Beaucoup de portails refusent
    // l'acces au flux (et coupent -> HTTP 444) si sn / device_id / signature sont absents.
    private fun stbSerial(pl: Playlist): String = md5Hex(pl.mac).uppercase().substring(0, 13)
    private fun stbDeviceId(pl: Playlist): String = sha256Hex(pl.mac.uppercase()).uppercase()

    private fun stbCookie(pl: Playlist): String =
        if (stbCompat)
            // Format releve sur un boitier MAG250 reel : mac non encodee, serial present,
            // fuseau en clair. Utilise uniquement en mode compatibilite.
            "mac=" + pl.mac + "; serial=" + pl.stalkerSn.ifBlank { stbSerial(pl) } +
                "; stb_lang=en; timezone=Europe/Paris"
        else
            "mac=" + enc(pl.mac) + "; stb_lang=en; timezone=Europe%2FParis"

    // En-tetes MAG a renvoyer au lecteur pour lire un flux stalker.
    // Sans eux, nginx ferme la connexion -> HTTP 444. A appeler apres create_link (token deja obtenu).
    fun stalkerHeaders(pl: Playlist): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        // En compat, le flux est refuse avec le User-Agent MAG : on utilise celui
        // releve dans la capture reelle.
        m["User-Agent"] = if (stbCompat) "IPTVSmartersPro" else currentStbProfile.ua
        m["Cookie"] = stbCookie(pl)
        m["X-User-Agent"] = "Model: ${currentStbProfile.model}; Link: WiFi"
        m["Referer"] = "${pl.serverUrl}/c/"
        val t = stalkerToken
        if (!t.isNullOrBlank()) m["Authorization"] = "Bearer $t"
        m.putAll(stbCompatExtras(pl))
        return m
    }

    private fun stbCall(pl: Playlist, portal: String, query: String): String {
        fun applyStbHeaders(b: Request.Builder): Request.Builder {
            b.header("User-Agent", if (stbCompat) "Mozilla/5.0 (QtEmbedded; Linux)" else currentStbProfile.ua)
                .header("Accept", "*/*")
                .header("Cookie", stbCookie(pl))
                .header("X-User-Agent", "Model: ${currentStbProfile.model}; Link: WiFi")
                .header("Referer", "${pl.serverUrl}/c/")
            val t = stalkerToken
            if (!t.isNullOrBlank()) b.header("Authorization", "Bearer $t")
            for ((k, v) in stbCompatExtras(pl)) b.header(k, v)
            return b
        }

        fun looksJson(txt: String): Boolean {
            val t = txt.trimStart()
            return t.startsWith("{") || t.startsWith("[")
        }

        fun callGet(): String {
            val b = applyStbHeaders(Request.Builder().url("$portal?$query"))
            return try { client.newCall(b.build()).execute().use { it.body?.string() ?: "" } } catch (e: Exception) { "" }
        }

        fun callPost(): String {
            val form = FormBody.Builder()
            for (part in query.split("&")) {
                if (part.isBlank()) continue
                val i = part.indexOf('=')
                val k = if (i >= 0) part.substring(0, i) else part
                val v = if (i >= 0) part.substring(i + 1) else ""
                // La query est deja encodee par enc(...). addEncoded evite de double-encoder
                // cmd, ver, metrics, etc. Certains portails Stalker acceptent uniquement POST.
                form.addEncoded(k, v)
            }
            val b = applyStbHeaders(Request.Builder().url(portal).post(form.build()))
            return try { client.newCall(b.build()).execute().use { it.body?.string() ?: "" } } catch (e: Exception) { "" }
        }

        val getTxt = callGet()
        if (looksJson(getTxt)) return getTxt
        val postTxt = callPost()
        if (looksJson(postTxt)) return postTxt
        return if (getTxt.isNotBlank()) getTxt else postTxt
    }

    private fun stalkerHandshake(pl: Playlist): Boolean {
        // Mode historique en premier (aucun changement pour les serveurs qui marchent).
        stbCompat = stbCompatServers.contains(stbKey(pl.serverUrl))
        if (stalkerHandshakePass(pl)) return true
        if (stbCompat) { stbCompat = false; return false }
        // Repli unique : profil MAG250 strict releve sur la capture HTTP reelle.
        stbCompat = true
        stalkerToken = null
        stalkerBase = null
        if (stalkerHandshakePass(pl)) {
            stbCompatServers.add(stbKey(pl.serverUrl))
            return true
        }
        stbCompat = false
        return false
    }

    private fun stalkerHandshakePass(pl: Playlist): Boolean {
        if (!hasCustomStalkerProfile(pl)) currentStbProfile = STB_PROFILES[0]
        val tried = StringBuilder()
        val candidates = stalkerPortalCandidates(pl.serverUrl)
        for (portal in candidates) {
            val txt = stbCall(pl, portal, "type=stb&action=handshake&token=&JsHttpRequest=1-xml")
            val tok = try { JSONObject(txt).optJSONObject("js")?.optString("token") } catch (e: Exception) { null }
            tried.append("\n- ").append(portal).append(" -> ").append(if (!tok.isNullOrBlank()) "TOKEN OK" else txt.take(120))
            if (!tok.isNullOrBlank()) {
                stalkerToken = tok
                stalkerBase = portal
                stalkerGetProfile(pl, portal)
                if (hasCustomStalkerProfile(pl)) stalkerAccountInfo(pl, portal)
                lastStalkerLog = "Stalker OK\nPortail: $portal\nProfil: ${currentStbProfile.model}\nMAC: ${pl.mac}\nSN: ${pl.stalkerSn.ifBlank { "auto" }}"
                return true
            }
        }
        lastStalkerLog = "Handshake Stalker impossible\nServeur: ${pl.serverUrl}\nProfil: ${currentStbProfile.model}\nMAC: ${pl.mac}\nSN: ${pl.stalkerSn.ifBlank { "auto" }}\nEssais:$tried"
        return false
    }

    private fun stalkerPortalCandidates(serverUrl: String): List<String> {
        val bases = LinkedHashSet<String>()
        val base = serverUrl.trim().trimEnd('/')
        if (base.isNotBlank()) bases.add(base)
        // Certains portails refusent ou routent mal quand :80 est present dans l'URL,
        // alors que le meme domaine sans port marche.
        if (base.endsWith(":80", true)) bases.add(base.removeSuffix(":80"))

        val out = LinkedHashSet<String>()
        for (b in bases) {
            // Les formats Stalker/MAG les plus courants. On garde portal.php en premier
            // pour les serveurs type 2.900900.me, puis on essaie les variantes sans casser
            // les anciens serveurs.
            out.add("$b/portal.php")
            out.add("$b/stalker_portal/server/load.php")
            out.add("$b/stalker_portal/portal.php")
            out.add("$b/server/load.php")
            out.add("$b/c/portal.php")
            out.add("$b/c/server/load.php")
            out.add("$b/stalker_portal/c/portal.php")
            // Secours : certains players appellent directement la racine :
            // http://serveur/?type=stb&action=handshake...
            out.add(b)
        }
        return out.toList()
    }

    private fun stalkerAccountInfo(pl: Playlist, portal: String) {
        stbCall(pl, portal, "type=account_info&action=get_main_info&JsHttpRequest=1-xml")
    }

    private fun hasCustomStalkerProfile(pl: Playlist): Boolean =
        pl.stalkerSn.isNotBlank() ||
        pl.stalkerDeviceId.isNotBlank() ||
        pl.stalkerDeviceId2.isNotBlank() ||
        pl.stalkerSignature.isNotBlank() ||
        pl.stalkerMetrics.isNotBlank() ||
        pl.stalkerHwVersion2.isNotBlank() ||
        pl.stalkerTimestamp.isNotBlank() ||
        pl.stalkerPrehash.isNotBlank() ||
        pl.stalkerApiSignature.isNotBlank() ||
        pl.stalkerImageVersion.isNotBlank() ||
        pl.stalkerVer.isNotBlank()

    // Profil complet facon MAG250 : indispensable sur les portails "proteges".
    // Certains serveurs valident un profil exact fourni par le fournisseur/SFVipPlayer :
    // sn + device_id + device_id2 + signature + metrics + hw_version_2 + timestamp + prehash.
    private fun stalkerGetProfile(pl: Playlist, portal: String) {
        if (!hasCustomStalkerProfile(pl)) {
            // Mode historique stable pour les Stalker classiques : ne pas leur envoyer
            // les champs speciaux ajoutes pour les portails avec SN obligatoire.
            val sn = stbSerial(pl)
            val did = stbDeviceId(pl)
            val sig = sha256Hex((pl.mac + sn).uppercase()).uppercase()
            val ver = enc(currentStbProfile.ver)
            val metrics = enc("{\"mac\":\"${pl.mac}\",\"sn\":\"$sn\",\"model\":\"${currentStbProfile.model}\",\"type\":\"STB\",\"uid\":\"$did\"}")
            val q = if (stbCompat) {
                // Premier get_profile, copie exacte de la capture d un MAG250 :
                // pas de hd=1, api_signature 263, timestamp, hw_version_2, metrics avec random.
                val metricsCompat = enc(
                    "{\"mac\":\"${pl.mac}\",\"sn\":\"$sn\",\"model\":\"${currentStbProfile.model}\"," +
                        "\"type\":\"STB\",\"uid\":\"\",\"random\":\"${(100000..999999).random()}\"}"
                )
                "type=stb&action=get_profile&ver=$ver" +
                    "&num_banks=2&sn=$sn&stb_type=${currentStbProfile.model}&image_version=${currentStbProfile.imageVersion}" +
                    "&video_out=hdmi&device_id=$did&device_id2=$did&signature=$sig" +
                    "&auth_second_step=1&hw_version=1.7-BD-00&not_valid_token=0&client_type=STB" +
                    "&hw_version_2=334&timestamp=${System.currentTimeMillis() / 1000}" +
                    "&api_signature=263&metrics=$metricsCompat&JsHttpRequest=1-xml"
            } else {
                "type=stb&action=get_profile&hd=1&ver=$ver" +
                    "&num_banks=2&sn=$sn&stb_type=${currentStbProfile.model}&client_type=STB&image_version=${currentStbProfile.imageVersion}" +
                    "&video_out=hdmi&device_id=$did&device_id2=$did&signature=$sig" +
                    "&auth_second_step=1&hw_version=1.7-BD-00&not_valid_token=0&metrics=$metrics" +
                    "&JsHttpRequest=1-xml"
            }
            stbCall(pl, portal, q)
            // Deuxieme get_profile allege : indispensable sur ces portails, ils ne
            // valident la session qu apres ce second appel.
            if (stbCompat) {
                stbCall(pl, portal, "type=stb&action=get_profile&sn=$sn&auth_second_step=1&JsHttpRequest=1-xml")
            }
            return
        }

        fun qv(v: String): String {
            if (v.isBlank()) return ""
            // Si le backend stocke deja la valeur encodee depuis la capture, on ne la double-encode pas.
            return if (v.contains("%")) v else enc(v)
        }

        val sn = pl.stalkerSn.ifBlank { stbSerial(pl) }
        val did = pl.stalkerDeviceId.ifBlank { stbDeviceId(pl) }
        val did2 = pl.stalkerDeviceId2.ifBlank { did }
        val sig = pl.stalkerSignature.ifBlank { sha256Hex((pl.mac + sn).uppercase()).uppercase() }
        val imageVersion = pl.stalkerImageVersion.ifBlank { currentStbProfile.imageVersion }
        val verRaw = pl.stalkerVer.ifBlank {
            currentStbProfile.ver
        }
        val metricsRaw = pl.stalkerMetrics.ifBlank {
            "{\"mac\":\"${pl.mac}\",\"sn\":\"$sn\",\"model\":\"${currentStbProfile.model}\",\"type\":\"STB\",\"uid\":\"$did\"}"
        }

        var q = "type=stb&action=get_profile&hd=1&ver=${qv(verRaw)}" +
            "&num_banks=2&sn=${qv(sn)}&stb_type=${currentStbProfile.model}&client_type=STB&image_version=${qv(imageVersion)}" +
            "&video_out=hdmi&device_id=${qv(did)}&device_id2=${qv(did2)}&signature=${qv(sig)}" +
            "&auth_second_step=1&hw_version=1.7-BD-00&not_valid_token=0&metrics=${qv(metricsRaw)}"

        if (pl.stalkerHwVersion2.isNotBlank()) q += "&hw_version_2=${qv(pl.stalkerHwVersion2)}"
        if (pl.stalkerTimestamp.isNotBlank()) q += "&timestamp=${qv(pl.stalkerTimestamp)}"
        q += "&api_signature=${qv(pl.stalkerApiSignature.ifBlank { if (stbCompat) "263" else "262" })}"
        if (pl.stalkerPrehash.isNotBlank()) q += "&prehash=${qv(pl.stalkerPrehash)}"
        q += "&JsHttpRequest=1-xml"

        stbCall(pl, portal, q)
        // Second get_profile allege (mode compatibilite uniquement).
        if (stbCompat) {
            stbCall(pl, portal, "type=stb&action=get_profile&sn=${qv(sn)}&auth_second_step=1&JsHttpRequest=1-xml")
        }
    }

    private fun ensureStalker(pl: Playlist): String? {
        val base = stalkerBase
        if (stalkerToken.isNullOrBlank() || base == null || !base.startsWith(pl.serverUrl)) {
            stalkerToken = null; stalkerBase = null
            if (!stalkerHandshake(pl)) return null
        }
        return stalkerBase
    }

    suspend fun stalkerCategories(pl: Playlist, kind: String): List<Category> = withContext(Dispatchers.IO) {
        val portal = ensureStalker(pl) ?: return@withContext listOf(Category("__all__", "Tout"))
        val type = when (kind) { "movie" -> "vod"; "series" -> "series"; else -> "itv" }
        val action = if (type == "itv") "get_genres" else "get_categories"
        var txt = stbCall(pl, portal, "type=$type&action=$action&JsHttpRequest=1-xml")
        if (!txt.contains("\"js\"")) {
            // Certains portails repondent mieux sans JsHttpRequest sur les listes.
            txt = stbCall(pl, portal, "type=$type&action=$action")
        }
        val out = ArrayList<Category>()
        out.add(Category("__all__", "Tout"))
        try {
            val js = JSONObject(txt).optJSONArray("js") ?: JSONArray()
            for (i in 0 until js.length()) {
                val o = js.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isBlank() || id == "*" || id == "0") continue
                out.add(Category(id, o.optString("title", o.optString("name"))))
            }
        } catch (e: Exception) {
            lastStalkerLog = "Erreur parsing categories\nPortail: $portal\nAction: $type/$action\nReponse: ${txt.take(500)}"
        }
        if (out.size <= 1) {
            lastStalkerLog = "Aucune categorie Stalker\nPortail: $portal\nAction: $type/$action\nMAC: ${pl.mac}\nSN: ${pl.stalkerSn.ifBlank { "auto" }}\nReponse: ${txt.take(700)}"
        } else {
            lastStalkerLog = "Categories OK: ${out.size - 1}\nPortail: $portal\nAction: $type/$action"
        }
        out
    }

    suspend fun stalkerItems(pl: Playlist, kind: String, categoryId: String): List<Item> = withContext(Dispatchers.IO) {
        val portal = ensureStalker(pl) ?: return@withContext emptyList()
        val type = when (kind) { "movie" -> "vod"; "series" -> "series"; else -> "itv" }
        val sel = if (categoryId == "__all__") "*" else categoryId
        val param = if (type == "itv") "genre" else "category"
        val out = ArrayList<Item>()
        var page = 1
        var totalPages = 1
        while (page <= totalPages && page <= 40) {
            val q = "type=$type&action=get_ordered_list&$param=$sel&fav=0&sortby=number&p=$page&JsHttpRequest=1-xml"
            val txt = stbCall(pl, portal, q)
            try {
                val js = JSONObject(txt).optJSONObject("js") ?: break
                val data = js.optJSONArray("data") ?: JSONArray()
                if (page == 1) {
                    val total = js.optInt("total_items", data.length())
                    val pageSize = if (data.length() > 0) data.length() else 14
                    totalPages = if (pageSize > 0) Math.ceil(total.toDouble() / pageSize).toInt() else 1
                    if (totalPages < 1) totalPages = 1
                }
                if (data.length() == 0) break
                for (i in 0 until data.length()) {
                    val o = data.optJSONObject(i) ?: continue
                    val isSeries = o.optInt("is_series", 0) == 1
                    val catchup = o.optInt("tv_archive", 0) == 1 ||
                        o.optString("tv_archive").equals("1", true) ||
                        o.optString("tv_archive").equals("true", true) ||
                        o.optInt("enable_tv_archive", 0) == 1 ||
                        o.optInt("catchup", 0) == 1
                    out.add(Item(
                        name = o.optString("name", o.optString("title")), added = parseStalkerAdded(o),
                        logo = absLogo(portal, o.optString("logo", o.optString("screenshot_uri"))),
                        kind = if (type == "itv") "live" else if (isSeries) "series" else "movie",
                        cmd = o.optString("cmd"),
                        seriesId = if (isSeries) o.optString("id") else null,
                        description = o.optString("description"),
                        catchup = catchup
                    ))
                }
            } catch (e: Exception) { break }
            page++
        }
        if (out.isEmpty() && type == "itv") {
            out.addAll(fetchStalkerAllChannels(pl, portal, sel))
        }
        out
    }

    private fun fetchStalkerPage(
        pl: Playlist, portal: String, type: String, param: String, sel: String, page: Int
    ): Pair<List<Item>, Int> {
        val q = "type=$type&action=get_ordered_list&$param=$sel&fav=0&sortby=number&p=$page&JsHttpRequest=1-xml"
        val txt = stbCall(pl, portal, q)
        val out = ArrayList<Item>()
        var totalPages = 1
        try {
            val js = JSONObject(txt).optJSONObject("js") ?: return Pair(out, 1)
            val data = js.optJSONArray("data") ?: JSONArray()
            val total = js.optInt("total_items", data.length())
            val pageSize = if (data.length() > 0) data.length() else 14
            totalPages = if (pageSize > 0) Math.ceil(total.toDouble() / pageSize).toInt() else 1
            if (totalPages < 1) totalPages = 1
            for (i in 0 until data.length()) {
                val o = data.optJSONObject(i) ?: continue
                val isSeries = o.optInt("is_series", 0) == 1
                val catchup = o.optInt("tv_archive", 0) == 1 ||
                    o.optString("tv_archive").equals("1", true) ||
                    o.optString("tv_archive").equals("true", true) ||
                    o.optInt("enable_tv_archive", 0) == 1 ||
                    o.optInt("catchup", 0) == 1
                out.add(Item(
                    name = o.optString("name", o.optString("title")), added = parseStalkerAdded(o),
                    logo = absLogo(portal, o.optString("logo", o.optString("screenshot_uri"))),
                    kind = if (type == "itv") "live" else if (isSeries) "series" else "movie",
                    // Pour le live Stalker on garde l'id de la chaine : il sert a recuperer l'EPG
                    // (get_short_epg&ch_id=...). N'impacte pas films/series (ils utilisent cmd/seriesId).
                    streamId = if (type == "itv") o.optString("id").ifBlank { o.optString("ch_id") } else null,
                    cmd = o.optString("cmd"),
                    seriesId = if (isSeries) o.optString("id") else null,
                    description = o.optString("description"),
                    catchup = catchup
                ))
            }
        } catch (e: Exception) {}
        if (out.isEmpty() && type == "itv" && page == 1) {
            val fallback = fetchStalkerAllChannels(pl, portal, sel)
            if (fallback.isNotEmpty()) return Pair(fallback, 1)
        }
        return Pair(out, totalPages)
    }

    private fun fetchStalkerAllChannels(pl: Playlist, portal: String, sel: String): List<Item> {
        val txt = stbCall(pl, portal, "type=itv&action=get_all_channels&JsHttpRequest=1-xml")
        val out = ArrayList<Item>()
        try {
            val root = JSONObject(txt)
            val jsAny = root.opt("js")
            val data = when (jsAny) {
                is JSONArray -> jsAny
                is JSONObject -> jsAny.optJSONArray("data") ?: jsAny.optJSONArray("channels") ?: JSONArray()
                else -> JSONArray()
            }
            for (i in 0 until data.length()) {
                val o = data.optJSONObject(i) ?: continue
                val cat = o.optString("tv_genre_id")
                    .ifBlank { o.optString("genre_id") }
                    .ifBlank { o.optString("category_id") }
                    .ifBlank { o.optString("genre") }
                    .ifBlank { o.optString("category") }
                if (sel != "*" && cat != sel) continue
                val cmd = o.optString("cmd").ifBlank { o.optString("stream_url") }.ifBlank { o.optString("url") }
                out.add(Item(
                    name = o.optString("name", o.optString("title")), added = parseStalkerAdded(o),
                    logo = absLogo(portal, o.optString("logo", o.optString("screenshot_uri"))),
                    kind = "live",
                    // id de la chaine Stalker : utilise pour l'EPG (get_short_epg&ch_id=...).
                    streamId = o.optString("id").ifBlank { o.optString("ch_id") },
                    cmd = cmd,
                    description = o.optString("description"),
                    catchup = true
                ))
            }
            lastStalkerLog = if (out.isNotEmpty()) {
                "Chaines OK via get_all_channels: ${out.size}\nPortail: $portal\nCategorie: $sel"
            } else {
                "Aucune chaine via get_all_channels\nPortail: $portal\nCategorie: $sel\nReponse: ${txt.take(700)}"
            }
        } catch (e: Exception) {
            lastStalkerLog = "Erreur get_all_channels\nPortail: $portal\nCategorie: $sel\nErreur: ${e.message}\nReponse: ${txt.take(700)}"
        }
        return out
    }

    // Chargement rapide : 1re page affichee tout de suite, puis le reste en parallele (paquets de 6).
    suspend fun stalkerItemsPaged(
        pl: Playlist, kind: String, categoryId: String, maxPages: Int = 40,
        onBatch: suspend (List<Item>) -> Unit
    ) = withContext(Dispatchers.IO) {
        val portal = ensureStalker(pl) ?: return@withContext
        val type = when (kind) { "movie" -> "vod"; "series" -> "series"; else -> "itv" }
        val sel = if (categoryId == "__all__") "*" else categoryId
        val param = if (type == "itv") "genre" else "category"

        var activePortal = portal
        var first = fetchStalkerPage(pl, activePortal, type, param, sel, 1)

        // Auto-profil : certains portails acceptent handshake/categories avec un profil MAG,
        // mais ne renvoient le contenu qu'avec un autre User-Agent/modele. Si une playlist a un
        // SN/profil custom et que la liste est vide, on essaie automatiquement plusieurs MAG.
        if (first.first.isEmpty() && hasCustomStalkerProfile(pl)) {
            for (profile in STB_PROFILES) {
                if (profile == currentStbProfile) continue
                stalkerToken = null
                stalkerBase = null
                currentStbProfile = profile
                val p2 = ensureStalker(pl) ?: continue
                val attempt = fetchStalkerPage(pl, p2, type, param, sel, 1)
                if (attempt.first.isNotEmpty()) {
                    activePortal = p2
                    first = attempt
                    lastStalkerLog = "Contenu OK via profil ${profile.model}: ${attempt.first.size}\nPortail: $p2\nCategorie: $sel"
                    break
                }
            }
        }

        val (firstItems, totalPages) = first
        var emitted = 0
        if (firstItems.isNotEmpty()) { onBatch(firstItems); emitted += firstItems.size }

        val last = minOf(totalPages, maxPages)
        var p = 2
        while (p <= last) {
            val end = minOf(p + 5, last)
            val batch = coroutineScope {
                (p..end).map { pg ->
                    async { fetchStalkerPage(pl, activePortal, type, param, sel, pg).first }
                }.awaitAll().flatten()
            }
            if (batch.isNotEmpty()) { onBatch(batch); emitted += batch.size }
            p = end + 1
        }

        // Repli "Tout" : beaucoup de portails Stalker renvoient VIDE pour la categorie * (tout).
        // Dans ce cas on parcourt chaque vraie categorie et on pagine, en streamant les resultats
        // au fur et a mesure. Ainsi la categorie "Tout" fonctionne partout.
        if (emitted == 0 && sel == "*") {
            val cats = try { stalkerCategories(pl, kind) } catch (e: Exception) { emptyList() }
            for (c in cats) {
                if (c.id.startsWith("__")) continue
                val its = try { stalkerItems(pl, kind, c.id) } catch (e: Exception) { emptyList() }
                if (its.isNotEmpty()) onBatch(its)
            }
        }
    }

    suspend fun stalkerLink(pl: Playlist, cmd: String, kind: String): String? = withContext(Dispatchers.IO) {
        val type = if (kind == "movie" || kind == "series") "vod" else "itv"
        var realCmd = cmd
        var extra = ""
        val marker = cmd.indexOf('\u0001')
        if (marker >= 0) {
            realCmd = cmd.substring(0, marker)
            extra = "&series=" + enc(cmd.substring(marker + 1))
        }
        // OPTIMISATION ZAPPING LIVE : beaucoup de portails mettent deja l'URL reelle dans le cmd.
        // Dans ce cas on evite totalement handshake/get_profile/create_link au clic sur une chaine.
        // C'est plus rapide et ca evite aussi les URL malformees renvoyees par certains create_link.
        val directInCmd = cleanCmdUrl(realCmd, pl)
        val isLive = type == "itv"
        val isLocalhost = directInCmd.contains("localhost", true) || directInCmd.contains("127.0.0.1")
        val hasPlayToken = directInCmd.contains("play_token=", true)
        // Stalker classiques : si le cmd contient une URL directe SANS play_token, on garde
        // l'ancien mode rapide qui fonctionnait bien. Si un play_token est deja present, il peut
        // expirer ou etre refuse (HTTP 458) : on repasse par create_link, mais apres avoir retire
        // l'ancien token du cmd pour que le portail genere un lien propre.
        if (isLive && directInCmd.startsWith("http", true) && !isLocalhost && !hasPlayToken) {
            lastStreamLog = "Mode : LIVE direct ultra-rapide (sans portail)\n" +
                "cmd chaine : $realCmd\n" +
                "URL jouee : $directInCmd"
            return@withContext directInCmd
        }

        val portal = ensureStalker(pl) ?: return@withContext null
        val cmdForCreateLink = if (isLive && hasPlayToken) stripLivePlayToken(realCmd) else realCmd
        val txt = stbCall(pl, portal, "type=$type&action=create_link&cmd=${enc(cmdForCreateLink)}$extra&JsHttpRequest=1-xml")
        val raw = try { JSONObject(txt).optJSONObject("js")?.optString("cmd") } catch (e: Exception) { null }
        val cleaned = if (raw.isNullOrBlank()) null else cleanCmdUrl(raw, pl)
        val repaired = if (cleaned.isNullOrBlank()) null else repairEmptyLiveStream(cleaned, directInCmd)
        lastStreamLog = "Mode : create_link ($type)\n" +
            "Portail : $portal\n" +
            "cmd demande : $cmdForCreateLink\n" +
            "reponse cmd : ${raw ?: "(vide)"}\n" +
            "URL jouee : ${repaired ?: "(aucune)"}"
        repaired
    }

    // v365 : ARCHIVE (replay) Stalker / Ministra avec le VRAI protocole du portail.
    // 100% additif : aucun appel LIVE existant n est modifie.
    var lastArchiveStalkerLog: String = ""

    suspend fun stalkerArchiveLinks(
        pl: Playlist,
        chId: String,
        cmd: String,
        startSec: Long,
        durSec: Long
    ): List<String> = withContext(Dispatchers.IO) {
        val out = ArrayList<String>()
        val log = StringBuilder()
        val portal = ensureStalker(pl)
        if (portal == null) {
            lastArchiveStalkerLog = "Portail Stalker injoignable."
            return@withContext out
        }
        // A) liste reelle des enregistrements de la chaine, puis lien du bon enregistrement
        if (chId.isNotBlank()) {
            val q1 = "type=tv_archive&action=get_ordered_list&ch_id=" + enc(chId) +
                "&size=200&p=1&JsHttpRequest=1-xml"
            val t1 = stbCall(pl, portal, q1)
            log.append("get_ordered_list -> ").append(t1.take(140)).append(nl1())
            val recCmd = pickArchiveRecord(t1, startSec)
            if (recCmd != null && recCmd.isNotBlank()) {
                val t2 = stbCall(
                    pl, portal,
                    "type=tv_archive&action=create_link&cmd=" + enc(recCmd) + "&JsHttpRequest=1-xml"
                )
                val raw = try { JSONObject(t2).optJSONObject("js")?.optString("cmd") } catch (e: Exception) { null }
                log.append("create_link(record) -> ").append((raw ?: "(vide)").take(140)).append(nl1())
                if (!raw.isNullOrBlank()) {
                    val u = cleanCmdUrl(raw, pl)
                    if (u.isNotBlank() && !out.contains(u)) out.add(u)
                }
            }
        }
        // B) create_link direct en donnant la position demandee
        if (cmd.isNotBlank()) {
            val variants = ArrayList<String>()
            variants.add(
                "type=tv_archive&action=create_link&cmd=" + enc(cmd) + "&stream=" + enc(chId) +
                    "&start=" + startSec + "&duration=" + durSec + "&JsHttpRequest=1-xml"
            )
            variants.add(
                "type=itv&action=create_link&cmd=" + enc(cmd) + "&start=" + startSec +
                    "&duration=" + durSec + "&JsHttpRequest=1-xml"
            )
            variants.add(
                "type=tv_archive&action=create_link&cmd=" + enc(cmd) + "&JsHttpRequest=1-xml"
            )
            for (q in variants) {
                val t = stbCall(pl, portal, q)
                val raw = try { JSONObject(t).optJSONObject("js")?.optString("cmd") } catch (e: Exception) { null }
                log.append("create_link -> ").append((raw ?: "(vide)").take(140)).append(nl1())
                if (!raw.isNullOrBlank()) {
                    val u = cleanCmdUrl(raw, pl)
                    if (u.isNotBlank() && !out.contains(u)) out.add(u)
                }
            }
        }
        lastArchiveStalkerLog = log.toString()
        out
    }

    private fun nl1(): Char = 10.toChar()

    // Choisit l enregistrement dont l heure de debut colle a celle demandee.
    private fun pickArchiveRecord(txt: String, startSec: Long): String? = try {
        val js = JSONObject(txt).optJSONObject("js")
        val arr = js?.optJSONArray("data")
        var best: String? = null
        var bestDiff = Long.MAX_VALUE
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val c = o.optString("cmd")
                if (c.isBlank()) continue
                var st = o.optString("start_timestamp").toLongOrNull() ?: 0L
                if (st <= 0L) st = o.optLong("start_timestamp", 0L)
                if (st <= 0L) continue
                val diff = if (st > startSec) st - startSec else startSec - st
                if (diff < bestDiff) { bestDiff = diff; best = c }
            }
        }
        if (best != null && bestDiff <= 5400L) best else null
    } catch (e: Exception) { null }

    private fun stripLivePlayToken(cmd: String): String {
        return cmd
            .replace(Regex("(?i)([?&])play_token=[^&]*&?")) { m -> if (m.groupValues[1] == "?") "?" else "&" }
            .replace(Regex("[?&]$"), "")
            .replace("?&", "?")
            .replace("&&", "&")
    }

    private fun repairEmptyLiveStream(url: String, originalUrl: String): String {
        val originalStream = Regex("(?i)(?:\\?|&)stream=([^&]+)").find(originalUrl)?.groupValues?.getOrNull(1)
        if (originalStream.isNullOrBlank()) return url
        val emptyStream = Regex("(?i)([?&]stream=)(?=&|$)")
        if (!emptyStream.containsMatchIn(url)) return url
        return emptyStream.replace(url) { m -> m.groupValues[1] + originalStream }
    }

    suspend fun stalkerSeasons(pl: Playlist, seriesId: String): List<Item> = withContext(Dispatchers.IO) {
        val portal = ensureStalker(pl) ?: return@withContext emptyList()
        val out = ArrayList<Item>()
        var page = 1
        var totalPages = 1
        var idx = 1
        while (page <= totalPages && page <= 20) {
            val q = "type=series&action=get_ordered_list&movie_id=${enc(seriesId)}&season_id=0&episode_id=0&p=$page&JsHttpRequest=1-xml"
            val txt = stbCall(pl, portal, q)
            try {
                val js = JSONObject(txt).optJSONObject("js") ?: break
                val data = js.optJSONArray("data") ?: JSONArray()
                if (page == 1) {
                    val total = js.optInt("total_items", data.length())
                    val pageSize = if (data.length() > 0) data.length() else 14
                    totalPages = if (pageSize > 0) Math.ceil(total.toDouble() / pageSize).toInt() else 1
                    if (totalPages < 1) totalPages = 1
                }
                if (data.length() == 0) break
                for (i in 0 until data.length()) {
                    val o = data.optJSONObject(i) ?: continue
                    val name = o.optString("name", o.optString("title"))
                    val cmd = o.optString("cmd")
                    val logo = o.optString("screenshot_uri").ifBlank { o.optString("logo") }
                    val eps = o.optJSONArray("series")
                    if (eps != null && eps.length() > 0) {
                        val csv = StringBuilder()
                        for (k in 0 until eps.length()) {
                            if (k > 0) csv.append(",")
                            csv.append(eps.optInt(k))
                        }
                        val title = if (name.isBlank()) "Saison $idx" else name
                        val seasonNum = Regex("\\d+").find(title)?.value?.toIntOrNull() ?: idx
                        out.add(Item(name = title, logo = logo, kind = "season", cmd = cmd, description = csv.toString(), season = seasonNum))
                        idx++
                    } else {
                        out.add(Item(name = name, logo = logo, kind = "movie", cmd = cmd))
                    }
                }
            } catch (e: Exception) { break }
            page++
        }
        out.sortedWith(compareBy<Item> { it.season }.thenBy { it.name.lowercase() })
    }

    fun stalkerSeasonEpisodes(season: Item): List<Item> {
        val out = ArrayList<Item>()
        val cmd = season.cmd ?: return out
        if (season.summary.isNotBlank()) {
            try {
                val arr = JSONArray(season.summary)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val epId = o.optString("id")
                        .ifBlank { o.optString("episode_id") }
                        .ifBlank { o.optString("series_id") }
                        .ifBlank { o.optString("number") }
                        .ifBlank { o.optString("episode_num") }
                    if (epId.isBlank()) continue
                    val epName = o.optString("name")
                        .ifBlank { o.optString("title") }
                        .ifBlank { "Episode $epId" }
                    val epLogo = o.optString("screenshot_uri")
                        .ifBlank { o.optString("logo") }
                        .ifBlank { o.optString("cover") }
                        .ifBlank { o.optString("image") }
                    val epSummary = o.optString("description")
                        .ifBlank { o.optString("plot") }
                        .ifBlank { o.optString("overview") }
                        .ifBlank { o.optString("synopsis") }
                    out.add(Item(name = epName, logo = epLogo, kind = "episode", cmd = "$cmd\u0001$epId", summary = epSummary))
                }
                if (out.isNotEmpty()) return out
            } catch (e: Exception) {}
        }
        val parts = season.description.split(",").mapNotNull { it.trim().toIntOrNull() }.sorted()
        for (ep in parts) {
            out.add(Item(name = "Episode $ep", kind = "episode", cmd = "$cmd\u0001$ep"))
        }
        return out
    }

    suspend fun stalkerSeriesExpanded(pl: Playlist, seriesId: String): List<Item> {
        val seasons = stalkerSeasons(pl, seriesId)
        val out = ArrayList<Item>()
        for (s in seasons) {
            if (s.kind == "season") {
                out.add(Item(name = s.name, kind = "header"))
                out.addAll(stalkerSeasonEpisodes(s))
            } else {
                out.add(s.copy(kind = "episode"))
            }
        }
        return out
    }

    private fun cleanCmdUrl(cmd: String, pl: Playlist? = null): String {
        val c = cmd.trim()
        // 1) cas courant : "ffmpeg http://..." -> on prend le jeton qui commence par http
        val tokens = c.split(" ", "\t").filter { it.isNotBlank() }
        tokens.lastOrNull { it.startsWith("http", true) }?.let { return it.trim() }
        // 2) un http present mais colle a autre chose
        val idx = c.indexOf("http", ignoreCase = true)
        if (idx >= 0) return c.substring(idx).substringBefore(" ").trim()
        // 3) chemin relatif renvoye par le portail -> on prefixe le serveur
        if (pl != null && c.startsWith("/")) return pl.serverUrl.trimEnd('/') + c
        return c
    }

    // ---------------- M3U ----------------
    // Cache de la playlist M3U complete (classee live/movie/series) : telechargee une seule fois.
    // v390 : User-Agent impose par la playlist (#EXTVLCOPT:http-user-agent / #EXTHTTP).
    // Certains fournisseurs ne renvoient RIEN sans lui : la chaine ne demarrait jamais.
    val m3uUserAgents = java.util.Collections.synchronizedMap(HashMap<String, String>())
    private var m3uCacheKey: String? = null
    private var m3uCache: List<Item> = emptyList()

    // Devine le type d'une entree M3U a partir du group-title et de l'URL.
    private fun classifyM3u(group: String, url: String): String {
        val g = group.lowercase()
        val u = url.lowercase()
        return when {
            u.contains("/series/") || g.contains("serie") || g.contains("s\u00e9ri") ||
                g.contains("tv show") || g.contains("shows") -> "series"
            u.contains("/movie/") || u.contains("/movies/") || u.contains("/vod/") ||
                g.contains("film") || g.contains("movie") || g.contains("vod") ||
                g.contains("cinema") || g.contains("cin\u00e9") -> "movie"
            else -> "live"
        }
    }

    // Enleve SxxExx / "Saison x" / "Episode x" / 1x02 pour regrouper les episodes par serie.
    private fun cleanSeriesName(raw: String): String {
        var s = raw
        s = Regex("(?i)\\s*[sS]\\s*\\d{1,2}\\s*[eE]\\s*\\d{1,3}.*$").replace(s, "")
        s = Regex("(?i)\\s*saison\\s*\\d+.*$").replace(s, "")
        s = Regex("(?i)\\s*season\\s*\\d+.*$").replace(s, "")
        s = Regex("(?i)\\s*-?\\s*episode\\s*\\d+.*$").replace(s, "")
        s = Regex("\\s*\\b\\d{1,2}x\\d{1,3}\\b.*$").replace(s, "")
        return s.trim().trim('-', '\u2013', '|', ':').trim().ifBlank { raw.trim() }
    }

    // Telecharge (une seule fois) et classe toute la playlist M3U, avec repli sur plusieurs URL.
    private suspend fun m3uAll(pl: Playlist): List<Item> = withContext(Dispatchers.IO) {
        val key = pl.id + "|" + pl.m3uUrl + "|" + pl.serverUrl + "|" + pl.username
        if (m3uCacheKey == key && m3uCache.isNotEmpty()) return@withContext m3uCache

        val candidates = ArrayList<String>()
        if (pl.m3uUrl.isNotBlank()) candidates.add(pl.m3uUrl)
        if (pl.serverUrl.isNotBlank() && pl.username.isNotBlank()) {
            candidates.add("${pl.serverUrl}/get.php?username=${enc(pl.username)}&password=${enc(pl.password)}&type=m3u_plus&output=ts")
            candidates.add("${pl.serverUrl}/get.php?username=${enc(pl.username)}&password=${enc(pl.password)}&type=m3u&output=ts")
        }
        if (pl.serverUrl.isNotBlank()) candidates.add(pl.serverUrl)

        var parsed: List<Item> = emptyList()
        for (u in candidates) {
            val txt = try { httpText(u) } catch (e: Exception) { "" }
            val looksLikeM3u = txt.contains("#EXTINF") || txt.contains("#EXTM3U") ||
                txt.lineSequence().any { it.trim().startsWith("http") }
            if (looksLikeM3u) {
                val list = parseM3u(txt)
                if (list.isNotEmpty()) { parsed = list; break }
            }
        }
        m3uCacheKey = key
        m3uCache = parsed
        parsed
    }

    // Categories M3U = les "group-title" rencontres pour ce type (live/movie/series).
    suspend fun m3uCategories(pl: Playlist, kind: String): List<Category> = withContext(Dispatchers.Default) {
        val all = m3uAll(pl)
        val groups = LinkedHashSet<String>()
        for (it in all) if (it.kind == kind && it.description.isNotBlank()) groups.add(it.description)
        val out = ArrayList<Category>()
        out.add(Category("__all__", "Tout"))
        if (groups.isEmpty()) {
            // v390 : playlist sans group-title -> AUCUNE categorie ne s affichait.
            // On fabrique les categories a partir du prefixe du nom (FR, BE, UK...)
            // ou, a defaut, par lettre. Il y a donc toujours quelque chose a ouvrir.
            val prefixes = LinkedHashSet<String>()
            for (e in all) {
                if (e.kind != kind) continue
                val p = m3uPrefix(e.name)
                if (p.isNotBlank()) prefixes.add(p)
            }
            for (g in prefixes.sorted()) out.add(Category("__pfx__" + g, g))
        } else {
            for (g in groups.sorted()) out.add(Category(g, g))
        }
        out
    }

    // Elements M3U filtres par type + categorie ; les series sont regroupees par nom.
    suspend fun m3uItems(pl: Playlist, kind: String, categoryId: String): List<Item> = withContext(Dispatchers.Default) {
        val all = m3uAll(pl)
        val matchCat = { it: Item ->
            categoryId == "__all__" ||
                it.description == categoryId ||
                // v390 : categories fabriquees a partir du prefixe du nom.
                (categoryId.startsWith("__pfx__") &&
                    m3uPrefix(it.name) == categoryId.removePrefix("__pfx__"))
        }
        if (kind == "series") {
            val map = LinkedHashMap<String, Item>()
            for (e in all) {
                if (e.kind != "series" || !matchCat(e)) continue
                val sn = cleanSeriesName(e.name)
                if (!map.containsKey(sn)) {
                    map[sn] = Item(name = sn, logo = e.logo, kind = "series", seriesId = sn, description = e.description)
                }
            }
            map.values.toList()
        } else {
            all.filter { it.kind == kind && matchCat(it) }
        }
    }

    // Episodes d'une serie M3U (regroupes par nom de serie).
    suspend fun m3uSeriesExpanded(pl: Playlist, seriesName: String): List<Item> = withContext(Dispatchers.Default) {
        val all = m3uAll(pl)
        all.filter { it.kind == "series" && cleanSeriesName(it.name) == seriesName }
            .sortedBy { it.name.lowercase() }
            .map { it.copy(kind = "episode") }
    }

    // v390 : PARSEUR M3U UNIVERSEL.
    // Corrige les playlists qui ne donnaient rien du tout ou aucune categorie :
    // - fins de ligne Windows et Mac (CRLF, CR seul) et marque BOM en debut de fichier
    // - URL collee sur la meme ligne que #EXTINF
    // - group-title sans guillemets, tvg-group, category
    // - #EXTVLCOPT:http-user-agent et #EXTHTTP (User-Agent impose par le fournisseur)
    fun parseM3u(txt: String): List<Item> {
        val out = ArrayList<Item>()
        var name = ""
        var logo = ""
        var group = ""
        var summary = ""
        var ua = ""
        fun ajoute(u: String) {
            val kind = classifyM3u(group, u)
            val catchup = group.contains("catch", true) || group.contains("replay", true) || group.contains("archive", true)
            out.add(Item(name = name.ifBlank { u }, logo = logo, kind = kind, directUrl = u, description = group, summary = summary, catchup = catchup))
            if (ua.isNotBlank()) { try { m3uUserAgents[u] = ua } catch (e: Throwable) {} }
            name = ""; logo = ""; group = ""; summary = ""; ua = ""
        }
        val propre = txt.replace("\r\n", "\n").replace('\r', '\n')
        for (raw in propre.split('\n')) {
            val line = raw.trim().removePrefix("\uFEFF").trim()
            if (line.startsWith("#EXTINF")) {
                var info = line
                var urlCollee = ""
                val m = Regex("(?i)\\s(https?://[^\\s]+)$").find(line)
                if (m != null) {
                    urlCollee = m.groupValues[1]
                    info = line.substring(0, m.range.first).trim()
                }
                name = info.substringAfterLast(',').trim()
                logo = Regex("tvg-logo=\"([^\"]*)\"").find(info)?.groupValues?.get(1) ?: ""
                group = Regex("group-title=\"([^\"]*)\"").find(info)?.groupValues?.get(1)
                    ?: Regex("(?i)group-title=([^\\s\",]+)").find(info)?.groupValues?.get(1)
                    ?: Regex("(?i)tvg-group=\"([^\"]*)\"").find(info)?.groupValues?.get(1)
                    ?: Regex("(?i)category=\"([^\"]*)\"").find(info)?.groupValues?.get(1) ?: ""
                summary = Regex("(?:plot|description|desc|overview|synopsis)=\"([^\"]*)\"").find(info)?.groupValues?.get(1) ?: ""
                if (name.isBlank()) {
                    name = Regex("tvg-name=\"([^\"]*)\"").find(info)?.groupValues?.get(1) ?: ""
                }
                if (urlCollee.isNotBlank()) ajoute(urlCollee)
            } else if (line.startsWith("#EXTVLCOPT", true)) {
                val v = Regex("(?i)http-user-agent\\s*=\\s*(.+)$").find(line)?.groupValues?.get(1)?.trim()
                if (!v.isNullOrBlank()) ua = v.trim('\'').trim()
            } else if (line.startsWith("#EXTHTTP", true)) {
                val v = Regex("(?i)\"user-agent\"\\s*:\\s*\"([^\"]+)\"").find(line)?.groupValues?.get(1)
                if (!v.isNullOrBlank()) ua = v
            } else if (line.isNotEmpty() && !line.startsWith("#")) {
                ajoute(line)
            }
        }
        return out
    }

    // v390 : prefixe de pays / groupe deduit du nom (|FR| ..., FR: ..., [FR] ...).
    // Sert a fabriquer des categories quand la playlist n en fournit aucune.
    fun m3uPrefix(nom: String): String {
        val n = nom.trim()
        val m = Regex("^[\\[|(]?\\s*([A-Za-z]{2,12})\\s*[\\]|):.-]").find(n)
        val p = m?.groupValues?.get(1)?.uppercase().orEmpty()
        if (p.isNotBlank()) return p
        val c = n.firstOrNull() ?: return ""
        return if (c.isLetter()) c.uppercaseChar().toString() else "#"
    }

    // ---------------- RECHERCHE GLOBALE (multi-serveurs) ----------------
    // Cache des catalogues deja telecharges (par serveur + type) pour des recherches rapides.
    // Cache LRU borne : au plus MAX_CACHED_CATALOGS catalogues gardes en memoire a la fois.
    // Avec beaucoup de serveurs, garder TOUS les catalogues saturait la memoire et l'appli
    // etait tuee (retour brutal a l'accueil). On ne garde donc que les plus recents.
    private val MAX_CACHED_CATALOGS = 5
    private val catalogCache = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, List<Item>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Item>>): Boolean =
                size > MAX_CACHED_CATALOGS
        }
    )
    // Au plus 2 gros catalogues (Xtream/M3U) telecharges EN MEME TEMPS, quelle que soit
    // l'origine (recherche ou prechargement) -> pas de saturation memoire.
    private val catalogSem = Semaphore(2)

    private suspend fun catalogFor(pl: Playlist, kind: String): List<Item> {
        val key = "${pl.id}:$kind"
        catalogCache[key]?.let { return it }
        val items = catalogSem.withPermit {
            // Re-verifie le cache : un autre appel (recherche ou prechargement) a pu le
            // remplir pendant l'attente du permis -> on evite un double telechargement.
            catalogCache[key] ?: try {
                when (pl.type) {
                    "m3u" -> m3uItems(pl, kind, "__all__")
                    "stalker" -> stalkerCatalogAll(pl, kind)
                    else -> xtreamItems(pl, kind, "__all__")
                }
            } catch (e: Exception) { emptyList() }
        }
        // On ne met en cache que les resultats non vides (evite de figer un echec reseau).
        if (items.isNotEmpty()) catalogCache[key] = items
        return items
    }

    /**
     * Prechauffe en arriere-plan le cache des catalogues Films/Series de TOUS les serveurs.
     * But : quand l'utilisateur tape sa recherche, tout est deja pret -> resultats quasi
     * instantanes, meme avec beaucoup de serveurs. Borne par catalogSem (max 3 a la fois),
     * donc aucun risque de saturation. Ne refait rien si deja en cache. Stalker est ignore
     * (il cherche cote portail, pas besoin de precharger). 100% additif.
     */
    suspend fun prefetchCatalogs(playlists: List<Playlist>, kind: String) {
        if (kind != "movie" && kind != "series") return
        val curId = Session.current?.id
        // Serveur courant en premier (c'est celui recherche en priorite).
        val ordered = playlists.sortedByDescending { it.id == curId }
        var warmed = 0
        // SEQUENTIEL (un catalogue a la fois) + garde-memoire : le prechargement ne doit
        // JAMAIS faire planter l'appli. On s'arrete des que la memoire se remplit.
        for (pl in ordered) {
            if (warmed >= MAX_CACHED_CATALOGS - 1) break // pas plus que le cache ne garde
            if (pl.type == "stalker") continue // Stalker cherche cote portail : rien a precharger
            val key = "${pl.id}:$kind"
            if (catalogCache.containsKey(key)) { warmed++; continue }
            val rt = Runtime.getRuntime()
            if (rt.totalMemory() - rt.freeMemory() > rt.maxMemory() * 6 / 10) break
            try { withTimeoutOrNull(15000L) { catalogFor(pl, kind) } } catch (e: Exception) {}
            warmed++
        }
    }

    // Stalker : le mode "tout" (*) renvoie souvent vide selon le portail.
    // On tente d'abord *, sinon on parcourt chaque categorie (mis en cache ensuite).
    private suspend fun stalkerCatalogAll(pl: Playlist, kind: String): List<Item> {
        val direct = try { stalkerItems(pl, kind, "__all__") } catch (e: Exception) { emptyList() }
        if (direct.isNotEmpty()) return direct
        val cats = try { stalkerCategories(pl, kind) } catch (e: Exception) { emptyList() }
        val out = ArrayList<Item>()
        for (c in cats) {
            if (c.id.startsWith("__")) continue
            try { out.addAll(stalkerItems(pl, kind, c.id)) } catch (e: Exception) {}
        }
        return out
    }

    private fun foldText(s: String): String {
        val noAccent = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        // Meme normalisation que la recherche classique (accents + ponctuation ignores).
        return noAccent.lowercase()
            .replace("&", " and ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
    }

    /**
     * Recherche multi-serveurs pour la recherche classique Films/Series.
     * Parcourt TOUS les serveurs, fusionne les doublons par titre et renvoie
     * (au fur et a mesure, serveur par serveur) des items prets a afficher :
     * chaque item porte serverLabel ("Serveur 1 / Serveur 2") et ownerPlaylistId
     * (le serveur a activer au clic). 100% additif.
     */
    suspend fun searchAllServers(
        playlists: List<Playlist>,
        query: String,
        kind: String,
        onProgress: suspend (done: Int, total: Int, merged: List<Item>) -> Unit
    ) {
        coroutineScope {
            if (foldText(query.trim()).isBlank()) { onProgress(0, playlists.size, emptyList()); return@coroutineScope }
            val groups = LinkedHashMap<String, MutableList<SearchHit>>()
            val curId = Session.current?.id
            val total = playlists.size
            // Serveur courant en premier (resultats immediats), puis les autres.
            val ordered = playlists.sortedByDescending { it.id == curId }
            // Parallelisation : jusqu'a 5 serveurs interroges EN MEME TEMPS (recherche native
            // Stalker + filtrage sur catalogues deja en cache = leger et rapide). La securite
            // memoire est desormais assuree en amont par catalogSem (max 2 gros catalogues
            // charges simultanement), donc plus de saturation meme avec beaucoup de serveurs.
            // Chaque serveur reste limite a 8 s pour ne jamais bloquer l'affichage.
            val sem = Semaphore(5)
            val lock = Any()
            var done = 0
            val jobs = ordered.map { pl ->
                async(Dispatchers.IO) {
                    val items = sem.withPermit {
                        try { withTimeoutOrNull(12000L) { searchServer(pl, query, kind) } ?: emptyList() }
                        catch (e: Exception) { emptyList<Item>() }
                    }
                    // Fusion + progression sequentialisees (thread-safe) puis notification UI.
                    val snapshot = synchronized(lock) {
                        for (item in items) {
                            if (item.kind == "header") continue
                            val gkey = foldText(item.name)
                            if (gkey.isNotBlank()) groups.getOrPut(gkey) { ArrayList() }.add(SearchHit(item, pl))
                        }
                        done++
                        done to buildMerged(groups, curId)
                    }
                    onProgress(snapshot.first, total, snapshot.second)
                }
            }
            jobs.awaitAll()
        }
    }

    // Recherche sur UN serveur. On privilegie la recherche NATIVE du serveur
    // (rapide + fiable) plutot que de telecharger tout le catalogue.
    private suspend fun searchServer(pl: Playlist, query: String, kind: String): List<Item> {
        val tokens = foldText(query).split(" ").filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()
        return try {
            when (pl.type) {
                "stalker" -> {
                    // Stalker sait chercher cote portail via le parametre search.
                    val native = stalkerSearch(pl, kind, query)
                    if (native.isNotEmpty()) native.filter { matchesTokens(it, tokens) }.ifEmpty { native }
                    else catalogFor(pl, kind).filter { matchesTokens(it, tokens) } // secours rare
                }
                // Xtream : 1 seul appel renvoie tout le VOD/series (puis mis en cache).
                // M3U : playlist deja en cache. On filtre localement, c'est rapide.
                else -> catalogFor(pl, kind).filter { matchesTokens(it, tokens) }
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun matchesTokens(item: Item, tokens: List<String>): Boolean {
        if (item.kind == "header") return false
        val hay = foldText(item.name + " " + item.description)
        return tokens.all { hay.contains(it) }
    }

    // Recherche native Stalker : type+action=get_ordered_list&search=...
    // Bornee a quelques pages pour rester rapide.
    private suspend fun stalkerSearch(pl: Playlist, kind: String, query: String): List<Item> = withContext(Dispatchers.IO) {
        val portal = ensureStalker(pl) ?: return@withContext emptyList()
        val type = when (kind) { "movie" -> "vod"; "series" -> "series"; else -> "itv" }
        val param = if (type == "itv") "genre" else "category"
        val out = ArrayList<Item>()
        var page = 1
        var totalPages = 1
        while (page <= totalPages && page <= 5) {
            val q = "type=$type&action=get_ordered_list&$param=*&search=${enc(query)}&fav=0&sortby=number&p=$page&JsHttpRequest=1-xml"
            val txt = stbCall(pl, portal, q)
            try {
                val js = JSONObject(txt).optJSONObject("js") ?: break
                val data = js.optJSONArray("data") ?: JSONArray()
                if (page == 1) {
                    val total = js.optInt("total_items", data.length())
                    val pageSize = if (data.length() > 0) data.length() else 14
                    totalPages = if (pageSize > 0) Math.ceil(total.toDouble() / pageSize).toInt() else 1
                    if (totalPages < 1) totalPages = 1
                }
                if (data.length() == 0) break
                for (i in 0 until data.length()) {
                    val o = data.optJSONObject(i) ?: continue
                    val isSeries = o.optInt("is_series", 0) == 1
                    out.add(Item(
                        name = o.optString("name", o.optString("title")), added = parseStalkerAdded(o),
                        logo = absLogo(portal, o.optString("logo", o.optString("screenshot_uri"))),
                        kind = if (type == "itv") "live" else if (isSeries) "series" else "movie",
                        cmd = o.optString("cmd"),
                        seriesId = if (isSeries) o.optString("id") else null,
                        description = o.optString("description")
                    ))
                }
            } catch (e: Exception) { break }
            page++
        }
        out
    }

    // Fusionne les hits par titre : 1 item affiche + la liste des serveurs qui l'ont.
    private fun buildMerged(groups: Map<String, List<SearchHit>>, curId: String?): List<Item> {
        val out = ArrayList<Item>(groups.size)
        for ((_, hits) in groups) {
            // On garde de preference la copie du serveur courant (clic direct sans bascule).
            val rep = hits.firstOrNull { it.playlist.id == curId } ?: hits.first()
            // Si le serveur courant n'a pas d'affiche pour ce titre mais qu'un autre serveur
            // en a une, on emprunte cette affiche (sans changer le serveur utilise au clic).
            val logo = if (rep.item.logo.isNotBlank()) rep.item.logo
                else hits.firstOrNull { it.item.logo.isNotBlank() }?.item?.logo ?: rep.item.logo
            val names = LinkedHashSet<String>()
            hits.sortedByDescending { it.playlist.id == curId }.forEach { names.add(it.playlist.nom) }
            out.add(rep.item.copy(
                logo = logo,
                serverLabel = names.joinToString(" / "),
                ownerPlaylistId = rep.playlist.id
            ))
        }
        return out
    }
}
