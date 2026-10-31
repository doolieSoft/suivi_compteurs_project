package be.suivicompteurs.app.moteur

import java.time.LocalDate
import kotlin.math.round

/*
 * Répartiteurs de frais de chauffage : un petit boîtier par radiateur, qui
 * compte des unités relatives. Une unité ne vaut quelque chose qu'une fois
 * multipliée par le coefficient du radiateur, et chaque boîtier peut repartir
 * de zéro à sa date anniversaire.
 *
 * Le moteur ne voit pourtant qu'un compteur ordinaire : chaque relevé porte
 * un index cumulé, la somme des écarts pondérés depuis le premier relevé.
 * Cet index ne recule jamais, si bien que courbes, prévisions et correction
 * climatique s'appliquent sans rien savoir des radiateurs.
 */

data class Radiateur(val id: Long, val coefficient: Double)

/** Ce qu'affiche le boîtier d'un radiateur à une date. */
data class ChiffreRadiateur(val radiateurId: Long, val date: LocalDate, val unites: Double)

/**
 * Unités pondérées entre deux relevés. Un chiffre plus bas signale une remise
 * à zéro : ce qui a été compté depuis, c'est le chiffre lui-même. Un radiateur
 * sans chiffre précédent (ajouté depuis) sert de point de départ et ne compte
 * pour rien.
 */
fun ecartPondere(
    radiateurs: List<Radiateur>,
    precedents: Map<Long, Double>,
    actuels: Map<Long, Double>,
): Double = radiateurs.sumOf { r ->
    val actuel = actuels[r.id]
    val precedent = precedents[r.id]
    if (actuel == null || precedent == null) 0.0
    else r.coefficient * if (actuel >= precedent) actuel - precedent else actuel
}

/** Radiateurs dont le chiffre a baissé : remise à zéro, ou faute de frappe. */
fun radiateursEnBaisse(precedents: Map<Long, Double>, actuels: Map<Long, Double>): Set<Long> =
    actuels.filter { (id, actuel) -> precedents[id]?.let { actuel < it } == true }.keys

/**
 * Index cumulé de chaque date relevée. Le premier relevé vaut zéro. Le dernier
 * chiffre connu d'un radiateur reste sa référence, même s'il manque à un relevé.
 */
fun indexCumules(
    radiateurs: List<Radiateur>,
    chiffres: List<ChiffreRadiateur>,
): Map<LocalDate, Double> {
    val derniers = HashMap<Long, Double>()
    val resultat = LinkedHashMap<LocalDate, Double>()
    var cumul = 0.0
    for ((date, duJour) in chiffres.groupBy { it.date }.toSortedMap()) {
        val actuels = duJour.associate { it.radiateurId to it.unites }
        cumul += ecartPondere(radiateurs, derniers, actuels)
        derniers += actuels
        // Les coefficients décimaux laisseraient sinon des 249,99999999.
        resultat[date] = round(cumul * 1e6) / 1e6
    }
    return resultat
}
