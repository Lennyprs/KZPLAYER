package com.kzplayer.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Live history is separate: channel clicks never evict film/episode progress. */
object LiveHistory {
    private const val PREF="kz_live_recent_v360"
    private const val MAX=80
    private fun read(ctx:Context):JSONArray = try{JSONArray(ctx.getSharedPreferences(PREF,Context.MODE_PRIVATE).getString("items","[]")?:"[]")}catch(_:Exception){JSONArray()}
    @Synchronized fun touch(ctx:Context,item:Item,playlist:Playlist?=Session.current){
        val pl=playlist?:return
        if(item.kind!="live"&&item.kind!="channel")return
        if(item.name.isBlank())return
        // Never save a one-use create_link URL in place of its original portal command.
        if(pl.type=="stalker"&&item.cmd.isNullOrBlank())return
        val identity=LiveRecentKey.of(pl.id,item.streamId,item.cmd,item.directUrl,item.name)
        val next=JSONArray();next.put(JSONObject().put("key",identity).put("source",FastSearch.key(pl,"live-history")).put("playlist",pl.id).put("name",item.name).put("logo",item.logo)
            .put("url",if(pl.type=="stalker")"" else item.directUrl.orEmpty()).put("cmd",item.cmd.orEmpty()).put("id",item.streamId.orEmpty())
            .put("catchup",item.catchup).put("at",System.currentTimeMillis()))
        item.directUrl?.let {url->Api.m3uUserAgents[url]?.let {agent->next.getJSONObject(0).put("ua",agent)}}
        val old=read(ctx)
        for(i in 0 until old.length()){val entry=old.optJSONObject(i)?:continue;if(entry.optString("key")!=identity&&next.length()<MAX)next.put(entry)}
        ctx.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().putString("items",next.toString()).apply()
    }
    @Synchronized fun recentItems(ctx:Context):List<Item>{
        val lists=Session.playlists.associateBy {it.id};val multi=MultiListPref.isAll(ctx);val current=Session.current?.id
        val array=read(ctx);val out=ArrayList<Item>()
        for(i in 0 until array.length()){
            val e=array.optJSONObject(i)?:continue;val owner=e.optString("playlist");val pl=lists[owner]?:continue
            if(e.optString("source")!=FastSearch.key(pl,"live-history"))continue
            if(!multi&&owner!=current)continue
            val direct=e.optString("url").takeIf {it.isNotBlank()}
            if(pl.type=="m3u"&&direct!=null&&e.optString("ua").isNotBlank())Api.m3uUserAgents[direct]=e.optString("ua")
            out.add(Item(name=e.optString("name"),logo=e.optString("logo"),kind="live",
                directUrl=if(pl.type=="stalker")null else e.optString("url").takeIf {it.isNotBlank()},
                cmd=e.optString("cmd").takeIf {it.isNotBlank()},streamId=e.optString("id").takeIf {it.isNotBlank()},
                catchup=e.optBoolean("catchup"),added=e.optLong("at"),ownerPlaylistId=owner,serverLabel=if(multi)pl.nom else ""))
        }
        return out
    }
    fun activateOwner(item:Item){if(item.ownerPlaylistId.isNotBlank())Session.playlists.firstOrNull {it.id==item.ownerPlaylistId}?.let {Session.current=it}}
}
