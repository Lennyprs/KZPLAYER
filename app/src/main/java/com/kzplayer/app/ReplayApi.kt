package com.kzplayer.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v340 : REPLAY (catch-up) reel.
 *
 * Api.kt n'est PAS modifie : on fait ici notre propre appel EPG (get_simple_data_table)
 * en reutilisant seulement le client HTTP public Api.imageClient(), puis on construit
 * l'URL d'archive timeshift standard Xtream.
 *
 * Format timeshift Xtream :
 *   <serveur>/timeshift/<user>/<pass>/<duree_min>/<yyyy-MM-dd:HH-mm>/<stream_id>.ts
 */
object ReplayApi {

    data class Prog(
        val title: String,
        val time: String,
        val desc: String,
        val startMs: Long,
        val endMs: Long
    )

    private fun enc(s: String): String = try { URLEncoder.encode(s, "UTF-8") } catch (e: Exception) { s }

    private fun clock(ms: Long): String = try {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
    } catch (e: Exception) { "" }

    private fun day(ms: Long): String = try {
        SimpleDateFormat("EEE d MMM", Locale.FRENCH).format(Date(ms))
    } catch (e: Exception) { "" }

    fun label(p: Prog): String = (day(p.startMs) + "  " + clock(p.startMs) + " - " + clock(p.endMs)).trim()

    private fun maybeBase64(raw: String): String {
        val t = raw.trim()
        if (t.isBlank()) return ""
        if (t.length >= 4 && t.length % 4 == 0 && t.matches(Regex("^[A-Za-z0-9+/=]+$"))) {
            try {
                val d = String(android.util.Base64.decode(t, android.util.Base64.DEFAULT), Charsets.UTF_8).trim()
                if (d.isNotBlank()) return d
            } catch (e: Exception) {}
        }
        return t
    }

    /** Programmes DEJA DIFFUSES (les 3 derniers jours), du plus recent au plus ancien. */
    suspend fun programs(pl: Playlist, streamId: String): List<Prog> = withContext(Dispatchers.IO) {
        if (streamId.isBlank()) return@withContext emptyList()
        // v341 : Stalker/MAG -> on reutilise l'EPG portail deja gere par Api (get_short_epg).
        if (pl.type == "stalker") return@withContext stalkerPrograms(pl, streamId)
        if (pl.type != "xtream") return@withContext emptyList()
        val url = pl.serverUrl.trimEnd('/') + "/player_api.php?username=" + enc(pl.username) +
            "&password=" + enc(pl.password) + "&action=get_simple_data_table&stream_id=" + enc(streamId)
        val txt = try {
            Api.imageClient().newCall(Request.Builder().url(url).build()).execute().use { r ->
                r.body?.string() ?: ""
            }
        } catch (e: Exception) { "" }
        if (txt.isBlank()) return@withContext emptyList()
        val arr: JSONArray = try {
            val o = JSONObject(txt)
            o.optJSONArray("epg_listings") ?: o.optJSONArray("data") ?: JSONArray()
        } catch (e: Exception) { JSONArray() }
        val now = System.currentTimeMillis()
        // v361 : on remonte jusqu a 8 jours en arriere (avant : 3 jours).
        val floor = now - 8L * 24L * 3600L * 1000L
        val off = serverOffsetMs(pl)
        val out = ArrayList<Prog>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = maybeBase64(o.optString("title").ifBlank { o.optString("name") })
            if (title.isBlank()) continue
            val desc = maybeBase64(o.optString("description").ifBlank { o.optString("descr") })
            var start = (o.optString("start_timestamp").toLongOrNull() ?: 0L) * 1000L
            var stop = (o.optString("stop_timestamp").ifBlank { o.optString("end_timestamp") }.toLongOrNull() ?: 0L) * 1000L
            // v366 : horaires exacts. Les champs texte du serveur sont convertis avec
            // son fuseau, ce qui evite les heures decalees affichees dans le replay.
            val ps = parseServerTime(o.optString("start"), off)
            val pe = parseServerTime(o.optString("end").ifBlank { o.optString("stop") }, off)
            if (ps > 0L && pe > ps) { start = ps; stop = pe }
            if (start <= 0L || stop <= start) continue
            if (stop > now) continue
            if (start < floor) continue
            val p = Prog(title, "", desc, start, stop)
            out.add(p.copy(time = label(p)))
        }
        out.sortedByDescending { it.startMs }
    }

    /** Si le serveur ne donne pas d'EPG : tranches de 30 min sur les dernieres 24 h. */
    fun fallbackSlots(hours: Int = 24): List<Prog> {
        val out = ArrayList<Prog>()
        val step = 30L * 60L * 1000L
        var end = (System.currentTimeMillis() / step) * step
        var n = hours * 2
        while (n > 0) {
            val start = end - step
            val p = Prog("Archive", "", "", start, end)
            out.add(p.copy(time = label(p)))
            end = start
            n--
        }
        return out
    }

    // v361 : minuit du jour demande (0 = aujourd hui, 1 = hier, ...).
    private fun midnight(offsetDays: Int): Long {
        val c = java.util.Calendar.getInstance()
        c.set(java.util.Calendar.HOUR_OF_DAY, 0)
        c.set(java.util.Calendar.MINUTE, 0)
        c.set(java.util.Calendar.SECOND, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        c.add(java.util.Calendar.DAY_OF_YEAR, -offsetDays)
        return c.timeInMillis
    }

    fun dayLabel(offsetDays: Int): String = when (offsetDays) {
        0 -> "Aujourd hui"
        1 -> "Hier"
        else -> try {
            SimpleDateFormat("EEE d MMM", Locale.FRENCH).format(Date(midnight(offsetDays)))
        } catch (e: Exception) { "J-" + offsetDays }
    }

    // Garde seulement les programmes du jour demande.
    fun filterDay(list: List<Prog>, offsetDays: Int): List<Prog> {
        val from = midnight(offsetDays)
        val to = from + 24L * 3600L * 1000L
        return list.filter { it.startMs >= from && it.startMs < to }
    }

    // Si le serveur ne donne pas de guide pour ce jour : tranches de 30 minutes.
    fun slotsForDay(offsetDays: Int): List<Prog> {
        val step = 30L * 60L * 1000L
        val from = midnight(offsetDays)
        val now = System.currentTimeMillis()
        var end = (from + 24L * 3600L * 1000L).coerceAtMost((now / step) * step)
        val out = ArrayList<Prog>()
        while (end - step >= from && out.size < 48) {
            val start = end - step
            val p = Prog("Archive", "", "", start, end)
            out.add(p.copy(time = label(p)))
            end = start
        }
        return out
    }

    fun timeshiftUrl(pl: Playlist, streamId: String, startMs: Long, durationMin: Int): String {
        val d = if (durationMin < 1) 60 else durationMin
        val stamp = try {
            SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US).format(Date(startMs))
        } catch (e: Exception) { "" }
        return pl.serverUrl.trimEnd('/') + "/timeshift/" + enc(pl.username) + "/" + enc(pl.password) +
            "/" + d + "/" + stamp + "/" + streamId + ".ts"
    }

    // v341 : programmes passes d'une chaine Stalker (via l'EPG du portail).
    private suspend fun stalkerPrograms(pl: Playlist, chId: String): List<Prog> {
        val epg = try { Api.stalkerShortEpg(pl, chId) } catch (e: Exception) { emptyList() }
        val now = System.currentTimeMillis()
        // v361 : on remonte jusqu a 8 jours en arriere (avant : 3 jours).
        val floor = now - 8L * 24L * 3600L * 1000L
        val out = ArrayList<Prog>()
        for (e in epg) {
            if (e.startMs <= 0L || e.endMs <= e.startMs) continue
            if (e.endMs > now || e.startMs < floor) continue
            val p = Prog(e.title, "", e.description, e.startMs, e.endMs)
            out.add(p.copy(time = label(p)))
        }
        return out.sortedByDescending { it.startMs }
    }

    /**
     * v341 : URL de l'archive, quel que soit le type de serveur.
     *  - Xtream : URL timeshift standard.
     *  - Stalker/MAG : on demande d'abord le lien direct de la chaine au portail
     *    (Api.stalkerLink, inchange), puis on ajoute les parametres d'archive
     *    utc / lutc acceptes par les portails Stalker (Flussonic / Astra / Ministra).
     *    Aucune modification d'Api.kt ni du protocole Stalker existant.
     */
    // v363 : les serveurs ne parlent pas tous le meme langage pour les archives.
    // On prepare donc plusieurs URL possibles, on les teste vraiment une par une
    // (petite requete HTTP) et on garde la premiere qui renvoie bien de la video.
    // Fini le ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED sur une page d erreur HTML.
    var lastArchiveLog: String = ""

    suspend fun archiveUrl(pl: Playlist, streamId: String, cmd: String, p: Prog): String =
        withContext(Dispatchers.IO) {
            val startSec = p.startMs / 1000L
            val durSec = ((p.endMs - p.startMs) / 1000L).coerceAtLeast(60L)
            // v366 STALKER : le portail donne lui-meme le lien de l enregistrement.
            // Ces liens sont a usage unique (play_token) : les tester avant lecture les
            // grillait et rendait le lancement tres long. On les joue donc directement.
            if (pl.type == "stalker") {
                val direct = try {
                    Api.stalkerArchiveLinks(pl, streamId, cmd, startSec, durSec)
                } catch (e: Exception) { emptyList<String>() }
                val first = direct.firstOrNull { it.isNotBlank() }
                if (first != null) {
                    lastArchiveMime = if (first.contains(".m3u8")) "application/x-mpegURL" else ""
                    lastArchiveLog = "Lien archive donne par le portail (joue direct) :" +
                        System.lineSeparator() + first + System.lineSeparator() +
                        Api.lastArchiveStalkerLog
                    return@withContext first
                }
            }
            val cands = archiveCandidates(pl, streamId, cmd, p)
            if (cands.isEmpty()) {
                lastArchiveMime = ""
                lastArchiveLog = "Aucune URL d archive possible pour cette chaine." +
                    System.lineSeparator() + Api.lastArchiveStalkerLog
                return@withContext ""
            }
            // v366 : tous les candidats sont testes EN MEME TEMPS (avant : un par un,
            // ce qui pouvait prendre 30 s). On garde le meilleur dans l ordre de priorite.
            val results = coroutineScope {
                cands.map { u -> async(Dispatchers.IO) { Pair(u, probe(u, p.startMs)) } }
                    .map { it.await() }
            }
            val tried = ArrayList<String>()
            for (pair in results) tried.add(pair.second.tag + "  " + pair.first)
            lastArchiveLog = tried.joinToString(System.lineSeparator())
            val good = results.firstOrNull { it.second.ok }
            if (good != null) {
                lastArchiveMime = good.second.mime
                return@withContext good.first
            }
            lastArchiveMime = ""
            ""
        }

    /**
     * v406 : candidats de telechargement construits immediatement, sans requete
     * reseau avant la creation de la tache. Le telechargeur les essaie dans
     * l ordre et passe automatiquement au suivant si un serveur refuse un format.
     */
    fun archiveDownloadUrls(pl: Playlist, streamId: String, p: Prog): List<String> {
        if (pl.type != "xtream" || streamId.isBlank()) return emptyList()
        val srv = pl.serverUrl.trimEnd(chr47())
        val u = enc(pl.username)
        val w = enc(pl.password)
        val dur = durationMin(p)
        val key = pl.serverUrl + "|" + pl.username
        val cachedOffset = srvOffset[key] ?: 0L
        val stamp = try {
            val f = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US)
            f.timeZone = java.util.TimeZone.getTimeZone("GMT")
            f.format(Date(p.startMs + cachedOffset))
        } catch (_: Exception) { "" }
        if (stamp.isBlank()) return emptyList()
        return listOf(
            srv + "/timeshift/" + u + "/" + w + "/" + dur + "/" + stamp + "/" + streamId + ".ts",
            srv + "/streaming/timeshift.php?username=" + u + "&password=" + w +
                "&stream=" + enc(streamId) + "&start=" + enc(stamp) + "&duration=" + dur,
            srv + "/timeshift.php?username=" + u + "&password=" + w +
                "&stream=" + enc(streamId) + "&start=" + enc(stamp) + "&duration=" + dur,
            srv + "/timeshift/" + u + "/" + w + "/" + dur + "/" + stamp + "/" + streamId
        ).distinct()
    }

    suspend fun archiveDownloadUrl(pl: Playlist, streamId: String, cmd: String, p: Prog): String =
        withContext(Dispatchers.IO) { archiveDownloadUrls(pl, streamId, p).firstOrNull().orEmpty() }

    // Toutes les URL d archive connues pour ce serveur / ce programme.
    private suspend fun archiveCandidates(pl: Playlist, streamId: String, cmd: String, p: Prog): List<String> {
        val out = ArrayList<String>()
        val dur = durationMin(p)
        val srv = pl.serverUrl.trimEnd(chr47())
        val startSec = p.startMs / 1000L
        val nowSec = System.currentTimeMillis() / 1000L
        val durSec = ((p.endMs - p.startMs) / 1000L).coerceAtLeast(60L)
        // v366 : les URL timeshift Xtream attendent l heure LOCALE DU SERVEUR.
        // Avant on envoyait l heure de la box : on tombait a cote (mauvaise emission).
        val stamp = if (pl.type == "xtream") serverStamp(pl, p.startMs) else try {
            SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US).format(Date(p.startMs))
        } catch (e: Exception) { "" }
        if (pl.type == "xtream") {
            if (streamId.isBlank()) return out
            val u = enc(pl.username)
            val w = enc(pl.password)
            // HLS (.m3u8) en premier : demarrage plus rapide et son beaucoup plus fiable
            // que le .ts brut (certains serveurs y mettent un son que la TV ne lit pas).
            out.add(srv + "/timeshift/" + u + "/" + w + "/" + dur + "/" + stamp + "/" + streamId + ".m3u8")
            out.add(srv + "/timeshift/" + u + "/" + w + "/" + dur + "/" + stamp + "/" + streamId + ".ts")
            out.add(srv + "/streaming/timeshift.php?username=" + u + "&password=" + w +
                "&stream=" + enc(streamId) + "&start=" + enc(stamp) + "&duration=" + dur)
            out.add(srv + "/timeshift.php?username=" + u + "&password=" + w +
                "&stream=" + enc(streamId) + "&start=" + enc(stamp) + "&duration=" + dur)
            out.add(srv + "/streaming/timeshift.php?username=" + u + "&password=" + w +
                "&stream=" + enc(streamId) + "&start=" + enc(stamp) + "&duration=" + dur + "&type=m3u8")
            out.add(srv + "/timeshift/" + u + "/" + w + "/" + dur + "/" + stamp + "/" + streamId)
            out.add(srv + "/live/" + u + "/" + w + "/" + streamId + ".m3u8?utc=" + startSec +
                "&lutc=" + nowSec)
            return out.distinct()
        }
        if (pl.type != "stalker" || cmd.isBlank()) return out
        // Repli Stalker : lien de la chaine + parametres d archive.
        val base = try { Api.stalkerLink(pl, cmd, "live") } catch (e: Exception) { null }
        if (base.isNullOrBlank()) return out.distinct()
        val clean = base.trim()
        // Essai A : parametres utc et lutc - Ministra, Astra, Flussonic
        out.add(withArchiveParams(clean, p))
        val sep = if (clean.contains("?")) "&" else "?"
        out.add(clean + sep + "utc=" + startSec + "&lutc=" + nowSec)
        out.add(clean + sep + "utcstart=" + startSec + "&lutc=" + nowSec)
        // Essai B : formats Flussonic timeshift_abs et archive
        val noQuery = clean.substringBefore("?")
        val slash = noQuery.lastIndexOf(chr47())
        if (slash > 8) {
            val root = noQuery.substring(0, slash)
            out.add(root + "/timeshift_abs-" + startSec + ".m3u8")
            out.add(root + "/timeshift_abs-" + startSec + ".ts")
            out.add(root + "/archive-" + startSec + "-" + durSec + ".m3u8")
            out.add(root + "/archive-" + startSec + "-" + durSec + ".ts")
            out.add(root + "/index-" + startSec + "-" + durSec + ".m3u8")
        }
        return out.distinct()
    }

    private fun chr47(): Char = 47.toChar()

    // ---- v366 : heure du serveur ----
    // Le guide Xtream et les URL timeshift sont exprimes dans le fuseau du SERVEUR.
    // On calcule une fois le decalage serveur/UTC, puis on convertit tout proprement.
    private val srvOffset = HashMap<String, Long>()

    private fun serverOffsetMs(pl: Playlist): Long {
        val key = pl.serverUrl + "|" + pl.username
        val known = srvOffset[key]
        if (known != null) return known
        var off = 0L
        try {
            val url = pl.serverUrl.trimEnd(chr47()) + "/player_api.php?username=" +
                enc(pl.username) + "&password=" + enc(pl.password)
            val txt = Api.imageClient().newCall(Request.Builder().url(url).build())
                .execute().use { it.body?.string() ?: "" }
            val si = JSONObject(txt).optJSONObject("server_info")
            if (si != null) {
                var ts = si.optString("timestamp_now").toLongOrNull() ?: 0L
                if (ts <= 0L) ts = si.optLong("timestamp_now", 0L)
                val timeNow = si.optString("time_now").trim()
                if (ts > 0L && timeNow.isNotBlank()) {
                    val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    f.timeZone = java.util.TimeZone.getTimeZone("GMT")
                    val asUtc = try { f.parse(timeNow)?.time ?: 0L } catch (e: Exception) { 0L }
                    if (asUtc > 0L) off = ((asUtc - ts * 1000L) / 60000L) * 60000L
                }
            }
        } catch (e: Exception) {}
        srvOffset[key] = off
        return off
    }

    // Horodatage attendu par les URL timeshift : heure locale du serveur.
    private fun serverStamp(pl: Playlist, ms: Long): String = try {
        val f = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US)
        f.timeZone = java.util.TimeZone.getTimeZone("GMT")
        f.format(Date(ms + serverOffsetMs(pl)))
    } catch (e: Exception) { "" }

    // "2026-08-23 21:00:00" (heure serveur) -> vraie heure universelle.
    private fun parseServerTime(txt: String, offsetMs: Long): Long = try {
        if (txt.isBlank()) 0L else {
            val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            f.timeZone = java.util.TimeZone.getTimeZone("GMT")
            val t = f.parse(txt.trim())?.time ?: 0L
            if (t > 0L) t - offsetMs else 0L
        }
    } catch (e: Exception) { 0L }

    // v365 : controle de l archive, souple mais sur.
    // On accepte tout ce qui est de la VRAIE video (playlist HLS ou flux MPEG-TS).
    // On refuse : les erreurs HTTP, les pages HTML/JSON, et les playlists dont la date
    // reelle du flux ne correspond pas au jour demande (= c est le direct).
    var lastArchiveMime: String = ""

    private class Verdict(val ok: Boolean, val mime: String, val tag: String)

    // Client dedie aux tests : timeouts courts pour ne jamais faire attendre.
    private val probeClient by lazy {
        Api.imageClient().newBuilder()
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private fun probe(url: String, wantStartMs: Long): Verdict {
        return try {
            val req = Request.Builder().url(url)
                .header("Range", "bytes=0-32767")
                .header("User-Agent", "IPTVSmartersPro")
                .build()
            probeClient.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use Verdict(false, "", "HTTP" + r.code)
                val ct = (r.header("Content-Type") ?: "").lowercase()
                if (ct.contains("html") || ct.contains("json") || ct.contains("/xml")) {
                    return@use Verdict(false, "", "PAGE ")
                }
                val bytes = try { r.body?.bytes() } catch (e: Exception) { null }
                if (bytes == null || bytes.size < 8) return@use Verdict(false, "", "VIDE ")
                val head = String(bytes, 0, if (bytes.size > 4096) 4096 else bytes.size, Charsets.ISO_8859_1)
                val t = head.trimStart()
                if (t.startsWith("<") || t.startsWith("{") || t.startsWith("[")) {
                    return@use Verdict(false, "", "PAGE ")
                }
                if (t.startsWith("#EXTM3U")) {
                    val body = String(bytes, Charsets.ISO_8859_1)
                    if (isLivePlaylist(body, wantStartMs)) Verdict(false, "", "DIRECT")
                    else Verdict(true, "application/x-mpegURL", "OK-HLS")
                } else if (bytes[0] == 0x47.toByte()) {
                    Verdict(true, "video/mp2t", "OK-TS ")
                } else {
                    // autre conteneur video (mp4, mkv...) : on laisse le lecteur decider
                    Verdict(true, "", "OK-VID")
                }
            }
        } catch (e: Exception) { Verdict(false, "", "ERR  ") }
    }

    // true = cette playlist est le DIRECT (mauvais jour) et non l archive demandee.
    private fun isLivePlaylist(body: String, wantStartMs: Long): Boolean {
        val m = Regex("EXT-X-PROGRAM-DATE-TIME:([0-9]{4})-([0-9]{2})-([0-9]{2})").find(body)
            ?: return false
        val got = m.groupValues[1] + "-" + m.groupValues[2] + "-" + m.groupValues[3]
        val dayMs = 24L * 3600L * 1000L
        val f = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val want = try { f.format(Date(wantStartMs)) } catch (e: Exception) { "" }
        if (want.isBlank()) return false
        val prev = try { f.format(Date(wantStartMs - dayMs)) } catch (e: Exception) { "" }
        val next = try { f.format(Date(wantStartMs + dayMs)) } catch (e: Exception) { "" }
        return !(got == want || got == prev || got == next)
    }

    // Ajoute utc=<debut> & lutc=<maintenant> (+ duree) sans casser les parametres deja presents.
    private fun withArchiveParams(url: String, p: Prog): String {
        val startSec = p.startMs / 1000L
        val nowSec = System.currentTimeMillis() / 1000L
        val durSec = ((p.endMs - p.startMs) / 1000L).coerceAtLeast(60L)
        val clean = url.trim()
        val sep = if (clean.contains("?")) "&" else "?"
        return clean + sep + "utc=" + startSec + "&lutc=" + nowSec + "&duration=" + durSec
    }

    fun durationMin(p: Prog): Int {
        val m = ((p.endMs - p.startMs) / 60000L).toInt()
        return if (m < 1) 30 else m
    }
}
