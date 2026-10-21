package com.kzplayer.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.Normalizer
import java.util.Locale

// Commande vocale globale (bouton micro). Ouvre la reconnaissance vocale de l'appareil,
// analyse la phrase et route vers l'action correspondante DANS LE THEME ACTIF.
// Aucun impact lecteur / flux / licence : on ne fait que lancer des ecrans existants
// ou changer un reglage (serveur / theme / couleur).
class VoiceActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fr-FR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Ex : Mets TF1  \u00b7  Recherche Titanic  \u00b7  Serveur voiture")
            // Plusieurs hypotheses : on essaie chacune pour mieux reconnaitre les noms courts (T18, M6...).
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }
        try {
            startActivityForResult(i, REQ)
        } catch (e: ActivityNotFoundException) {
            toast("Reconnaissance vocale indisponible sur cet appareil.")
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ && resultCode == RESULT_OK) {
            val cands = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
            if (cands.isNotEmpty()) route(cands)
        }
        finish()
    }

    // ---------- Analyse de la phrase ----------
    // On recoit plusieurs hypotheses de reconnaissance. On choisit en priorite celle qui
    // commence par un mot-cle de commande (mets, serveur, recherche...) ; sinon la meilleure.
    private fun route(rawCands: List<String>) {
        val cands = rawCands.map { normNum(norm(it)) }.filter { it.isNotBlank() }.distinct()
        if (cands.isEmpty()) return
        val chosen = cands.firstOrNull { isCommand(it.split(" ").firstOrNull().orEmpty()) } ?: cands[0]
        routeOne(chosen, cands)
    }

    private fun isCommand(w: String): Boolean = w in setOf(
        "recharge", "actualise", "rafraichi", "reload", "refresh",
        "serveur", "server", "liste",
        "recherche", "rechercher", "cherche", "chercher", "trouve", "trouver",
        "met", "mets", "mais", "mettre", "metre", "lance", "allume",
        "couleur", "couleurs", "theme", "chaine", "chaines", "numero",
        "suivante", "suivant", "precedente", "precedent",
        "film", "films", "cinema", "serie", "series",
        "direct", "tv", "television", "live", "guide"
    )

    private fun routeOne(t: String, cands: List<String>) {
        val words = t.split(" ").filter { it.isNotBlank() }
        if (words.isEmpty()) return
        val first = words[0]
        val rest = words.drop(1).joinToString(" ").trim()

        when {
            // 0. Recharger / mettre a jour les listes de lecture
            t.contains("recharge") || t.contains("actualise") || t.contains("rafraichi") ||
                t.contains("mets a jour") || t.contains("met a jour") || t.contains("mise a jour") ||
                first == "reload" || first == "refresh" ->
                reloadPlaylists()

            // 1. Changer de serveur / liste de lecture
            first == "serveur" || first == "server" || first == "liste" ->
                switchServer(rest)

            // 2. Recherche VOD (detecte film / serie dans la phrase)
            first == "recherche" || first == "rechercher" || first == "cherche" ||
                first == "chercher" || first == "trouve" || first == "trouver" ->
                openSearch(rest)

            // 3. Mettre une chaine : nom construit depuis TOUTES les hypotheses.
            first == "met" || first == "mets" || first == "mais" || first == "mettre" ||
                first == "metre" || first == "lance" || first == "allume" ->
                openChannel(channelNames(cands))

            // 4. Changer la couleur
            first == "couleur" || first == "couleurs" -> applyColor(rest.ifBlank { t })

            // 5. Changer de theme d'interface
            first == "theme" -> applyThemeSwitch(rest.ifBlank { t })

            // 6. Zapping
            first == "chaine" || first == "chaines" -> {
                when {
                    rest.startsWith("suivante") || rest.startsWith("suivant") ||
                        rest.startsWith("d apres") -> openSection("live")
                    rest.startsWith("precedente") || rest.startsWith("precedent") -> openSection("live")
                    rest.startsWith("numero") ->
                        openChannel(listOf(digits(rest).ifBlank { rest.removePrefix("numero").trim() }))
                    else -> openChannel(channelNames(cands))
                }
            }
            first == "numero" -> openChannel(listOf(digits(rest).ifBlank { rest }))
            first == "suivante" || first == "suivant" ||
                first == "precedente" || first == "precedent" -> openSection("live")

            // 7. Ouvrir une section (mot seul)
            t.contains("parametre") || t.contains("reglage") || t.contains("option") -> openSection("settings")
            t.contains("favori") -> openSection("favorites")
            first == "film" || first == "films" || first == "cinema" ->
                if (rest.isBlank()) openSection("movie") else searchIn("movie", rest)
            first == "serie" || first == "series" ->
                if (rest.isBlank()) openSection("series") else searchIn("series", rest)
            first == "direct" || first == "tv" || first == "television" ||
                first == "live" || first == "guide" -> openSection("live")

            // 8. Par defaut : PAS de recherche de film. Le micro sert surtout a lancer une chaine,
            //    donc on tente de la lancer (repli sur la liste si introuvable), dans le theme actif.
            else -> openChannel(channelNames(cands))
        }
    }

    // Retire le verbe d'action et les mots parasites, sur chaque hypothese -> liste de noms candidats.
    private fun channelNames(cands: List<String>): List<String> =
        cands.map { stripFillers(it) }.filter { it.isNotBlank() }.distinct()

    private val fillers = setOf(
        "met", "mets", "mais", "mettre", "metre", "lance", "allume", "sur", "la", "le", "les",
        "tele", "teles", "television", "televisions", "chaine", "chaines", "numero", "stp", "svp"
    )
    private fun stripFillers(s: String): String =
        s.split(" ").filter { it.isNotBlank() && it !in fillers }.joinToString(" ").trim()

    // ---------- Actions ----------
    private fun openBrowse(kind: String, query: String, title: String) {
        Session.browseTitle = title
        val i = Intent(this, BrowseActivity::class.java).putExtra("kind", kind)
        if (query.isNotBlank()) i.putExtra("voiceQuery", query)
        startActivity(i)
    }

    // Lance une chaine dans le MODE REDUIT du theme actif :
    // - NewTivi : apercu inline de NewLiveActivity ;
    // - Classique : apercu de BrowseActivity.
    // On envoie toutes les hypotheses (separees par des sauts de ligne) : l'ecran essaie chacune.
    private fun openChannel(names: List<String>) {
        val clean = names.filter { it.isNotBlank() }.distinct()
        if (clean.isEmpty()) { openSection("live"); return }
        toast("Chaine : ${clean.first()}")
        val payload = clean.joinToString("\n")
        if (ThemePref.isNew(this)) {
            startActivity(
                Intent(this, NewLiveActivity::class.java).putExtra("voicePlay", payload)
            )
        } else {
            Session.browseTitle = "TV"
            startActivity(
                Intent(this, BrowseActivity::class.java)
                    .putExtra("kind", "live")
                    .putExtra("voicePlay", payload)
            )
        }
    }

    // Recherche VOD : par defaut dans les films, mais si la phrase precise "serie(s)" ou
    // "film(s)" (ex : "recherche serie Breaking Bad"), on cible la bonne section.
    private fun openSearch(query: String) {
        if (query.isBlank()) { openSection("movie"); return }
        val words = norm(query).split(" ").filter { it.isNotBlank() }.toMutableList()
        var kind = "movie"
        if (words.isNotEmpty()) {
            when (words[0]) {
                "serie", "series", "seri", "seriz" -> { kind = "series"; words.removeAt(0) }
                "film", "films", "cinema" -> { kind = "movie"; words.removeAt(0) }
            }
        }
        val term = words.joinToString(" ").trim().ifBlank { norm(query) }
        searchIn(kind, term)
    }

    // Recherche integree dans le theme actif :
    // - NewTivi : ecrans Films/Series NewTivi (recherche multi-serveurs native) ;
    // - Classique : recherche de BrowseActivity.
    private fun searchIn(kind: String, query: String) {
        val q = query.trim()
        if (q.isBlank()) { openSection(if (kind == "series") "series" else "movie"); return }
        toast(if (kind == "series") "Recherche serie : $q" else "Recherche film : $q")
        if (ThemePref.isNew(this)) {
            val cls = if (kind == "series") NewSeriesActivity::class.java else NewMoviesActivity::class.java
            startActivity(Intent(this, cls).putExtra("voiceQuery", q))
        } else {
            openBrowse(kind, q, "Recherche")
        }
    }

    // Recharge licence + listes de lecture (equivaut au bouton "Recharger"), puis revient a l'accueil.
    private fun reloadPlaylists() {
        toast("Rechargement des listes...")
        val appCtx = applicationContext
        CoroutineScope(Dispatchers.Main).launch {
            val res = try {
                Api.checkLicense(
                    DeviceIdentity.stableId(appCtx),
                    DeviceIdentity.licenseCode(appCtx),
                    android.os.Build.MODEL ?: "Android TV", BuildConfig.VERSION_NAME
                )
            } catch (e: Exception) { null }
            if (res != null && res.ok && res.active) {
                Session.playlists = LocalPlaylists.merge(res.playlists)
                Session.expiration = res.expiration
                if (Session.current == null || Session.playlists.none { it.id == Session.current?.id }) {
                    Session.current = Session.playlists.firstOrNull()
                }
                Toast.makeText(appCtx, "Listes mises a jour.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(appCtx, res?.message?.ifBlank { "Echec du rechargement." } ?: "Echec du rechargement.", Toast.LENGTH_SHORT).show()
            }
            relaunchHome()
        }
    }

    private fun openSection(kind: String) {
        val isNew = ThemePref.isNew(this)
        when (kind) {
            "movie" -> if (isNew) start(NewMoviesActivity::class.java) else openBrowse("movie", "", "Films")
            "series" -> if (isNew) start(NewSeriesActivity::class.java) else openBrowse("series", "", "Series")
            "live" -> if (isNew) start(NewLiveActivity::class.java) else openBrowse("live", "", "TV")
            "favorites" -> openBrowse("favorites", "", "Favoris")
            "settings" -> start(SettingsActivity::class.java)
        }
    }

    private fun switchServer(name: String) {
        if (name.isBlank()) { toast("Dites le nom du serveur."); return }
        val q = norm(name)
        val pl = Session.playlists.firstOrNull { norm(it.nom) == q }
            ?: Session.playlists.firstOrNull { norm(it.nom).contains(q) || q.contains(norm(it.nom)) }
        if (pl == null) { toast("Serveur introuvable : $name"); return }
        Session.current = pl
        toast("Serveur : ${pl.nom}")
        relaunchHome()
    }

    private fun applyColor(text: String) {
        val gris = text.contains("gris") || text.contains("fonce")
        val base = when {
            text.contains("rouge") -> "red"
            text.contains("bleu") -> "blue"
            text.contains("vert") -> "green"
            text.contains("orange") -> "orange"
            text.contains("violet") || text.contains("mauve") -> "purple"
            text.contains("cyan") || text.contains("turquoise") -> "cyan"
            text.contains("rose") -> "pink"
            else -> ""
        }
        if (base.isBlank()) { toast("Couleur non reconnue."); return }
        val id = base + if (gris) "_gray" else "_black"
        val palette = ColorThemePref.ALL.firstOrNull { it.id == id }
            ?: ColorThemePref.ALL.firstOrNull { it.id.startsWith(base) }
        if (palette == null) { toast("Couleur non reconnue."); return }
        ColorThemePref.set(this, palette.id)
        toast("Couleur : ${palette.label}")
        relaunchHome()
    }

    private fun applyThemeSwitch(text: String) {
        when {
            text.contains("class") -> { ThemePref.set(this, ThemePref.CLASSIC); toast("Theme : Classique") }
            text.contains("new") || text.contains("tivi") || text.contains("nouveau") -> {
                ThemePref.set(this, ThemePref.NEWTIVI); toast("Theme : NewTivi")
            }
            else -> { toast("Theme non reconnu."); return }
        }
        relaunchHome()
    }

    private fun relaunchHome() {
        val cls = ThemePref.homeClass(this)
        startActivity(
            Intent(this, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
    }

    private fun start(cls: Class<*>) { startActivity(Intent(this, cls)) }

    private fun digits(s: String): String = s.filter { it.isDigit() }

    // Convertit les nombres dits en lettres en chiffres ("dix huit" -> "18"), puis colle
    // un sigle court a un nombre ("t 18" -> "t18", "m 6" -> "m6", "tf 1" -> "tf1").
    private fun normNum(s: String): String {
        val units = mapOf(
            "zero" to 0, "un" to 1, "une" to 1, "deux" to 2, "trois" to 3, "quatre" to 4, "cinq" to 5,
            "six" to 6, "sept" to 7, "huit" to 8, "neuf" to 9, "dix" to 10, "onze" to 11, "douze" to 12,
            "treize" to 13, "quatorze" to 14, "quinze" to 15, "seize" to 16,
            "vingt" to 20, "trente" to 30, "quarante" to 40, "cinquante" to 50, "soixante" to 60
        )
        val inTok = s.split(" ").filter { it.isNotBlank() }
        val out = ArrayList<String>()
        var i = 0
        while (i < inTok.size) {
            val w = inTok[i]
            val v = units[w]
            if (v != null) {
                var n = v
                if (v == 10 && i + 1 < inTok.size) {
                    val u = units[inTok[i + 1]]
                    if (u != null && u in 7..9) { n = 10 + u; i++ }
                } else if ((v == 20 || v == 30 || v == 40 || v == 50 || v == 60) && i + 1 < inTok.size) {
                    val nxt = inTok[i + 1]
                    if (nxt == "et" && i + 2 < inTok.size && units[inTok[i + 2]] == 1) { n = v + 1; i += 2 }
                    else { val u = units[nxt]; if (u != null && u in 1..9) { n = v + u; i++ } }
                }
                out.add(n.toString())
            } else out.add(w)
            i++
        }
        val joined = out.joinToString(" ")
        return Regex("\\b([a-z]{1,3}) (\\d+)").replace(joined) { it.groupValues[1] + it.groupValues[2] }
    }

    private fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.FRENCH), Normalizer.Form.NFD)
            .replace("\\p{Mn}".toRegex(), "")
            .replace("[^a-z0-9 ]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    companion object { private const val REQ = 4711 }
}
