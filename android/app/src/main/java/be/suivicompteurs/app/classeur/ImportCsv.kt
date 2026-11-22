package be.suivicompteurs.app.classeur

import java.time.LocalDate

/**
 * Relevés d'un compteur repris d'un fichier CSV à deux colonnes, date et
 * index, tel qu'en exportent les applications de suivi.
 *
 * On ne sait rien de l'application d'origine : le séparateur (`;`, tabulation
 * ou `,`), la ligne d'en-tête, l'ordre des colonnes, le format des dates et la
 * virgule décimale se devinent. Une heure après la date est ignorée ; deux
 * relevés du même jour n'en font qu'un, le plus haut.
 */
object ImportCsv {

    /** Au-delà, ce n'est pas un export de relevés. */
    const val TAILLE_MAX = 5_000_000

    enum class Raison { DATE_ILLISIBLE, INDEX_ILLISIBLE }

    /** Ligne écartée : [numero] compte à partir de 1, comme dans un tableur. */
    data class LigneIgnoree(val numero: Int, val contenu: String, val raison: Raison)

    data class Lecture(
        /** Par date croissante, un seul index par jour. */
        val releves: List<Pair<LocalDate, Double>>,
        val ignorees: List<LigneIgnoree>,
        /** Relevés du même jour qu'un autre, fondus dans le plus haut. */
        val memeJour: Int = 0,
    )

    fun lire(texte: String): Lecture {
        val lignes = texte.removePrefix("\uFEFF").lines()
            .withIndex()
            .filter { it.value.isNotBlank() }
        if (lignes.isEmpty()) return Lecture(emptyList(), emptyList())

        val separateur = deviner(lignes.take(20).map { it.value })
        val champs = lignes.map { (i, ligne) -> i + 1 to decouper(ligne, separateur) }
        val ordreDates = ordreDates(champs.flatMap { it.second.take(2) })

        val lus = mutableListOf<Pair<LocalDate, Double>>()
        val ignorees = mutableListOf<LigneIgnoree>()
        for ((rang, entree) in champs.withIndex()) {
            val (numero, valeurs) = entree
            // Date d'abord ; sinon, colonnes inversées.
            val date0 = valeurs.getOrNull(0)?.let { lireDate(it, ordreDates) }
            val date1 = if (date0 == null) valeurs.getOrNull(1)?.let { lireDate(it, ordreDates) } else null
            val date = date0 ?: date1
            val index = (if (date0 != null) valeurs.getOrNull(1) else valeurs.getOrNull(0))?.let(::lireNombre)
            when {
                // La première ligne sans date est l'en-tête.
                date == null && rang == 0 -> continue
                date == null -> ignorees += LigneIgnoree(numero, lignes[rang].value.trim(), Raison.DATE_ILLISIBLE)
                index == null -> ignorees += LigneIgnoree(numero, lignes[rang].value.trim(), Raison.INDEX_ILLISIBLE)
                else -> lus += date to index
            }
        }

        val parJour = lus.groupBy({ it.first }, { it.second })
        return Lecture(
            releves = parJour.map { (jour, index) -> jour to index.max() }.sortedBy { it.first },
            ignorees = ignorees,
            memeJour = lus.size - parJour.size,
        )
    }

    /** Ce que deviendraient ces relevés face à ceux du compteur. */
    data class Tri(
        val retenus: List<Pair<LocalDate, Double>>,
        /** Jours déjà relevés dans l'application : on ne les écrase pas. */
        val dejaReleves: List<LocalDate>,
        /** Index plus bas qu'un relevé antérieur, ou plus haut qu'un postérieur. */
        val incoherents: List<Pair<LocalDate, Double>>,
    )

    /**
     * Mêmes règles qu'à la saisie : un index ne recule pas. Chaque relevé est
     * comparé à ses voisins, ceux du compteur comme ceux déjà retenus.
     * [existants] donne, par jour, l'index et s'il suit un tour complet.
     */
    fun trier(releves: List<Pair<LocalDate, Double>>, existants: Map<LocalDate, Pair<Double, Boolean>>): Tri {
        val connus = java.util.TreeMap<LocalDate, Pair<Double, Boolean>>(existants)
        val retenus = mutableListOf<Pair<LocalDate, Double>>()
        val deja = mutableListOf<LocalDate>()
        val incoherents = mutableListOf<Pair<LocalDate, Double>>()
        for ((jour, index) in releves.sortedBy { it.first }) {
            if (jour in connus) { deja += jour; continue }
            val avant = connus.lowerEntry(jour)?.value
            val apres = connus.higherEntry(jour)?.value
            val recule = avant != null && index < avant.first
            val depasse = apres != null && index > apres.first && !apres.second
            if (recule || depasse) { incoherents += jour to index; continue }
            connus[jour] = index to false
            retenus += jour to index
        }
        return Tri(retenus, deja, incoherents)
    }

    // --- lecture ------------------------------------------------------------

    /** Le séparateur présent sur le plus de lignes : `;` et tabulation avant `,`, souvent décimale. */
    internal fun deviner(lignes: List<String>): Char {
        val candidats = listOf(';', '\t', ',')
        return candidats.maxByOrNull { s -> lignes.count { s in it } * 10 - candidats.indexOf(s) }
            ?.takeIf { s -> lignes.any { s in it } } ?: ','
    }

    /** Découpe une ligne, guillemets compris (`"1,5"` reste un seul champ). */
    internal fun decouper(ligne: String, separateur: Char): List<String> {
        val champs = mutableListOf<String>()
        val courant = StringBuilder()
        var entreGuillemets = false
        var i = 0
        while (i < ligne.length) {
            val c = ligne[i]
            when {
                c == '"' && entreGuillemets && ligne.getOrNull(i + 1) == '"' -> { courant.append('"'); i++ }
                c == '"' -> entreGuillemets = !entreGuillemets
                c == separateur && !entreGuillemets -> { champs += courant.toString().trim(); courant.clear() }
                else -> courant.append(c)
            }
            i++
        }
        champs += courant.toString().trim()
        return champs
    }

    /** Jour, mois, année : seul ordre ambigu, le 03/04 européen ou américain. */
    internal enum class Ordre { JOUR_MOIS, MOIS_JOUR }

    private val DATE_ISO = Regex("""(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})""")
    private val DATE_ANNEE_FIN = Regex("""(\d{1,2})[-/.](\d{1,2})[-/.](\d{4})""")

    /** Sans indice contraire (un 13 en deuxième position), le jour vient d'abord. */
    internal fun ordreDates(valeurs: List<String>): Ordre {
        var jourDabord = false
        var moisDabord = false
        for (v in valeurs) {
            val m = DATE_ANNEE_FIN.matchEntire(sansHeure(v)) ?: continue
            val (a, b) = m.destructured
            if (a.toInt() > 12) jourDabord = true
            if (b.toInt() > 12) moisDabord = true
        }
        return if (moisDabord && !jourDabord) Ordre.MOIS_JOUR else Ordre.JOUR_MOIS
    }

    /** « 2024-01-31 14:05 », « 2024-01-31T14:05:00Z » → la date seule. */
    private fun sansHeure(valeur: String): String = valeur.trim().split(' ', 'T').first()

    internal fun lireDate(valeur: String, ordre: Ordre): LocalDate? {
        val texte = sansHeure(valeur)
        return runCatching {
            DATE_ISO.matchEntire(texte)?.destructured?.let { (a, m, j) ->
                LocalDate.of(a.toInt(), m.toInt(), j.toInt())
            } ?: DATE_ANNEE_FIN.matchEntire(texte)?.destructured?.let { (x, y, a) ->
                if (ordre == Ordre.JOUR_MOIS) LocalDate.of(a.toInt(), y.toInt(), x.toInt())
                else LocalDate.of(a.toInt(), x.toInt(), y.toInt())
            }
        }.getOrNull()
    }

    private val NOMBRE = Regex("""[-+]?[\d.,\s  ']*\d""")

    /**
     * « 1234,5 », « 1234.5 », « 1 234,5 », « 1.234,5 », « 1,234.5 », « 1234,5 kWh ».
     * Avec un point et une virgule, le dernier des deux est la décimale.
     */
    internal fun lireNombre(valeur: String): Double? {
        val brut = NOMBRE.find(valeur.trim())?.value ?: return null
        // Une unité, oui ; du texte avant le nombre, non.
        if (valeur.trim().indexOf(brut) != 0) return null
        var chiffres = brut.filterNot { it.isWhitespace() || it == ' ' || it == ' ' || it == '\'' }
        val point = chiffres.lastIndexOf('.')
        val virgule = chiffres.lastIndexOf(',')
        chiffres = when {
            point >= 0 && virgule >= 0 && virgule > point -> chiffres.replace(".", "").replace(',', '.')
            point >= 0 && virgule >= 0 -> chiffres.replace(",", "")
            virgule >= 0 -> chiffres.replace(',', '.')
            else -> chiffres
        }
        return chiffres.toDoubleOrNull()?.takeIf { it.isFinite() }
    }
}
