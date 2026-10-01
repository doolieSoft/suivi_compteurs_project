package be.suivicompteurs.app.ocr

import kotlin.math.abs
import kotlin.math.floor
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
    /**
     * Suites de chiffres trouvées sur la photo, celle retenue comprise.
     *
     * Affichées quand la confiance est faible : sans elles, l'utilisateur ne
     * peut pas distinguer « la reconnaissance n'a pas vu l'index » de « elle
     * l'a vu et s'est trompée » — or le geste à faire n'est pas le même.
     */
    val suitesLues: List<String> = emptyList(),
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

    /**
     * Longueur à partir de laquelle on exige des chiffres de tête communs.
     * En deçà, l'index est trop petit pour que ses premiers chiffres portent
     * une information stable : un compteur fraîchement posé passe
     * légitimement de 21 à 61 m³ sans en partager aucun.
     */
    private const val CHIFFRES_POUR_EXIGER_LE_PREFIXE = 4

    /**
     * Chiffres de tête que l'index doit conserver. Deux suffisent à écarter le
     * numéro de série « 27364770 », qui ne partage que le « 2 » initial avec
     * 24978, sans rejeter une progression normale.
     */
    private const val PREFIXE_MINIMAL = 2

    /**
     * @param incrementMax progression au-delà de laquelle la lecture devient
     *   douteuse, calculée à partir des jours écoulés et du débit journalier
     *   déjà observé. Quand elle est inconnue, on retombe sur une règle
     *   grossière proportionnelle à l'index, bien plus permissive.
     */
    fun choisir(
        lignes: List<String>,
        dernierIndex: Double?,
        decimales: Int,
        incrementMax: Double? = null,
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
                suitesLues = suites,
            )
        }

        val progressions = candidats.filter { it.valeur >= dernierIndex }

        if (progressions.isNotEmpty()) {
            // Un compteur mécanique a un nombre FIXE de chiffres entiers — cinq
            // ici, et pour longtemps : 24978 ne deviendra 100000 qu'au bout de
            // 75 000 m³. Toute lecture qui en compte un autre nombre vient
            // d'ailleurs sur la plaque.
            val chiffresAttendus = chiffresEntiers(dernierIndex)

            // Et les premiers chiffres ne changent quasiment jamais : entre deux
            // relevés, 24978 devient 24979, jamais 27364. C'est ce qui distingue
            // l'index du numéro de série quand les deux ont la bonne longueur.
            // Un cadran isolé rend une suite de longueur connue : cinq chiffres
            // noirs plus trois rouges sur ce compteur. Quand la lecture tombe
            // juste sur ce compte, la position de la virgule n'est plus une
            // hypothèse mais une certitude, et prime sur tout le reste.
            val longueurAttendue = chiffresAttendus + decimales

            val classement = compareBy<Candidat>(
                { if (it.brut.length == longueurAttendue) 0 else 1 },
                { if (chiffresEntiers(it.valeur) == chiffresAttendus) 0 else 1 },
                { -prefixeCommun(it.valeur, dernierIndex) },
                { floor(it.valeur) },
                { -it.brut.length },
            )
            val choix = progressions.sortedWith(classement).first()

            // Une lecture qui n'a ni la bonne longueur ni les bons chiffres de
            // tête n'est pas l'index : c'est autre chose sur la plaque, et le
            // dire vaut mieux que de pré-remplir le champ d'une valeur fausse.
            val ressemble = chiffresEntiers(choix.valeur) == chiffresAttendus &&
                (
                    chiffresAttendus < CHIFFRES_POUR_EXIGER_LE_PREFIXE ||
                        prefixeCommun(choix.valeur, dernierIndex) >= PREFIXE_MINIMAL
                    )

            val ecart = choix.valeur - dernierIndex
            val borne = incrementMax
                ?: max(dernierIndex * PROGRESSION_SUSPECTE, PROGRESSION_PLANCHER)
            val suspecte = ecart > borne || !ressemble
            return Proposition(
                valeur = choix.valeur,
                brut = choix.brut,
                confiance = if (suspecte) 0.2 else 0.85,
                explication = when {
                    !ressemble ->
                        "Aucune suite de chiffres ne ressemble à l'index : " +
                            "saisissez-le à la main."
                    ecart > borne ->
                        "Progression inhabituelle de ${format(ecart)} : à confirmer."
                    else -> "Soit ${format(ecart)} depuis le dernier relevé."
                },
                suitesLues = suites,
            )
        }

        // Tout est en dessous du dernier index : un chiffre a échappé à la lecture.
        val approchant = candidats.minByOrNull { abs(it.valeur - dernierIndex) } ?: return null
        return Proposition(
            valeur = approchant.valeur,
            brut = approchant.brut,
            confiance = 0.15,
            explication = "Lecture inférieure au dernier index : corrigez à la main.",
            suitesLues = suites,
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

    /** Nombre de chiffres de la partie entière. */
    private fun chiffresEntiers(valeur: Double): Int =
        floor(valeur).toLong().coerceAtLeast(0L).toString().length

    /**
     * Nombre de chiffres de tête communs aux parties entières.
     *
     * Entre deux relevés d'un même compteur, seuls les derniers chiffres
     * bougent : 24978 et 24979 partagent quatre chiffres, 24978 et 27364 un
     * seul. C'est le signal le plus discriminant dont on dispose.
     */
    private fun prefixeCommun(a: Double, b: Double): Int {
        val ga = floor(a).toLong().coerceAtLeast(0L).toString()
        val gb = floor(b).toLong().coerceAtLeast(0L).toString()
        var commun = 0
        while (commun < ga.length && commun < gb.length && ga[commun] == gb[commun]) {
            commun++
        }
        return commun
    }

    private fun format(valeur: Double): String =
        if (valeur >= 100) String.format("%.0f", valeur) else String.format("%.2f", valeur)
}
