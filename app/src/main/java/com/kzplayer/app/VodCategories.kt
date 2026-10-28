package com.kzplayer.app
import android.content.Context
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
/** Cache only category metadata, partitioned by provider identity and section, before visibility filtering. */
internal object VodCategories {
    private class Result(private val values:List<Category>,val fresh:Boolean):AbstractList<Category>(){
        override val size:Int get()=values.size
        override fun get(index:Int):Category=values[index]
    }
    private suspend fun fetch(pl:Playlist,kind:String):List<Category> = when(pl.type){
        "m3u"->Api.m3uCategories(pl,kind)
        "stalker"->Api.stalkerCategories(pl,kind)
        else->Api.xtreamCategories(pl,kind)
    }
    suspend fun load(ctx:Context,pl:Playlist,kind:String):List<Category>{
        if(kind!="movie"&&kind!="series")return fetch(pl,kind)
        return withContext(Dispatchers.IO){
            val store=CategorySnapshotStore(File(ctx.applicationContext.filesDir,"vod-categories-v134"))
            val key=CategorySnapshotStore.key(FastSearch.key(pl,kind),kind)
            val now=System.currentTimeMillis()
            val saved=store.read(key,now)?.let {raw->try{
                val array=JSONArray(raw);val out=ArrayList<Category>()
                for(i in 0 until array.length()){val o=array.optJSONObject(i)?:continue;val id=o.optString("id");val name=o.optString("name");if(id.isNotBlank()&&name.isNotBlank())out.add(Category(id,name))}
                out.takeIf {list->list.any {it.id!="__all__"}}
            }catch(_:Exception){null}}
            if(saved!=null&&store.fresh(key,now))return@withContext Result(saved,false)
            try{
                val fresh=fetch(pl,kind)
                if(fresh.any {it.id.isNotBlank()&&it.id!="__all__"&&it.name.isNotBlank()}){
                    val array=JSONArray();for(c in fresh)array.put(JSONObject().put("id",c.id).put("name",c.name))
                    store.write(key,array.toString(),now)
                    Result(fresh,true)
                }else if(saved!=null)Result(saved,false)else Result(fresh,true)
            }catch(e:Exception){if(e is CancellationException)throw e;if(saved!=null)Result(saved,false)else throw e}
        }
    }
    fun report(ctx:Context,pl:Playlist,kind:String,categories:List<Category>){
        // A restored snapshot is not a fresh provider health check or a new panel category report.
        if(categories is Result&&!categories.fresh)return
        CategorySync.report(ctx,pl,kind,categories)
    }
}
