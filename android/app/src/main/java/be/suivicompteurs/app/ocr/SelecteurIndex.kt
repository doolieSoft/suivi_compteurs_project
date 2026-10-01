package be.suivicompteurs.app.ocr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/**
 * Proposition de lecture, toujours soumise à l'utilisateur avant enregistrement.
 *
 * @param valeur index retenu
 * @param brut suite de chiffres dont il provient, pour expliquer la proposition
 * @param confiance 0 à 1 : cohérence de la valeur avec l'historique connu
 */
data class Proposition(
    val valeur: Double,
    val brut: String,
    val confiance: Double,
    val explication: String,
)

/**
 * Choisit l'index parmi tout ce que la reconnaissance de texte a lu.
 *
 * Volontairement séparé de la reconnaissance elle-même : cette logique est la
 * partie faillible, et elle doit pouvoir être testée sans appareil photo ni
 * téléphone — ce que la classe [LecteurIndex], liée à ML Kit, interdirait.
 *
 * Le problème : une photo de compteur ne contient pas que l'index. Il y a un
 * numéro de série, une année de fabrication, un numéro d'agrément, un calibre.
 * Pire, chaque suite de chiffres peut se lire de plusieurs façons, le compteur
 * n'imprimant pas toujours le séparateur décimal : « 24981625 » vaut aussi bien
 * 24 981 625 que 24 981,625.
 *
 * Deux connaissances tranchent, dans cet ordre :
 *
 * 1. **Un index ne recule pas et progresse peu.** Toute lecture qui exigerait
 *    un bond démesuré est écartée — c'est ce qui élimine les numéros de série.
 * 2. **À vraisemblance égale, la lecture la plus complète gagne.** Entre
 *    « 24981 » et « 24981,625 », la seconde exploite tous les chiffres vus ;
 *    préférer la première reviendrait à jeter les décimales.
 *
 * Quand rien n'est vraisemblable, on propose quand même la lecture la moins
 * absurde, mais en l'annonçant : mieux vaut un champ pré-rempli signalé comme
 * douteux qu'un champ vide au fond d'une cave.
 */
object SelecteurIndex {

    /** Part du dernier index au-delà de laquelle une progression devient suspecte. */
    private const val PROGRESSION_SUSPECTE = 0.5

    /**
     * Progression toujours tolérée, quelle que soit la valeur de l'index.
     * Sans ce plancher, un compteur fraîchement posé — donc proche de zéro —
     * verrait toute lecture normale rejetée.
     */
    private const val PROGRESSION_PLANCHER = 100.0

    fun choisir(
        lignes: List<String>,
        dernierIndex: Double?,
        decimales: Int,
    ): Proposition? {
        val suites = suitesDeChiffres(lignes)
        if (suites.isEmpty()) return null

        val candidats = candidats(suites, decimales)
        if (candidats.isEmpty()) return null

        if (dernierIndex == null) {
            // Aucun historique : la suite la plus longue est le moins mauvais
            // choix, mais rien ne permet de la vérifier.
            val choix = candidats.maxByOrNull { it.brut.length } ?: return null
            return Proposition(
                valeur = choix.valeur,
                brut = choix.brut,
                confiance = 0.3,
                explication = "Aucun relevé antérieur : vérifiez attentivement.",
            )
        }

        val borne = max(dernierIndex * PROGRESSION_SUSPECTE, PROGRESSION_PLANCHER)
        val vraisemblables = candidats.filter {
            it.valeur >= dernierIndex && it.valeur - dernierIndex <= borne
        }

        if (vraisemblables.isNotEmpty()) {
            // La plus complète d'abord ; à nombre de chiffres égal, la plus proche.
            val choix = vraisemblables.maxWith(
                compareBy({ it.brut.length }, { -(it.valeur - dernierIndex) })
            )
            val ecart = choix.valeur - dernierIndex
            return Proposition(
                valeur = choix.valeur,
                brut = choix.brut,
                confiance = 0.85,
                explication = "Soit ${format(ecart)} depuis le dernier relevé.",
            )
        }

        // Rien de vraisemblable : il reste peut-être des lectures supérieures au
        // dernier index, mais toutes exigent un bond démesuré. On retient la
        // moins extravagante, en le disant.
        val excessive = candidats
            .filter { it.valeur >= dernierIndex }
            .minByOrNull { it.valeur - dernierIndex }
        if (excessive != null) {
            return Proposition(
                valeur = excessive.valeur,
                brut = excessive.brut,
                confiance = 0.3,
                explication = "Progression inhabituelle de " +
                    "${format(excessive.valeur - dernierIndex)} : à confirmer.",
            )
        }

        // Tout est en dessous du dernier index : un chiffre a échappé à la lecture.
        val approchant = candidats.minByOrNull { abs(it.valeur - dernierIndex) } ?: return null
        return Proposition(
            valeur = approchant.valeur,
            brut = approchant.brut,
            confiance = 0.15,
            explication = "Lecture inférieure au dernier index : corrigez à la main.",
        )
    }

    private data class Candidat(val valeur: Double, val brut: String)

    /**
     * Toutes les lectures envisageables : chaque suite de chiffres, avec la
     * virgule à toutes les positions plausibles.
     */
    private fun candidats(suites: List<String>, decimales: Int): List<Candidat> {
        val resultat = mutableListOf<Candidat>()
        suites.forEach { suite ->
            val entier = suite.toDoubleOrNull() ?: return@forEach
            for (d in 0..decimales) {
                if (suite.length - d < 1) continue
                resultat.add(Candidat(entier / 10.0.pow(d), suite))
            }
        }
        return resultat
    }

    /** Extrait les suites de chiffres, en ignorant tout le reste de la ligne. */
    internal fun suitesDeChiffres(lignes: List<String>): List<String> {
        val suites = mutableListOf<String>()
        lignes.forEach { ligne ->
            // Les séparateurs internes d'un affichage à tambours (espaces,
            // points, virgules) sont retirés : seuls les chiffres informent.
            val compacte = ligne.replace(Regex("[\\s.,]"), "")
            Regex("\\d{3,10}").findAll(compacte).forEach { suites.add(it.value) }
        }
        // Un compteur peut présenter sa partie entière et ses décimales sur deux
        // lignes distinctes (chiffres noirs puis chiffres rouges) : on tente
        // aussi les concaténations de suites voisines.
        val concatenations = mutableListOf<String>()
        for (i in 0 until suites.size - 1) {
            val jointure = suites[i] + suites[i + 1]
            if (jointure.length <= 10) concatenations.add(jointure)
        }
        return (suites + concatenations).distinct()
    }

    private fun format(valeur: Double): String =
        if (valeur >= 100) String.format("%.0f", valeur) else String.format("%.2f", valeur)
}
