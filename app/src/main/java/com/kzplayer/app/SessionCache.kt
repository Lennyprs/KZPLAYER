package com.kzplayer.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// v375 : cache local des serveurs (playlists).
//
// But : ne PLUS JAMAIS afficher "Chargement des serveurs...".
// Avant, si le process etait relance (retour du lecteur, memoire liberee par
// Android, appli remise en avant), Session.playlists etait vide et l'ecran
// repartait sur un appel reseau checkLicense avec un message d'attente.
//
// Maintenant, la liste des serveurs est ecrite sur le disque a chaque
// chargement reussi, et restauree INSTANTANEMENT (sans reseau, sans attente)
// des qu'un ecran en a besoin. Aucun message de chargement n'est necessaire.
//
// N'appelle aucune API, ne touche ni au lecteur ni a Stalker : c'est juste une
// copie locale de ce que le backend a deja renvoye.
object SessionCache {
    private const val PREFS = "kz_session_cache"
    private const val KEY_PL = "playlists_json"
    private const val KEY_CUR = "current_id"
    private const val KEY_EXP = "expiration"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Sauvegarde la session courante (appelee apres chaque checkLicense reussi).
    fun save(ctx: Context) {
        try {
            val arr = JSONArray()
            for (p in Session.playlists) arr.put(toJson(p))
            prefs(ctx).edit()
                .putString(KEY_PL, arr.toString())
                .putString(KEY_CUR, Session.current?.id ?: "")
                .putString(KEY_EXP, Session.expiration ?: "")
                .apply()
        } catch (_: Throwable) {}
    }

    // Restaure la session depuis le disque si elle est vide.
    // Retourne true si on a bien des serveurs disponibles apres l'appel.
    fun restore(ctx: Context): Boolean {
        try {
            if (Session.playlists.isNotEmpty()) {
                if (Session.current == null) Session.current = Session.playlists.firstOrNull()
                return Session.current != null
            }
            val raw = prefs(ctx).getString(KEY_PL, "").orEmpty()
            if (raw.isBlank()) return false
            val arr = JSONArray(raw)
            val out = ArrayList<Playlist>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(fromJson(o))
            }
            if (out.isEmpty()) return false
            Session.playlists = out
            val curId = prefs(ctx).getString(KEY_CUR, "").orEmpty()
            Session.current = out.firstOrNull { it.id == curId } ?: out.firstOrNull()
            val exp = prefs(ctx).getString(KEY_EXP, "").orEmpty()
            if (Session.expiration.isNullOrBlank() && exp.isNotBlank()) Session.expiration = exp
            return Session.current != null
        } catch (_: Throwable) {
            return false
        }
    }

    fun has(ctx: Context): Boolean =
        Session.playlists.isNotEmpty() || prefs(ctx).getString(KEY_PL, "").orEmpty().isNotBlank()

    fun clear(ctx: Context) {
        try { prefs(ctx).edit().clear().commit() } catch (_: Throwable) {}
        Session.clear()
    }

    private fun toJson(p: Playlist): JSONObject {
        val o = JSONObject()
        o.put("id", p.id)
        o.put("type", p.type)
        o.put("nom", p.nom)
        o.put("serverUrl", p.serverUrl)
        o.put("username", p.username)
        o.put("password", p.password)
        o.put("mac", p.mac)
        o.put("m3uUrl", p.m3uUrl)
        o.put("stalkerSn", p.stalkerSn)
        o.put("stalkerDeviceId", p.stalkerDeviceId)
        o.put("stalkerDeviceId2", p.stalkerDeviceId2)
        o.put("stalkerSignature", p.stalkerSignature)
        o.put("stalkerMetrics", p.stalkerMetrics)
        o.put("stalkerHwVersion2", p.stalkerHwVersion2)
        o.put("stalkerTimestamp", p.stalkerTimestamp)
        o.put("stalkerPrehash", p.stalkerPrehash)
        o.put("stalkerApiSignature", p.stalkerApiSignature)
        o.put("stalkerImageVersion", p.stalkerImageVersion)
        o.put("stalkerVer", p.stalkerVer)
        o.put("hiddenCategories", JSONArray(p.hiddenCategories))
        val sb = JSONObject()
        for ((k, v) in p.shownByKind) sb.put(k, JSONArray(v))
        o.put("shownByKind", sb)
        return o
    }

    private fun strList(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        val out = ArrayList<String>(a.length())
        for (i in 0 until a.length()) out.add(a.optString(i, ""))
        return out.filter { it.isNotBlank() }
    }

    private fun fromJson(o: JSONObject): Playlist {
        val shown = HashMap<String, List<String>>()
        val sb = o.optJSONObject("shownByKind")
        if (sb != null) {
            val keys = sb.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                shown[k] = strList(sb.optJSONArray(k))
            }
        }
        return Playlist(
            id = o.optString("id", ""),
            type = o.optString("type", ""),
            nom = o.optString("nom", ""),
            serverUrl = o.optString("serverUrl", ""),
            username = o.optString("username", ""),
            password = o.optString("password", ""),
            mac = o.optString("mac", ""),
            m3uUrl = o.optString("m3uUrl", ""),
            stalkerSn = o.optString("stalkerSn", ""),
            stalkerDeviceId = o.optString("stalkerDeviceId", ""),
            stalkerDeviceId2 = o.optString("stalkerDeviceId2", ""),
            stalkerSignature = o.optString("stalkerSignature", ""),
            stalkerMetrics = o.optString("stalkerMetrics", ""),
            stalkerHwVersion2 = o.optString("stalkerHwVersion2", ""),
            stalkerTimestamp = o.optString("stalkerTimestamp", ""),
            stalkerPrehash = o.optString("stalkerPrehash", ""),
            stalkerApiSignature = o.optString("stalkerApiSignature", ""),
            stalkerImageVersion = o.optString("stalkerImageVersion", ""),
            stalkerVer = o.optString("stalkerVer", ""),
            hiddenCategories = strList(o.optJSONArray("hiddenCategories")),
            shownByKind = shown
        )
    }
}
