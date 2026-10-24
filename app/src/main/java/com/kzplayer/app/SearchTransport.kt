package com.kzplayer.app

import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.json.JSONArray
import java.io.BufferedReader
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Search-only HTTP. Cancellation actually cancels OkHttp, including response-body reads. */
internal object SearchTransport {
    private val client: OkHttpClient by lazy {
        Api.imageClient().newBuilder().connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(9, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).build()
    }
    internal class HttpFailure(val status:Int): IOException("Catalogue HTTP $status")
    private class Bounded(input: InputStream) : FilterInputStream(input) {
        private var remaining = 96L * 1024L * 1024L
        private fun used(n: Int): Int { if (n > 0) { remaining -= n; if (remaining < 0) throw IOException("Catalogue trop volumineux") }; return n }
        override fun read(): Int { val n = super.read(); if (n >= 0) used(1); return n }
        override fun read(b: ByteArray, off: Int, len: Int): Int = used(`in`.read(b, off, len))
    }
    suspend fun <T> read(url: String, headers: Map<String,String> = emptyMap(), form: FormBody? = null, parser: (BufferedReader) -> T): T = suspendCancellableCoroutine { continuation ->
        val root = Request.Builder().url(url).apply {
            header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            header("Accept", "application/json, audio/x-mpegurl, text/plain, */*")
            header("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.8")
            headers.forEach { (k,v) -> header(k,v) }
            if (form != null) post(form)
        }.build()
        // MAG identity must never follow a redirect to a foreign origin.
        val http = if (headers.isEmpty()) client else client.newBuilder().cookieJar(okhttp3.CookieJar.NO_COOKIES).addNetworkInterceptor { chain ->
            var req = chain.request()
            val sameProvider = req.url.host == root.url.host && (
                (req.url.port == root.url.port && req.url.scheme == root.url.scheme) ||
                (root.url.scheme == "http" && req.url.scheme == "https" && req.url.port == 443))
            if (!sameProvider) throw IOException("Redirection du portail vers un autre serveur refusee")
            chain.proceed(req)
        }.build()
        val call = http.newCall(root)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(IOException("Serveur de catalogue indisponible")) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val value = response.use { r ->
                        if (!r.isSuccessful) throw HttpFailure(r.code)
                        val body = r.body ?: throw IOException("Catalogue vide")
                        BufferedReader(InputStreamReader(Bounded(body.byteStream()), Charsets.UTF_8)).use(parser)
                    }
                    if (continuation.isActive) continuation.resume(value)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(if (e is HttpFailure) e else IOException("Catalogue inaccessible ou reponse invalide"))
                }
            }
        })
    }
    private fun enc(s:String) = URLEncoder.encode(s,"UTF-8")
    private val fields = setOf("name","title","stream_id","series_id","container_extension","stream_icon","cover","added","last_modified")
    private fun objectFrom(reader: JsonReader): JSONObject {
        val o=JSONObject();reader.beginObject()
        while(reader.hasNext()) {
            val name=reader.nextName()
            if(name !in fields) {reader.skipValue();continue}
            when(reader.peek()) {
                JsonToken.STRING, JsonToken.NUMBER -> o.put(name,reader.nextString())
                JsonToken.BOOLEAN -> o.put(name,reader.nextBoolean())
                else -> reader.skipValue()
            }
        }
        reader.endObject();return o
    }
    suspend fun xtream(pl:Playlist,kind:String,onBatch:(List<Item>)->Unit) {
        val action=if(kind=="series") "get_series" else "get_vod_streams"
        val base=pl.serverUrl.trimEnd('/')
        val url="$base/player_api.php?username=${enc(pl.username)}&password=${enc(pl.password)}&action=$action"
        read(url) { input ->
            val reader=JsonReader(input);reader.isLenient=false
            reader.beginArray();var batch=ArrayList<Item>(256)
            while(reader.hasNext()) {
                if(reader.peek()!=JsonToken.BEGIN_OBJECT) {reader.skipValue();continue}
                val o=objectFrom(reader)
                val name=o.optString("name").ifBlank {o.optString("title")}
                val id=o.optString(if(kind=="series") "series_id" else "stream_id")
                if(name.isBlank() || id.isBlank()) continue
                val ext=o.optString("container_extension").ifBlank {"mp4"}
                batch.add(if(kind=="series") Item(name=name,kind=kind,logo=o.optString("cover"),seriesId=id,added=o.optString("last_modified").toLongOrNull()?:0L)
                    else Item(name=name,kind="movie",logo=o.optString("stream_icon"),streamId=id,containerExt=ext,directUrl="$base/movie/${enc(pl.username)}/${enc(pl.password)}/$id.$ext",added=o.optString("added").toLongOrNull()?:0L))
                if(batch.size>=256) {onBatch(batch);batch=ArrayList(256)}
            }
            reader.endArray()
            if(reader.peek()!=JsonToken.END_DOCUMENT) throw IOException("Reponse catalogue incomplete")
            if(batch.isNotEmpty()) onBatch(batch)
        }
    }
    suspend fun m3u(pl:Playlist,onBatch:(List<Item>)->Unit) {
        val candidates=linkedSetOf<String>()
        if(pl.m3uUrl.isNotBlank()) candidates.add(pl.m3uUrl)
        if(pl.serverUrl.isNotBlank() && pl.username.isNotBlank()) {
            candidates.add("${pl.serverUrl.trimEnd('/')}/get.php?username=${enc(pl.username)}&password=${enc(pl.password)}&type=m3u_plus&output=ts")
            candidates.add("${pl.serverUrl.trimEnd('/')}/get.php?username=${enc(pl.username)}&password=${enc(pl.password)}&type=m3u&output=ts")
        }
        if(pl.serverUrl.isNotBlank()) candidates.add(pl.serverUrl)
        for(url in candidates) {
            try {
                read(url) { input ->
                    var batch=ArrayList<Item>(256)
                    SearchM3u.read(input) {entry ->
                        if(entry.userAgent.isNotBlank()) Api.m3uUserAgents[entry.url]=entry.userAgent
                        if(entry.kind!="live") {
                            batch.add(Item(name=entry.name,logo=entry.logo,kind=entry.kind,
                                directUrl=if(entry.kind=="series") null else entry.url,
                                seriesId=if(entry.kind=="series") entry.name else null,
                                description=entry.group,summary=entry.summary))
                            if(batch.size>=256) {onBatch(batch);batch=ArrayList(256)}
                        }
                    }
                    if(batch.isNotEmpty()) onBatch(batch)
                }
                return
            } catch(e:kotlinx.coroutines.CancellationException) {throw e}
            catch(e:HttpFailure) {if(e.status==429||e.status==401||e.status==403) throw e}
            catch(_:Exception) {}
        }
        throw IOException("Liste M3U indisponible")
    }

    // SearchPortal implementation is generated from the existing MAG profile queries,
    // but session state is isolated per playlist instead of using Api's global token.

    private const val STB_UA =
        "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG250 stbapp ver: 2 rev: 250 Safari/533.3"

    private data class StbProfile(val model: String, val ua: String, val imageVersion: String, val ver: String)
    private val STB_PROFILES = listOf(
        StbProfile("MAG250", STB_UA, "218", "ImageDescription: 0.2.18-r23-250; ImageDate: Wed Aug 29 2018; PORTAL version: 5.6.2; API Version: JS API version: 343;"),
        StbProfile("MAG254", "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG254 stbapp ver: 2 rev: 254 Safari/533.3", "220", "ImageDescription: 0.2.18-r23-254; ImageDate: Wed Aug 29 2018; PORTAL version: 5.6.2; API Version: JS API version: 343;"),
        StbProfile("MAG322", "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG322 stbapp ver: 2 rev: 322 Safari/533.3", "220", "ImageDescription: 0.2.18-r23-322; ImageDate: Wed Aug 29 2018; PORTAL version: 5.6.2; API Version: JS API version: 343;")
    )

    private fun md5Hex(s:String) = java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun sha256Hex(s:String) = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun stbSerial(pl: Playlist): String = md5Hex(pl.mac).uppercase().substring(0, 13)
    private fun stbDeviceId(pl: Playlist): String = sha256Hex(pl.mac.uppercase()).uppercase()

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


    internal class Portal(private val playlist:Playlist) {
        private var currentStbProfile = STB_PROFILES[0]
        private var stbCompat=false
        private var stalkerToken:String?=null
        private var base:String?=null
        private var legacyGeneration=-1L
        private val mutex=kotlinx.coroutines.sync.Mutex()
    private fun stbCookie(pl: Playlist): String =
        if (stbCompat)
            // Format releve sur un boitier MAG250 reel : mac non encodee, serial present,
            // fuseau en clair. Utilise uniquement en mode compatibilite.
            "mac=" + pl.mac + "; serial=" + pl.stalkerSn.ifBlank { stbSerial(pl) } +
                "; stb_lang=en; timezone=Europe/Paris"
        else
            "mac=" + enc(pl.mac) + "; stb_lang=en; timezone=Europe%2FParis"

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

    private suspend fun stalkerGetProfile(pl: Playlist, portal: String) {
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
            call(pl, portal, q)
            // Deuxieme get_profile allege : indispensable sur ces portails, ils ne
            // valident la session qu apres ce second appel.
            if (stbCompat) {
                call(pl, portal, "type=stb&action=get_profile&sn=$sn&auth_second_step=1&JsHttpRequest=1-xml")
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

        call(pl, portal, q)
        // Second get_profile allege (mode compatibilite uniquement).
        if (stbCompat) {
            call(pl, portal, "type=stb&action=get_profile&sn=${qv(sn)}&auth_second_step=1&JsHttpRequest=1-xml")
        }
    }


        private fun headers(pl:Playlist):Map<String,String> = linkedMapOf(
            "User-Agent" to if(stbCompat) "Mozilla/5.0 (QtEmbedded; Linux)" else currentStbProfile.ua,
            "Accept" to "*/*", "Cookie" to stbCookie(pl),
            "X-User-Agent" to "Model: ${currentStbProfile.model}; Link: WiFi",
            "Referer" to "${pl.serverUrl}/c/"
        ).apply {
            stalkerToken?.let {put("Authorization","Bearer $it")}
            putAll(stbCompatExtras(pl))
        }
        private suspend fun call(pl:Playlist,portal:String,query:String):String {
            val h=headers(pl)
            val text=try {read("$portal?$query",h) {it.readText()}}
                catch(e:HttpFailure) {if(e.status!=400 && e.status!=405) throw e else ""}
            if(text.trimStart().startsWith("{")) return text
            val form=FormBody.Builder()
            for(part in query.split('&')) {
                val pos=part.indexOf('=');if(pos>0) form.addEncoded(part.substring(0,pos),part.substring(pos+1))
            }
            return read(portal,h,form.build()) {it.readText()}
        }
        private suspend fun ensure():String {
            if(legacyGeneration!=Api.searchStalkerGeneration(playlist)) {base=null;stalkerToken=null}
            base?.takeIf { !stalkerToken.isNullOrBlank() }?.let {return it}
            val pl=playlist
            val raw=pl.serverUrl.trim().trimEnd('/')
            val endpoints=linkedSetOf<String>()
            if(raw.endsWith(".php",true)) endpoints.add(raw)
            endpoints.addAll(stalkerPortalCandidates(raw.removeSuffix("/c")))
            for(compat in listOf(false,true)) {
                stbCompat=compat
                for(portal in endpoints) {
                    try {
                        val obj=JSONObject(call(pl,portal,"type=stb&action=handshake&token=&JsHttpRequest=1-xml"))
                        val token=obj.optJSONObject("js")?.optString("token").orEmpty()
                        if(token.isBlank()) continue
                        stalkerToken=token
                        stalkerGetProfile(pl,portal)
                        if(hasCustomStalkerProfile(pl)) call(pl,portal,"type=account_info&action=get_main_info&JsHttpRequest=1-xml")
                        legacyGeneration=Api.searchStalkerGeneration(pl)
                        Api.searchChangedStalkerSession(pl)
                        base=portal;return portal
                    } catch(e:kotlinx.coroutines.CancellationException) {throw e}
                    catch(e:HttpFailure) {if(e.status==429) throw e}
                    catch(_:Exception) {}
                    stalkerToken=null
                }
            }
            throw IOException("Portail de recherche indisponible")
        }
        suspend fun search(kind:String,query:String,onBatch:(List<Item>)->Unit):Boolean {
            mutex.lock()
            try {
                val portal=ensure()
                val type=if(kind=="series") "series" else "vod"
                var page=1;var pages=1;var received=0;var category="*"
                val seen=HashSet<String>()
                while(page<=pages && page<=40 && received<=SearchCatalogStore.PER_LIST_LIMIT) {
                    val q="type=$type&action=get_ordered_list&category=${enc(category)}&search=${enc(query)}&fav=0&sortby=number&p=$page&JsHttpRequest=1-xml"
                    val root=JSONObject(call(playlist,portal,q))
                    var js=root.optJSONObject("js")?:throw IOException("Reponse de recherche invalide")
                    if(js.optString("error").let {it.isNotBlank() && it!="0" && it!="null" && it!="false"}) {base=null;stalkerToken=null;throw IOException("Session portail expiree")}
                    var data=js.optJSONArray("data")?:throw IOException("Reponse de recherche incomplete")
                    if(page==1 && data.length()==0) {
                        for(alias in listOf("0","")) {
                            val alternative=q.replace("&category=${enc(category)}&","&category=${enc(alias)}&")
                            val retry=JSONObject(call(playlist,portal,alternative)).optJSONObject("js")?:continue
                            val retryData=retry.optJSONArray("data")?:continue
                            if(retryData.length()>0) {category=alias;js=retry;data=retryData;break}
                        }
                    }
                    if(page==1) {
                        val total=js.optInt("total_items",data.length()).coerceAtLeast(0)
                        val size=js.optInt("max_page_items",data.length()).coerceAtLeast(1)
                        pages=((total.toLong()+size-1)/size).coerceIn(1,100000).toInt()
                    }
                    if(data.length()==0) return js.optInt("total_items",0)>0 || page>1
                    val batch=ArrayList<Item>()
                    for(i in 0 until data.length()) {
                        val o=data.optJSONObject(i)?:continue
                        val id=o.optString("id");val cmd=o.optString("cmd")
                        val name=o.optString("name").ifBlank {o.optString("title")}
                        if(name.isBlank()) continue
                        if(!seen.add(id+"|"+cmd+"|"+name)) continue
                        val isSeries=kind=="series"||o.optInt("is_series",0)==1
                        var logo=o.optString("logo").ifBlank {o.optString("screenshot_uri")}.ifBlank {o.optString("cover")}
                        if(logo.isNotBlank()&&!logo.startsWith("http",true)) logo=try {java.net.URI(portal).resolve(logo).toString()}catch(_:Exception){""}
                        batch.add(Item(name=name,kind=if(isSeries) "series" else "movie",logo=logo,cmd=cmd,
                            seriesId=if(isSeries) id else null,streamId=if(!isSeries) id.takeIf {it.isNotBlank()} else null,description=o.optString("description"),added=o.optString("added").toLongOrNull()?:0L))
                    }
                    if(batch.isEmpty() && page>1) return true // Portal repeated the same page.
                    if(batch.isNotEmpty()) onBatch(batch)
                    received+=data.length();page++
                }
                return page<=pages
            } finally {mutex.unlock()}
        }
    }
}
