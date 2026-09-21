package com.kzplayer.app

import android.content.Context

object Config {
    // URL backend reconstruite par morceaux pour eviter une chaine trop evidente dans l'APK.
    // Ce n'est pas une securite parfaite, mais ca complique l'extraction basique.
    private const val P1 = "https://script.google.com"
    private const val P2 = "/macros/s/"
    private const val P3 = "AKfycbwndI9dry8Q6DYoqD0PYeNFc23MjKjFNX9tPrcj4oUd6qMht6v_jlYwT1_8tJxICtk"
    const val LOGIN_PATH = "/exec"
    const val USER_AGENT = "KZPlayer/1.0 (Android)"

    val API_BASE: String get() = P1 + P2 + P3
    val LOGIN_URL: String get() = API_BASE.trimEnd('/') + LOGIN_PATH

    // ---------------- Fallback proxy Cloudflare (v144) ----------------
    // Quand la box du client bloque le DNS de script.google.com ("Unable to resolve
    // host"), l'app bascule automatiquement sur ce proxy Cloudflare Pages qui
    // relaye vers Apps Script en form-urlencoded.
    //
    // Mets ici l'URL de TON panel Cloudflare (sans /api/kz a la fin), par ex :
    //   "https://kzplayer.pages.dev"
    // Laisse vide si tu n'as pas de panel Cloudflare deploye.
    // L'utilisateur peut aussi la definir depuis Parametres > Panel Cloudflare.
    const val CF_PROXY_URL_DEFAULT = ""

    private const val PREFS = "kz_config"
    private const val KEY_CF = "cf_proxy_url"

    fun currentCfProxyUrl(ctx: Context): String {
        val saved = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CF, null)?.trim().orEmpty()
        val url = if (saved.isNotBlank()) saved else CF_PROXY_URL_DEFAULT
        return url.trim().trimEnd('/')
    }

    fun saveCfProxyUrl(ctx: Context, url: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_CF, url.trim().trimEnd('/')).apply()
        Api.cfProxyBase = currentCfProxyUrl(ctx)
    }

    // Anti-modification : mets ici le SHA-256 de TA signature release, sans les deux-points.
    // Tant que c'est vide, l'app ne bloque pas sur la signature.
    // Exemple format attendu : ABCD1234... en majuscules, sans ':' ni espaces.
    const val EXPECTED_RELEASE_SIGNATURE_SHA256 = "E4288AF20EF2962943F4A7A0056CCB2661DE9D2838A311CDF4FA39BC6197BE6C"

    // Cle API TMDB (v3) pour enrichir les episodes Stalker/M3U avec photos + resumes FR.
    // Cle gratuite a creer sur https://www.themoviedb.org (Parametres du compte > API > cle API v3).
    // Laisse vide pour desactiver l'enrichissement (aucun effet sur le reste de l'app).
    const val TMDB_API_KEY = "9ce341907d2f15740cde4a2e746b75c3"

    // ---------------- Licences administrateur (v356) ----------------
    // Empreintes SHA-256 (majuscules) des codes de licence autorises a activer le
    // journal de diagnostic. On ne stocke jamais le code lui-meme, seulement son
    // empreinte : personne ne peut retrouver la licence en lisant l APK.
    // Tant que la liste est vide, le journal est masque pour absolument tout le monde.
    // Pour ajouter un appareil : appui long sur la carte "Mise a jour" des parametres,
    // relever l empreinte affichee, puis la coller ici.
    val ADMIN_LICENSE_HASHES: List<String> = listOf(
        // Appareil principal de l administrateur (empreinte relevee le 22/08).
        "7B96DD7EC22D1FFA68ADC75D02166C68A3063CFFF24DAA8A86247C1C28F203A5"
    )
}
