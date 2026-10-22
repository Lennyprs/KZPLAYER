package com.kzplayer.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import org.json.JSONObject
import org.json.JSONArray
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Replay-only protocol adapters. Never generate an archive by reusing a live Stalker command. */
object ReplaySources {
    private val portalMutex = Mutex()
    private val guideCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<ReplayApi.Prog>>>()
    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    fun installQuota(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences("kz_replay_rate_v120", Context.MODE_PRIVATE)
        ReplayRateLimit.setStore(object : ReplayRateLimit.Store {
            override fun read(host: String): Long = prefs.getLong(host, 0L)
            override fun write(host: String, untilMs: Long) { prefs.edit().putLong(host, untilMs).apply() }
        })
    }

    fun allowSlots(pl: Playlist, channel: Item): Boolean {
        val config = channel.replayConfig
        return pl.type == "xtream" || (pl.type == "m3u" && config != null && config.declared() && !config.source.contains("{catchup-id}"))
    }

    fun availableSlot(pl: Playlist, channel: Item, prog: ReplayApi.Prog): Boolean =
        pl.type == "xtream" || ReplayCatchup.available(channel.replayConfig, prog.startMs, prog.endMs, System.currentTimeMillis())

    fun failureMessage(error: Throwable): String = when {
        error is ReplayRateLimit.Limited -> "HTTP 429 : le serveur demande une pause. Attends environ ${((error.remainingMs + 59999L) / 60000L).coerceAtLeast(1L)} min, sans relancer."
        error is ReplayRateLimit.HttpFailure -> "Le serveur a refuse la requete replay : HTTP ${error.responseCode}."
        else -> "Le guide ou le lien d'archive n'a pas pu etre obtenu. Aucun basculement vers le direct."
    }

    // Only the replay path uses this client. Portal credentials are stripped on EVERY foreign-origin network hop.
    fun stalkerFactory(context: Context, pl: Playlist, userAgent: String): androidx.media3.datasource.HttpDataSource.Factory {
        fun origin(value: String): String = try {
            val u = java.net.URI(value)
            val port = if (u.port >= 0) u.port else if (u.scheme.equals("https", true)) 443 else 80
            "${u.scheme?.lowercase()}://${u.host?.lowercase()}:$port"
        } catch (e: Exception) { "" }
        val trusted = origin(pl.serverUrl)
        val portalHeaders = Api.stalkerHeaders(pl)
        val builder = Api.imageClient().newBuilder()
            .connectTimeout(7, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(9, java.util.concurrent.TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                val safe = if (trusted.isNotBlank() && origin(request.url.toString()) == trusted) request
                else request.newBuilder().apply {
                    for (header in listOf("Authorization", "Proxy-Authorization", "Cookie", "Referer", "Origin", "X-User-Agent")) removeHeader(header)
                    for (header in portalHeaders.keys) if (!header.equals("User-Agent", true) && !header.equals("Accept", true)) removeHeader(header)
                }.build()
                chain.proceed(safe)
            }
        if (DnsPref.current(context) != DnsPref.SYSTEM) builder.dns(DohDns)
        val headers = portalHeaders.filterKeys { !it.equals("User-Agent", true) }
        return androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(builder.build())
            .setUserAgent(userAgent).setDefaultRequestProperties(headers)
    }

    suspend fun m3uPrograms(pl: Playlist, channel: Item): List<ReplayApi.Prog> = withContext(Dispatchers.IO) {
        val config = channel.replayConfig ?: return@withContext emptyList()
        if (!config.declared() || config.epgUrl.isBlank() || config.tvgId.isBlank()) return@withContext emptyList()
        val key = "m3u|${pl.id}|${config.epgUrl}|${config.tvgId}"
        val now = System.currentTimeMillis()
        guideCache[key]?.takeIf { now - it.first < 300000L }?.let { return@withContext it.second }
        val source = config.epgUrl.split(',').firstOrNull()?.trim().orEmpty()
        val parsed = try { java.net.URI(source) } catch (e: Exception) { return@withContext emptyList() }
        if (parsed.scheme !in listOf("https", "http") || parsed.host.isNullOrBlank()) return@withContext emptyList()
        val remaining = ReplayRateLimit.remainingMs(source)
        if (remaining > 0L) throw ReplayRateLimit.Limited(remaining)
        val response = Api.imageClient().newCall(Request.Builder().url(source).build()).execute()
        response.use { result ->
            if (result.code == 429) {
                ReplayRateLimit.recordStatus(source, 429, result.headers.toMultimap())
                throw ReplayRateLimit.Limited(ReplayRateLimit.remainingMs(source))
            }
            if (!result.isSuccessful) throw ReplayRateLimit.HttpFailure(result.code)
            val body = result.body ?: return@withContext emptyList()
            val raw = java.io.BufferedInputStream(body.byteStream())
            raw.mark(2)
            val gzip = raw.read() == 0x1f && raw.read() == 0x8b
            raw.reset()
            val stream: java.io.InputStream = if (gzip) java.util.zip.GZIPInputStream(raw) else raw
            val programs = stream.use { input -> ReplayXmltv.parse(input, config.tvgId, now) }.map { entry ->
                val p = ReplayApi.Prog(entry.title, "", entry.description, entry.start, entry.end, entry.id)
                p.copy(time = ReplayApi.label(p))
            }.filter { ReplayCatchup.available(config, it.startMs, it.endMs, now) }
            if (guideCache.size > 96) guideCache.clear()
            guideCache[key] = now to programs
            programs
        }
    }

    suspend fun stalkerDay(pl: Playlist, channel: Item, offsetDays: Int): List<ReplayApi.Prog> = withContext(Dispatchers.IO) {
        portalMutex.withLock {
            val id = channel.streamId.orEmpty()
            if (id.isBlank()) return@withLock emptyList<ReplayApi.Prog>()
            val calendar = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, -offsetDays) }
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(calendar.time)
            val key = "stalker|${pl.id}|$id|$day"
            val now = System.currentTimeMillis()
            guideCache[key]?.takeIf { now - it.first < 300000L }?.let { return@withLock it.second }
            val programs = ArrayList<ReplayApi.Prog>()
            for (page in 1..32) {
                if (page > 1) delay(150L)
                val json = JSONObject(Api.replayPortalCall(pl, ReplayStalker.guideQuery(id, day, page)))
                val js = json.opt("js")
                val objectData = js as? JSONObject
                val entries = if (js is JSONArray) js else objectData?.optJSONArray("data") ?: JSONArray()
                if (entries.length() == 0) break
                for (i in 0 until entries.length()) {
                    val row = entries.optJSONObject(i) ?: continue
                    val start = (row.optString("start_timestamp").toLongOrNull() ?: 0L) * 1000L
                    val end = (row.optString("stop_timestamp").toLongOrNull() ?: 0L) * 1000L
                    val programId = row.optString("id")
                    if (!programId.matches(Regex("[0-9]+")) || start <= 0L || end <= start || end > now) continue
                    if (row.has("mark_archive") && row.optString("mark_archive") !in listOf("1", "true")) continue
                    val title = row.optString("name").ifBlank { row.optString("title") }
                    val prog = ReplayApi.Prog(title.ifBlank { "Archive" }, "", row.optString("descr").ifBlank { row.optString("description") }, start, end, programId)
                    programs.add(prog.copy(time = ReplayApi.label(prog)))
                }
                val pageSize = (objectData?.optInt("max_page_items", entries.length()) ?: entries.length()).coerceAtLeast(1)
                val total = objectData?.optInt("total_items", entries.length()) ?: entries.length()
                if (page.toLong() * pageSize >= total) break
            }
            val result = programs.distinctBy { it.replayId }.sortedByDescending { it.startMs }
            if (guideCache.size > 96) guideCache.clear()
            guideCache[key] = now to result
            result
        }
    }

    suspend fun stalkerLink(pl: Playlist, prog: ReplayApi.Prog): String = withContext(Dispatchers.IO) {
        portalMutex.withLock {
            if (!prog.replayId.matches(Regex("[0-9]+")) || prog.endMs > System.currentTimeMillis()) return@withLock ""
            // Stalker server's tv_archive.create_link resolves the EPG PROGRAM id, not the channel id.
            val query = ReplayStalker.archiveQuery(prog.replayId)
            if (query.isBlank()) return@withLock ""
            val response = JSONObject(Api.replayPortalCall(pl, query))
            val js = response.optJSONObject("js") ?: return@withLock ""
            if (js.optString("error").isNotBlank() && js.optString("error") != "0") return@withLock ""
            val url = Api.replayCleanCommand(pl, js.optString("cmd"))
            val uri = try { java.net.URI(url) } catch (e: Exception) { return@withLock "" }
            if (uri.scheme !in listOf("https", "http") || uri.host.isNullOrBlank()) return@withLock ""
            url
        }
    }
}
