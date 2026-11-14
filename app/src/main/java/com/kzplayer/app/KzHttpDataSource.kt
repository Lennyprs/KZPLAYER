package com.kzplayer.app

import android.content.Context
import androidx.media3.datasource.HttpDataSource
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

// v149 : HttpDataSource.Factory qui applique le DNS choisi (DoH) aux flux ExoPlayer.
//
// - Si DnsPref = SYSTEM (defaut, la majorite des utilisateurs) :
//     -> retourne un DefaultHttpDataSource.Factory NATIF, identique a l'ancien code.
//        Zero changement de comportement pour le lecteur.
// - Si l'utilisateur a choisi un DNS (Cloudflare/Quad9/AdGuard/...) :
//     -> retourne un OkHttpDataSource.Factory alimente par un OkHttp qui utilise DohDns.
//     -> conserve la tolerance SSL de Api.buildLenientClient (necessaire pour les
//        hebergeurs IPTV a certificats incomplets) sinon on aurait des flux qui
//        cassent uniquement pour les utilisateurs de DNS custom.
object KzHttpDataSource {
    // v386 : parametre "headers" facultatif. Vide par defaut => comportement identique
    // a avant pour TOUS les appels existants (direct Stalker, Xtream, M3U).
    // Utilise uniquement pour les films/series Stalker, dont certains portails refusent
    // le flux si la requete n a pas les memes en-tetes que le boitier MAG.
    fun factory(
        ctx: Context,
        userAgent: String,
        allowCrossProtocolRedirects: Boolean = true,
        headers: Map<String, String> = emptyMap()
    ): HttpDataSource.Factory {
        return if (DnsPref.current(ctx) == DnsPref.SYSTEM) {
            val f = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(allowCrossProtocolRedirects)
                .setUserAgent(userAgent)
                // v3.8.5 : Timeouts adaptés aux gros flux 4K VOD (fichiers volumineux sur CDN/disques distants)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(25000)
            if (headers.isNotEmpty()) {
                try { f.setDefaultRequestProperties(headers) } catch (e: Throwable) {}
            }
            f
        } else {
            val f = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(buildStreamClient())
                .setUserAgent(userAgent)
            if (headers.isNotEmpty()) {
                try { f.setDefaultRequestProperties(headers) } catch (e: Throwable) {}
            }
            f
        }
    }

    private fun buildStreamClient(): OkHttpClient {
        return try {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            val ssl = SSLContext.getInstance("TLS")
            ssl.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
            OkHttpClient.Builder()
                .sslSocketFactory(ssl.socketFactory, trustAll)
                .hostnameVerifier(HostnameVerifier { _, _ -> true })
                .dns(DohDns)
                .followRedirects(true)
                .followSslRedirects(true)
                .protocols(listOf(Protocol.HTTP_1_1))
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        } catch (_: Exception) {
            OkHttpClient.Builder()
                .dns(DohDns)
                .followRedirects(true)
                .followSslRedirects(true)
                .protocols(listOf(Protocol.HTTP_1_1))
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        }
    }
}
