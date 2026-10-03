package be.suivicompteurs.app.moteur

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max

/*
 * Calcul des consommations à partir des relevés d'index.
 *
 * Deux difficultés que ce fichier résout :
 *
 * 1. Les relevés sont irréguliers. Entre deux index peuvent s'écouler 7 jours
 *    comme 300. Pour comparer des années, il faut répartir chaque écart d'index
 *    sur les jours qu'il couvre : c'est la *ventilation*.
 *
 * 2. Répartir linéairement fausse le gaz. 300 m³ consommés d'octobre à mars
 *    suivent le froid. On ajuste donc, par compteur, un modèle
 *    `conso_jour = base + k × DJ_jour` où `base` couvre l'eau chaude et la
 *    cuisson et `k` mesure la sensibilité au climat. La ventilation suit ce
 *    modèle en conservant exactement le volume mesuré.
 *
 * Ce même `k` sert à la normalisation climatique :
 * `conso_corrigée = conso_mesurée + k × (DJ_normal − DJ_réel)`.
 *
 * Portage de `suivi/services/consommation.py` : les deux doivent donner les
 * mêmes résultats (voir ParitePythonTest).
 */

/** En deçà, l'ajustement thermique n'est pas jugé fiable. */
const val MIN_PERIODES_MODELE = 4

/**
 * Écart de DJ moyen/jour entre la période la plus douce et la plus froide. Bas
 * à dessein : sur des relevés annuels, l'amplitude dépasse rarement 1,5.
 */
const val MIN_AMPLITUDE_DJ = 1.0

// ---------------------------------------------------------------------------
// Périodes entre relevés
// ---------------------------------------------------------------------------

/** Intervalle entre deux relevés consécutifs ; ses jours vont de `debut + 1` à `fin`. */
data class Periode(
    val compteur: Compteur,
    val debut: LocalDate,
    val fin: LocalDate,
    val volume: Double,
) {
    val nbJours: Int get() = ecartJours(debut, fin)

    val volumeJournalier: Double get() = if (nbJours != 0) volume / nbJours else 0.0

    val jours: Sequence<LocalDate> get() = jours(debut.plusDays(1), fin)
}

/**
 * Périodes de consommation d'une ligne de compteurs.
 *
 * Les écarts ne sont jamais calculés d'un compteur à l'autre. [jusqua] écarte
 * les relevés postérieurs : on retrouve ce que l'on savait ce jour-là.
 */
fun periodes(compteurs: List<Compteur>, jusqua: LocalDate? = null): List<Periode> {
    val resultat = mutableListOf<Periode>()
    for (compteur in compteurs) {
        val releves = compteur.releves
            .filter { jusqua == null || it.date <= jusqua }
            .sortedBy { it.date }
        for ((avant, apres) in releves.zipWithNext()) {
            val volume = apres.index - avant.index
            // Index en recul : compteur remis à zéro ou saisie erronée.
            if (apres.date <= avant.date || volume < 0) continue
            resultat += Periode(compteur, avant.date, apres.date, volume)
        }
    }
    return resultat.sortedWith(compareBy({ it.debut }, { it.fin }))
}

// ---------------------------------------------------------------------------
// Modèle thermique
// ---------------------------------------------------------------------------

/** Régression `conso_jour = base + k × DJ_jour`. */
data class ModeleThermique(
    val base: Double = 0.0,
    val k: Double = 0.0,
    val r2: Double = 0.0,
    val nbPeriodes: Int = 0,
    val fiable: Boolean = false,
    internal val djReference: Double = 2000.0,
) {
    fun attendu(djDuJour: Double): Double = base + k * djDuJour

    /** Part de la consommation annuelle imputable au chauffage. */
    val partChauffagePct: Double?
        get() {
            if (!fiable) return null
            val total = base * 365 + k * djReference
            if (total <= 0) return null
            return 100.0 * (k * djReference) / total
        }
}

/**
 * Ajuste le modèle par moindres carrés pondérés : chaque période fournit un
 * point (DJ moyen/jour, conso moyenne/jour) pesant sa durée.
 */
fun ajusterModele(periodes: List<Periode>, djs: Map<LocalDate, Double>): ModeleThermique {
    data class Point(val x: Double, val y: Double, val poids: Double)

    val points = mutableListOf<Point>()
    for (periode in periodes) {
        if (periode.nbJours <= 0) continue
        // Deux index identiques sur une longue période traduisent un relevé
        // manquant, pas une consommation nulle : le point fausserait la droite.
        if (periode.volume <= 0) continue
        val jours = periode.jours.toList()
        if (!jours.all { it in djs }) continue // couverture météo incomplète
        var djTotal = 0.0
        for (jour in jours) djTotal += djs.getValue(jour)
        points += Point(djTotal / periode.nbJours, periode.volumeJournalier, periode.nbJours.toDouble())
    }

    val vide = ModeleThermique(nbPeriodes = points.size)
    if (points.size < MIN_PERIODES_MODELE) return vide
    if (points.maxOf { it.x } - points.minOf { it.x } < MIN_AMPLITUDE_DJ) return vide

    val poidsTotal = points.somme { it.poids }
    val xMoy = points.somme { it.x * it.poids } / poidsTotal
    val yMoy = points.somme { it.y * it.poids } / poidsTotal

    val variance = points.somme { it.poids * (it.x - xMoy) * (it.x - xMoy) }
    if (variance <= 0) return vide

    val covariance = points.somme { it.poids * (it.x - xMoy) * (it.y - yMoy) }
    var k = covariance / variance
    var base = yMoy - k * xMoy

    if (k <= 0) return vide // insensible au froid
    if (base < 0) {
        // Une base négative n'a pas de sens : on refait passer la droite par 0.
        val denominateur = points.somme { it.poids * it.x * it.x }
        k = if (denominateur > 0) points.somme { it.poids * it.x * it.y } / denominateur else 0.0
        base = 0.0
        if (k <= 0) return vide
    }

    val sce = points.somme { it.poids * carre(it.y - (base + k * it.x)) }
    val sct = points.somme { it.poids * carre(it.y - yMoy) }
    val r2 = if (sct > 0) 1 - sce / sct else 0.0

    return ModeleThermique(
        base = base,
        k = k,
        r2 = r2,
        nbPeriodes = points.size,
        fiable = r2 >= 0.5,
        djReference = if (djs.isNotEmpty()) djs.values.sum() / djs.size * 365 else 2000.0,
    )
}

private fun carre(x: Double) = x * x

/** Somme dans l'ordre de la liste, comme le `sum()` de Python. */
internal inline fun <T> Iterable<T>.somme(selecteur: (T) -> Double): Double {
    var total = 0.0
    for (element in this) total += selecteur(element)
    return total
}

// ---------------------------------------------------------------------------
// Ventilation journalière
// ---------------------------------------------------------------------------

/** Consommation ventilée au jour le jour. */
class SerieConso(
    /** Jours dans l'ordre où la ventilation les a remplis. */
    val jours: LinkedHashMap<LocalDate, Double> = LinkedHashMap(),
    val modele: ModeleThermique = ModeleThermique(),
    /** Degrés-jours restreints à la période couverte par les relevés. */
    val djs: Map<LocalDate, Double> = emptyMap(),
    val lacunes: MutableList<Pair<LocalDate, LocalDate>> = mutableListOf(),
) {
    val total: Double get() = jours.values.sum()

    val premierJour: LocalDate? get() = jours.keys.minOrNull()

    val dernierJour: LocalDate? get() = jours.keys.maxOrNull()

    fun entre(debut: LocalDate, fin: LocalDate): Double {
        var total = 0.0
        for ((jour, valeur) in jours) if (jour >= debut && jour <= fin) total += valeur
        return total
    }

    fun nbJoursCouverts(debut: LocalDate, fin: LocalDate): Int =
        jours.keys.count { it >= debut && it <= fin }

    fun parAnnee(): Map<Int, Double> {
        val cumuls = HashMap<Int, Double>()
        for ((jour, valeur) in jours) cumuls[jour.year] = (cumuls[jour.year] ?: 0.0) + valeur
        return cumuls.toSortedMap()
    }

    fun parMois(): Map<Pair<Int, Int>, Double> {
        val cumuls = HashMap<Pair<Int, Int>, Double>()
        for ((jour, valeur) in jours) {
            val cle = jour.year to jour.monthValue
            cumuls[cle] = (cumuls[cle] ?: 0.0) + valeur
        }
        return cumuls.toSortedMap(compareBy({ it.first }, { it.second }))
    }

    fun joursCouvertsParAnnee(): Map<Int, Int> {
        val cumuls = HashMap<Int, Int>()
        for (jour in jours.keys) cumuls[jour.year] = (cumuls[jour.year] ?: 0) + 1
        return cumuls.toSortedMap()
    }

    /** Courbe cumulée du 1er janvier au dernier jour connu de l'année. */
    fun cumulAnnuel(annee: Int): List<Pair<LocalDate, Double>> {
        var cumul = 0.0
        return jours.keys.filter { it.year == annee }.sorted().map { jour ->
            cumul += jours.getValue(jour)
            jour to cumul
        }
    }

    /** DJ cumulés sur les seuls jours réellement couverts par des relevés. */
    fun djParAnnee(): Map<Int, Double> {
        val cumuls = HashMap<Int, Double>()
        for (jour in jours.keys) {
            val dj = djs[jour] ?: continue
            cumuls[jour.year] = (cumuls[jour.year] ?: 0.0) + dj
        }
        return cumuls.toSortedMap()
    }
}

/**
 * Répartit les écarts d'index sur les jours qu'ils couvrent.
 *
 * [djsStation] peut couvrir bien plus large : seule la période des relevés
 * est retenue, comme côté serveur.
 */
fun ventiler(
    compteurs: List<Compteur>,
    djsStation: Map<LocalDate, Double>,
    modele: ModeleThermique? = null,
    jusqua: LocalDate? = null,
): SerieConso {
    val liste = periodes(compteurs, jusqua)
    if (liste.isEmpty()) return SerieConso()

    val debut = liste.minOf { it.debut }.plusDays(1)
    val fin = liste.maxOf { it.fin }
    val djs = LinkedHashMap<LocalDate, Double>()
    for (jour in djsStation.keys.sorted()) {
        if (jour >= debut && jour <= fin) djs[jour] = djsStation.getValue(jour)
    }

    val modeleRetenu = modele ?: if (compteurs.all { it.energie.thermosensible }) {
        ajusterModele(liste, djs)
    } else {
        ModeleThermique(nbPeriodes = liste.size)
    }

    val serie = SerieConso(modele = modeleRetenu, djs = djs)
    for (periode in liste) {
        val jours = periode.jours.toList()
        if (jours.isEmpty()) continue

        var poids: List<Double>? = null
        if (modeleRetenu.fiable && jours.all { it in djs }) {
            val brut = jours.map { modeleRetenu.attendu(djs.getValue(it)) }
            val somme = brut.somme { it }
            if (somme > 0) poids = brut.map { it / somme }
        }
        val parts = poids ?: List(jours.size) { 1.0 / jours.size }

        for ((jour, part) in jours.zip(parts)) {
            serie.jours[jour] = (serie.jours[jour] ?: 0.0) + periode.volume * part
        }
    }

    // Trous de couverture (changement de compteur, relevés interrompus).
    val tries = liste.sortedBy { it.fin }
    for ((precedente, suivante) in tries.zipWithNext()) {
        if (suivante.debut > precedente.fin) {
            serie.lacunes += precedente.fin.plusDays(1) to suivante.debut
        }
    }
    return serie
}

// ---------------------------------------------------------------------------
// Lignes de compteurs
// ---------------------------------------------------------------------------

/** Une énergie d'une maison, tous compteurs successifs confondus. */
class Ligne(
    val energie: Energie,
    val plage: Plage,
    val compteurs: List<Compteur>,
    val serie: SerieConso,
    /** Date au-delà de laquelle les relevés ont été masqués, le cas échéant. */
    val jusqua: LocalDate? = null,
) {
    val thermosensible: Boolean get() = energie.thermosensible

    val unite: String get() = compteurs.firstOrNull()?.unite.orEmpty()

    val libelle: String
        get() = if (plage == Plage.UNIQUE) energie.libelle else "${energie.libelle} – ${plage.libelle}"

    /** Dates des relevés marqués « annuels », dans l'ordre. */
    val relevesAnnuels: List<LocalDate>
        get() = compteurs.flatMap { c -> c.releves.filter { it.annuel }.map { it.date } }
            .distinct().sorted()
}

/**
 * Regroupe les compteurs d'une maison en lignes : une par énergie et plage,
 * eau puis gaz puis électricité.
 */
fun lignes(
    compteurs: List<Compteur>,
    djsStation: Map<LocalDate, Double>,
    jusqua: LocalDate? = null,
): List<Ligne> {
    val tries = compteurs.sortedWith(
        compareBy<Compteur>({ it.energie.code }, { it.plage.code })
            // Comme SQLite : un compteur sans date de pose passe en premier.
            .thenBy(nullsFirst()) { it.datePose }
            .thenBy { it.id }
    )
    val groupes = LinkedHashMap<Pair<Energie, Plage>, MutableList<Compteur>>()
    for (c in tries) groupes.getOrPut(c.energie to c.plage) { mutableListOf() } += c

    return groupes.entries
        .sortedWith(compareBy({ it.key.first.ordre }, { it.key.second.code }))
        .map { (cle, liste) ->
            Ligne(cle.first, cle.second, liste, ventiler(liste, djsStation, jusqua = jusqua), jusqua)
        }
}

// ---------------------------------------------------------------------------
// Normalisation climatique
// ---------------------------------------------------------------------------

data class AnneeComparee(
    val annee: Int,
    val consommation: Double,
    val joursCouverts: Int,
    val djReel: Double,
    val djNormal: Double,
    val consommationNormalisee: Double?,
    val complete: Boolean,
) {
    val couverturePct: Double get() = 100.0 * joursCouverts / joursDansAnnee(annee)

    /** Écart entre la rigueur de l'année et la normale climatique. */
    val ecartClimatiquePct: Double?
        get() = if (djNormal <= 0) null else 100.0 * (djReel - djNormal) / djNormal
}

/**
 * Consommation annuelle, brute puis corrigée du climat par
 * `+ k × (DJ_normal − DJ_réel)`, si le modèle thermique est fiable.
 */
fun comparerAnnees(
    ligne: Ligne,
    normales: Map<Int, Double>,
    seuilCompletude: Double = 90.0,
): List<AnneeComparee> {
    val serie = ligne.serie
    if (serie.jours.isEmpty()) return emptyList()

    val joursAnnuels = serie.joursCouvertsParAnnee()
    val djAnnuels = serie.djParAnnee()

    return serie.parAnnee().map { (annee, consommation) ->
        val joursCouverts = joursAnnuels[annee] ?: 0
        val complete = joursCouverts >= joursDansAnnee(annee) * seuilCompletude / 100

        // La normale est restreinte aux jours réellement couverts, sans quoi on
        // comparerait une année partielle à une année pleine.
        var djNormal = 0.0
        for (jour in serie.jours.keys) {
            if (jour.year == annee) djNormal += normales[cleCalendaire(jour)] ?: 0.0
        }
        val djReel = djAnnuels[annee] ?: 0.0
        val normalisee = if (serie.modele.fiable && djNormal > 0 && djReel > 0) {
            max(0.0, consommation + serie.modele.k * (djNormal - djReel))
        } else {
            null
        }
        AnneeComparee(annee, consommation, joursCouverts, djReel, djNormal, normalisee, complete)
    }
}

// ---------------------------------------------------------------------------
// Qualité des données
// ---------------------------------------------------------------------------

enum class Niveau { ALERTE, INFO }

/**
 * Point qui mérite un coup d'œil avant d'interpréter les courbes.
 *
 * Le moteur ne rédige rien : il décrit, et l'interface met en mots dans la
 * langue choisie.
 */
sealed interface Anomalie {
    val niveau: Niveau
    val debut: LocalDate
    val fin: LocalDate

    /** Aucun relevé exploitable : remplacement de compteur ou suivi interrompu. */
    data class Lacune(override val debut: LocalDate, override val fin: LocalDate) : Anomalie {
        override val niveau get() = Niveau.INFO
        val nbJours: Int get() = ecartJours(debut, fin) + 1
    }

    /** Index inchangé longtemps : relevé probablement manquant. */
    data class IndexFige(
        override val debut: LocalDate,
        override val fin: LocalDate,
        val nbJours: Int,
        val unite: String,
    ) : Anomalie {
        override val niveau get() = Niveau.ALERTE
    }

    /**
     * Consommation anormalement forte : [rapport] fois ce que le climat
     * laissait attendre ([faceAuClimat]) ou la consommation habituelle.
     */
    data class Surconsommation(
        override val debut: LocalDate,
        override val fin: LocalDate,
        val volume: Double,
        val unite: String,
        val nbJours: Int,
        val rapport: Double,
        val faceAuClimat: Boolean,
    ) : Anomalie {
        override val niveau get() = Niveau.ALERTE
    }
}

/** Un index figé plus longtemps signale presque toujours un relevé oublié. */
const val JOURS_SANS_CONSO_SUSPECT = 60

/**
 * Seuil face au modèle thermique : celui-ci absorbe déjà la saison, donc un
 * dépassement y est plus significatif et plus rare.
 */
const val FACTEUR_SURCONSOMMATION_MODELE = 2.5

/** Face à une simple médiane, un doublement suffit : signature typique d'une fuite. */
const val FACTEUR_SURCONSOMMATION_MEDIANE = 2.0

/** Repère les points qui méritent un coup d'œil avant d'interpréter les courbes. */
fun detecterAnomalies(ligne: Ligne): List<Anomalie> {
    val liste = periodes(ligne.compteurs, ligne.jusqua)
    if (liste.isEmpty()) return emptyList()

    val anomalies = mutableListOf<Anomalie>()
    for ((debut, fin) in ligne.serie.lacunes) anomalies += Anomalie.Lacune(debut, fin)

    for (p in liste) {
        if (p.volume == 0.0 && p.nbJours >= JOURS_SANS_CONSO_SUSPECT) {
            anomalies += Anomalie.IndexFige(p.debut, p.fin, p.nbJours, p.compteur.unite)
        }
    }

    val modele = ligne.serie.modele
    val djs = ligne.serie.djs
    if (modele.fiable) {
        // Sur une énergie de chauffage, la référence est ce que le modèle
        // prédit pour ces jours-là : sinon l'hiver passerait pour une anomalie.
        for (p in liste) {
            val jours = p.jours.toList()
            if (p.nbJours < 7 || !jours.all { it in djs }) continue
            val attendu = jours.somme { modele.attendu(djs.getValue(it)) }
            if (attendu <= 0) continue
            val rapport = p.volume / attendu
            if (rapport >= FACTEUR_SURCONSOMMATION_MODELE) {
                anomalies += Anomalie.Surconsommation(
                    p.debut, p.fin, p.volume, p.compteur.unite, p.nbJours, rapport,
                    faceAuClimat = true,
                )
            }
        }
    } else {
        // Référence : consommation journalière médiane, robuste aux extrêmes.
        val journalieres = liste.filter { it.volume > 0 && it.nbJours > 0 }
            .map { it.volumeJournalier }.sorted()
        if (journalieres.size >= 5) {
            val mediane = journalieres[journalieres.size / 2]
            for (p in liste) {
                if (p.nbJours < 7 || mediane <= 0) continue
                val rapport = p.volumeJournalier / mediane
                if (rapport >= FACTEUR_SURCONSOMMATION_MEDIANE) {
                    anomalies += Anomalie.Surconsommation(
                        p.debut, p.fin, p.volume, p.compteur.unite, p.nbJours, rapport,
                        faceAuClimat = false,
                    )
                }
            }
        }
    }
    return anomalies.sortedBy { it.debut }
}

internal fun absJours(a: LocalDate, b: LocalDate): Int = abs(ecartJours(a, b))
