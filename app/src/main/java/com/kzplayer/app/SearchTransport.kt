package com.kzplayer.app

import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withPermit
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
    private lateinit var spoolDirectory:java.io.File
    fun init(context:android.content.Context){
        spoolDirectory=java.io.File(context.cacheDir,"search-v124/transfers");spoolDirectory.mkdirs()
        spoolDirectory.listFiles()?.forEach {if(it.isFile)it.delete()}
    }
    private val client:OkHttpClient by lazy {
        Api.imageClient().newBuilder().connectTimeout(6,TimeUnit.SECONDS)
            .readTimeout(20,TimeUnit.SECONDS).callTimeout(90,TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).build()
    }
    private val origins=java.util.concurrent.ConcurrentHashMap<String,kotlinx.coroutines.sync.Semaphore>()
    internal class HttpFailure(val status:Int):IOException("HTTP_$status")
    private class ChallengeSolved:IOException()
    private suspend fun download(url:String,headers:Map<String,String>,form:FormBody?):java.io.File = suspendCancellableCoroutine {cont ->
        val root=Request.Builder().url(url).apply {
            header("User-Agent","Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            header("Accept","application/json, audio/x-mpegurl, text/plain, */*")
            header("Accept-Language","fr-FR,fr;q=0.9,en;q=0.8")
            header("Referer",java.net.URI(url).resolve("/").toString())
            headers.forEach {(k,v)->header(k,v)}
            if(form!=null)post(form)
        }.build()
        val file=java.io.File.createTempFile("catalog-",".part",spoolDirectory)
        val call=client.newCall(root)
        cont.invokeOnCancellation {call.cancel();file.delete()}
        call.enqueue(object:Callback {
            override fun onFailure(call:Call,e:IOException){file.delete();if(cont.isActive)cont.resumeWithException(IOException(if(e is java.net.UnknownHostException)"RESEAU_DNS" else "RESEAU_CONNEXION"))}
            override fun onResponse(call:Call,response:Response){
                try {
                    response.use {r ->
                        if(!r.isSuccessful)throw HttpFailure(r.code)
                        val body=r.body?:throw IOException("REPONSE_VIDE")
                        body.byteStream().use {input ->file.outputStream().buffered(65536).use {out ->
                            val bytes=ByteArray(65536);var total=0L
                            while(true){val n=input.read(bytes);if(n<0)break;if(!cont.isActive)throw IOException("ANNULE");total+=n;if(total>96L*1024*1024)throw IOException("CATALOGUE_TROP_GRAND");out.write(bytes,0,n)}
                        }}
                    }
                    if(cont.isActive)cont.resume(file)else file.delete()
                }catch(e:Exception){file.delete();if(cont.isActive)cont.resumeWithException(if(e is HttpFailure)e else IOException("TRANSFERT_INCOMPLET"))}
            }
        })
    }
    suspend fun <T> read(url:String,headers:Map<String,String> = emptyMap(),form:FormBody?=null,parser:(BufferedReader)->T):T {
        val origin=java.net.URI(url).let {"${it.scheme}://${it.host}:${it.port}"}
        val gate=origins.getOrPut(origin){kotlinx.coroutines.sync.Semaphore(1)}
        for(attempt in 0..1){
            val file=gate.withPermit {download(url,headers,form)}
            try{return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){
                file.bufferedReader(Charsets.UTF_8).use {input ->
                    input.mark(8192);val peek=CharArray(4096);val n=input.read(peek);input.reset()
                    val prefix=if(n>0)String(peek,0,n)else ""
                    if(prefix.trimStart().startsWith("<")&&file.length()<=256*1024){
                        if(Api.searchChallenge(Request.Builder().url(url).build().url,file.readText(Charsets.UTF_8)))throw ChallengeSolved()
                    }
                    parser(input)
                }
            }}catch(e:ChallengeSolved){if(attempt==1)throw IOException("PROTECTION_SERVEUR")}
            finally{file.delete()}
        }
        throw IOException("REPONSE_INVALIDE")
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
            catch(e:IllegalStateException) {throw e}
            catch(_:Exception) {}
        }
        throw IOException("Liste M3U indisponible")
    }

    internal class Portal(private val playlist:Playlist) {
        private val mutex=kotlinx.coroutines.sync.Mutex()
        suspend fun search(kind:String,query:String,onBatch:(List<Item>)->Unit):Boolean {
            mutex.lock()
            try {
                val type=if(kind=="series") "series" else "vod"
                var page=1;var pages=1;var received=0;var category="*"
                val seen=HashSet<String>()
                while(page<=pages && page<=40 && received<=SearchCatalogStore.PER_LIST_LIMIT) {
                    val q="type=$type&action=get_ordered_list&category=${enc(category)}&search=${enc(query)}&fav=0&sortby=number&p=$page&JsHttpRequest=1-xml"
                    var js=JSONObject(Api.searchLegacyPortal(playlist,q)).optJSONObject("js")?:throw IOException("REPONSE_PORTAIL")
                    if(js.optString("error").trim().lowercase() !in listOf("","0","false","null"))throw IOException("REFUS_PORTAIL")
                    var data=js.optJSONArray("data")?:throw IOException("REPONSE_PORTAIL")
                    if(page==1&&data.length()==0)for(alias in listOf("0","")){
                        val retry=JSONObject(Api.searchLegacyPortal(playlist,q.replace("&category=${enc(category)}&","&category=${enc(alias)}&"))).optJSONObject("js")?:continue
                        val rows=retry.optJSONArray("data")?:continue
                        if(rows.length()>0){js=retry;data=rows;category=alias;break}
                    }
                    if(page==1){val size=js.optInt("max_page_items",data.length()).coerceAtLeast(1);val total=js.optInt("total_items",data.length()).coerceAtLeast(0);pages=((total.toLong()+size-1)/size).coerceIn(1,100000).toInt()}
                    if(data.length()==0)return js.optInt("total_items",0)>0||page>1
                    val batch=ArrayList<Item>()
                    for(i in 0 until data.length()){
                        val o=data.optJSONObject(i)?:continue;val id=o.optString("id");val cmd=o.optString("cmd");val name=o.optString("name").ifBlank {o.optString("title")}
                        if(name.isBlank()||!seen.add(id+"|"+cmd+"|"+name))continue
                        val series=kind=="series"||o.optInt("is_series",0)==1
                        var logo=o.optString("logo").ifBlank {o.optString("screenshot_uri")}.ifBlank {o.optString("cover")}
                        if(logo.isNotBlank()&&!logo.startsWith("http",true))logo=try{java.net.URI(playlist.serverUrl.trimEnd('/')+"/").resolve(logo).toString()}catch(_:Exception){""}
                        batch.add(Item(name=name,kind=if(series)"series" else "movie",logo=logo,cmd=cmd,streamId=if(!series)id.takeIf {it.isNotBlank()}else null,seriesId=if(series)id else null,description=o.optString("description"),added=o.optString("added").toLongOrNull()?:0L))
                    }
                    if(batch.isEmpty()&&page>1)return true
                    if(batch.isNotEmpty())onBatch(batch)
                    received+=data.length();page++
                }
                return page<=pages
            }finally{mutex.unlock()}
        }
    }
}
