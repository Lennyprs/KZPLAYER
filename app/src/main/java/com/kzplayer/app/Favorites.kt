package com.kzplayer.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// v396 : favoris ISOLES par serveur en mode simple, et annotes du nom du serveur
// en mode multi-listes (affiche sous chaque tuile via Item.serverLabel).
object Favorites {
    private const val PREF = "kz_favorites"
    private const val KEY = "items"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private data class StoredFav(val item: Item, val playlistId: String)

    private fun readAll(ctx: Context): List<StoredFav> {
        val raw = prefs(ctx).getString(KEY, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
        val out = ArrayList<StoredFav>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val it = Item(
                name = o.optString("name"),
                logo = o.optString("logo"),
                kind = o.optString("kind", "movie"),
                directUrl = o.optString("directUrl").ifBlank { null },
                streamId = o.optString("streamId").ifBlank { null },
                containerExt = o.optString("containerExt").ifBlank { null },
                seriesId = o.optString("seriesId").ifBlank { null },
                cmd = o.optString("cmd").ifBlank { null },
                description = o.optString("description"),
                duration = o.optString("duration"),
                summary = o.optString("summary"),
                added = o.optLong("added", 0L),
                season = o.optInt("season", 0),
                catchup = o.optBoolean("catchup", false)
            )
            if (it.name.isBlank()) continue
            out.add(StoredFav(it, o.optString("playlistId")))
        }
        return out
    }

    private fun visible(ctx: Context): List<StoredFav> {
        if (MultiListPref.isAll(ctx)) return readAll(ctx)
        val cur = Session.current?.id ?: return emptyList()
        return readAll(ctx).filter { it.playlistId == cur }
    }

    fun all(ctx: Context): List<Item> {
        val v = visible(ctx)
        if (!MultiListPref.isAll(ctx)) return v.map { it.item }
        val nameOf = HashMap<String, String>()
        for (p in Session.playlists) nameOf[p.id] = p.nom
        return v.map { s ->
            val label = nameOf[s.playlistId].orEmpty()
            if (label.isBlank()) s.item else s.item.copy(serverLabel = label)
        }
    }

    fun forKind(ctx: Context, kind: String): List<Item> {
        return all(ctx).filter { it.kind == kind }.sortedBy { it.name.lowercase() }
    }

    fun isFavorite(ctx: Context, item: Item): Boolean {
        val key = key(item)
        return visible(ctx).any { key(it.item) == key }
    }

    fun toggle(ctx: Context, item: Item): Boolean {
        val key = key(item)
        val curPl = Session.current?.id ?: ""
        val list = readAll(ctx).toMutableList()
        val idx = if (MultiListPref.isAll(ctx)) {
            list.indexOfFirst { key(it.item) == key }
        } else {
            list.indexOfFirst { key(it.item) == key && it.playlistId == curPl }
        }
        val nowFav = idx < 0
        if (idx >= 0) list.removeAt(idx) else list.add(0, StoredFav(item, curPl))
        save(ctx, list)
        return nowFav
    }

    private fun save(ctx: Context, items: List<StoredFav>) {
        val arr = JSONArray()
        for (s in items.take(300)) {
            val e = s.item
            arr.put(JSONObject().apply {
                put("name", e.name)
                put("logo", e.logo)
                put("kind", e.kind)
                put("directUrl", e.directUrl ?: "")
                put("streamId", e.streamId ?: "")
                put("containerExt", e.containerExt ?: "")
                put("seriesId", e.seriesId ?: "")
                put("cmd", e.cmd ?: "")
                put("description", e.description)
                put("duration", e.duration)
                put("summary", e.summary)
                put("added", e.added)
                put("season", e.season)
                put("catchup", e.catchup)
                put("playlistId", s.playlistId)
            })
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    private fun key(item: Item): String {
        return when {
            item.directUrl != null -> "url:${item.directUrl}"
            item.streamId != null -> "stream:${item.kind}:${item.streamId}"
            item.seriesId != null -> "series:${item.seriesId}"
            item.cmd != null -> "cmd:${item.kind}:${item.cmd}"
            else -> "name:${item.kind}:${item.name}"
        }
    }
}
