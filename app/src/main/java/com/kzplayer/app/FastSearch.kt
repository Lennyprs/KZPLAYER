package com.kzplayer.app

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Persistent streaming catalogs, shared downloads, immediate local queries. */
object FastSearch {
    data class Progress(val items:List<Item>,val done:Int,val total:Int,val failed:Int,val pending:Int,val limited:Boolean,val finished:Boolean,val issues:List<String> = emptyList()) {
        fun message(query:String):String = when {
            pending>0 -> "" // Progression silencieuse : les resultats continuent a arriver.
            failed>0 -> "Recherche partielle : $failed liste(s) non interrogee(s)." + if(issues.isEmpty()) "" else "\n"+issues.take(4).joinToString(" ; ")
            limited -> "Resultats limites : precise le titre pour affiner la recherche."
            items.isEmpty() -> "Aucun resultat pour \"$query\" dans les $total liste(s)."
            else -> ""
        }
    }
    private const val TTL=15L*60L*1000L
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val downloads=Semaphore(2)
    private val nativeRequests=Semaphore(3)
    private val lock=Any()
    @Volatile private var generation=0L
    private lateinit var store:SearchCatalogStore
    private val work=HashMap<String,Ticket>()
    private val portals=ConcurrentHashMap<String,SearchTransport.Portal>()
    private val nativeBackoff=ConcurrentHashMap<String,Long>()
    private class Ticket {
        @Volatile var outcome=0 // 0 pending, 1 complete, 2 unavailable, 3 truncated
        @Volatile var ended=0L
        @Volatile var error=""
        lateinit var job:Job
    }
    fun init(context:Context) {store=SearchCatalogStore(context.applicationContext);SearchTransport.init(context.applicationContext)}
    private fun hash(s:String)=MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it)}
    internal fun key(pl:Playlist,kind:String):String = hash(pl.copy(nom="",hiddenCategories=emptyList(),shownByKind=emptyMap()).toString())+":"+kind
    private fun fresh(key:String)=System.currentTimeMillis()-store.updated(key) in 0 until TTL
    private fun row(item:Item):SearchCatalogStore.Row {
        val stable=listOf(item.streamId,item.seriesId,item.cmd,item.directUrl).firstOrNull { !it.isNullOrBlank() }?:item.name
        val identity=hash(item.kind+":"+stable)
        val o=JSONObject().put("name",item.name).put("logo",item.logo).put("kind",item.kind)
            .put("direct",item.directUrl?:JSONObject.NULL).put("stream",item.streamId?:JSONObject.NULL)
            .put("ext",item.containerExt?:JSONObject.NULL).put("series",item.seriesId?:JSONObject.NULL)
            .put("cmd",item.cmd?:JSONObject.NULL).put("description",item.description).put("duration",item.duration)
            .put("added",item.added).put("summary",item.summary)
        item.directUrl?.let {url -> Api.m3uUserAgents[url]?.let {o.put("ua",it)}}
        return SearchCatalogStore.Row(identity,item.name,o.toString())
    }
    private fun item(raw:String):Item {
        val o=JSONObject(raw)
        fun optional(key:String):String? = if(o.isNull(key)) null else o.optString(key).takeIf {it.isNotBlank()}
        val direct=optional("direct")
        if(direct!=null && o.optString("ua").isNotBlank()) Api.m3uUserAgents[direct]=o.optString("ua")
        return Item(name=o.optString("name"),logo=o.optString("logo"),kind=o.optString("kind"),directUrl=direct,
            streamId=optional("stream"),containerExt=optional("ext"),seriesId=optional("series"),cmd=optional("cmd"),
            description=o.optString("description"),duration=o.optString("duration"),added=o.optLong("added"),summary=o.optString("summary"))
    }
    private fun catalog(pl:Playlist,kind:String):Ticket {
        val requested=key(pl,kind)
        val jobKey=if(pl.type=="m3u") key(pl,"m3u-both") else requested
        synchronized(lock) {
            val previous=work[jobKey]
            if(previous!=null && (previous.outcome==0 || (previous.outcome==2 && System.currentTimeMillis()-previous.ended<3000))) return previous
            if(fresh(requested)) return Ticket().apply {outcome=1;ended=System.currentTimeMillis();job=Job().apply {complete()}}
            val ticket=Ticket()
            val version=generation
            ticket.job=scope.launch(start=CoroutineStart.LAZY) {
                try {
                    downloads.withPermit {
                        run { // Download has its own deadline; indexing must not be timed as a network request.
                            val stamp=java.util.UUID.randomUUID().toString()
                            if(pl.type=="m3u") {
                                val movieKey=key(pl,"movie");val seriesKey=key(pl,"series")
                                SearchTransport.m3u(pl) {batch ->
                                    val movies=batch.filter {it.kind=="movie"}.map(::row)
                                    val series=batch.filter {it.kind=="series"}.map(::row)
                                    if(movies.isNotEmpty()) save(version) {store.put(movieKey,stamp,movies)}
                                    if(series.isNotEmpty()) save(version) {store.put(seriesKey,stamp,series)}
                                }
                                save(version) {store.complete(movieKey,stamp);store.complete(seriesKey,stamp)}
                            } else {
                                SearchTransport.xtream(pl,kind) {batch ->save(version) {store.put(requested,stamp,batch.map(::row))}}
                                save(version) {store.complete(requested,stamp)}
                            }
                        }
                    }
                    ticket.outcome=1
                } catch(e:CancellationException) {ticket.outcome=2; ticket.error="delai depasse"; if(e !is TimeoutCancellationException) throw e}
                catch(e:Exception) {ticket.outcome=2;ticket.error=failure(e)}
                finally {ticket.ended=System.currentTimeMillis()}
            }
            work[jobKey]=ticket;ticket.job.start();return ticket
        }
    }
    suspend fun prefetch(playlists:List<Playlist>,kind:String) = withContext(Dispatchers.IO) {
        if(kind!="movie" && kind!="series") return@withContext
        playlists.distinctBy {it.id}.sortedByDescending {it.id==Session.current?.id}
            .filter {it.type!="stalker"}.forEach {pl ->
                try {catalog(pl,kind)} catch(e:CancellationException) {throw e} catch(_:Exception) {}
            }
    }
    private data class View(val items:List<Item>,val limited:Boolean)
    private fun local(playlists:List<Playlist>,query:String,kind:String):View {
        val groups=LinkedHashMap<String,MutableList<SearchHit>>()
        var limited=false
        for(pl in playlists) {
            val rows=store.find(key(pl,kind),query)
            if(rows.size>SearchCatalogStore.PER_LIST_LIMIT) limited=true
            for(raw in rows.take(SearchCatalogStore.PER_LIST_LIMIT)) {
                val item=try {item(raw)}catch(_:Exception){continue}
                if(item.kind!=kind) continue
                groups.getOrPut(item.kind+":"+SearchText.fold(item.name)) {ArrayList()}.add(SearchHit(item,pl))
            }
        }
        val current=Session.current?.id
        val merged=groups.values.map {hits ->
            val rep=hits.firstOrNull {it.playlist.id==current}?:hits.first()
            rep.item.copy(ownerPlaylistId=rep.playlist.id,
                serverLabel=hits.sortedByDescending {it.playlist.id==current}.map {it.playlist.nom}.distinct().joinToString(" / "),
                logo=rep.item.logo.ifBlank {hits.firstOrNull {it.item.logo.isNotBlank()}?.item?.logo.orEmpty()})
        }.sortedWith(compareBy<Item> {SearchText.rank(SearchText.fold(it.name),SearchText.fold(query))}.thenBy {SearchText.fold(it.name)})
        return View(merged.take(300),limited||merged.size>300)
    }
    suspend fun search(playlists:List<Playlist>,query:String,kind:String,onProgress:suspend(Progress)->Unit) = coroutineScope {
        val ordered=playlists.distinctBy {it.id}.sortedByDescending {it.id==Session.current?.id}
        if(ordered.isEmpty()||SearchText.tokens(query).isEmpty()) {
            onProgress(Progress(emptyList(),0,ordered.size,0,0,false,true));return@coroutineScope
        }
        // Local results FIRST: never wait for a semaphore, handshake or network timeout.
        val initial=withContext(Dispatchers.IO) {local(ordered,query,kind)}
        onProgress(Progress(initial.items,0,ordered.size,0,ordered.size,initial.limited,false))
        val tickets=ordered.map {pl ->
            if(pl.type!="stalker") catalog(pl,kind) else {
                val catalogKey=key(pl,kind)
                val cached=store.queryState(catalogKey,query,System.currentTimeMillis()-5L*60L*1000L)
                val t=Ticket()
                val version=generation
                t.job=launch(Dispatchers.IO,start=CoroutineStart.LAZY) {
                    try {
                        if(cached>=0) {t.outcome=if(cached==1) 3 else 1;return@launch}
                        if((nativeBackoff[catalogKey]?:0L)>System.currentTimeMillis()) {t.outcome=2;t.error="nouvel essai dans quelques secondes";return@launch}
                        val limited=nativeRequests.withPermit {
                            withTimeout(45000L) {
                                val portal=portals.getOrPut(key(pl,"stalker-portal")) {SearchTransport.Portal(pl)}
                                portal.search(kind,query) {batch -> save(version) {store.put(catalogKey,"native",batch.map(::row))}}
                            }
                        }
                        save(version) {store.completeQuery(catalogKey,query,limited)}
                        t.outcome=if(limited) 3 else 1
                    } catch(e:CancellationException) {t.outcome=2;if(e is TimeoutCancellationException) {t.error="delai du portail depasse";nativeBackoff[catalogKey]=System.currentTimeMillis()+3000L} else throw e}
                    catch(e:Exception) {t.outcome=2;t.error=failure(e);nativeBackoff[catalogKey]=System.currentTimeMillis()+3000L}
                    finally {t.ended=System.currentTimeMillis()}
                }
                t.job.start();t
            }
        }
        var lastRevision=-1L;var lastState=""
        while(true) {
            ensureActive()
            val done=tickets.count {it.outcome!=0};val failed=tickets.count {it.outcome==2}
            val state="$done:$failed:${tickets.count {it.outcome==3}}"
            val revision=store.revision.get()
            if(revision!=lastRevision||state!=lastState) {
                val snapshot=withContext(Dispatchers.IO) {local(ordered,query,kind)}
                onProgress(Progress(snapshot.items,done,ordered.size,failed,ordered.size-done,
                    snapshot.limited||tickets.any {it.outcome==3},done==ordered.size,
                    tickets.mapIndexedNotNull {i,t -> if(t.outcome==2) "Liste ${playlists.indexOfFirst {it.id==ordered[i].id}+1}: ${t.error.ifBlank {"connexion ou reponse invalide"}}" else null}))
                lastRevision=revision;lastState=state
            }
            if(done==ordered.size) break
            delay(160)
        }
    }
    private fun failure(e:Exception):String = when {
        e is SearchTransport.HttpFailure -> "HTTP ${e.status}"
        e.message?.matches(Regex("HTTP_[0-9]{3}"))==true -> e.message!!.replace("_"," ")
        e.message?.startsWith("CACHE_ESPACE")==true -> "espace de cache insuffisant"
        e.message?.startsWith("CACHE_")==true -> "erreur du cache local"
        e.message=="RESEAU_DNS" -> "resolution DNS"
        e.message=="RESEAU_CONNEXION" -> "connexion au fournisseur"
        e is TimeoutCancellationException -> "delai depasse"
        else -> "reponse fournisseur ou session refusee"
    }
    private inline fun save(version:Long,action:()->Unit) {
        synchronized(lock) {
            if(version!=generation) throw CancellationException("Index invalide")
            action()
        }
    }
    fun invalidate() {
        scope.launch {
            synchronized(lock) {
                generation++
                work.values.forEach {it.job.cancel()};work.clear()
                store.invalidate();nativeBackoff.clear();portals.clear()
            }
        }
    }
}
