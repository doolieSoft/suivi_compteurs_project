package be.suivicompteurs.app.moteur

import java.time.LocalDate
import kotlin.math.max

/**
 * Degrés-jours : un degré-jour vaut `max(0, base − température moyenne)`.
 * Sommé sur une période, il mesure la rigueur du climat et rend deux années
 * comparables.
 *
 * Les « normales » sont indexées par jour calendaire (voir [cleCalendaire]).
 */
object DegresJours {

    /** Convention belge : DJ 16,5/16,5. */
    const val BASE_BELGE = 16.5

    /** Années prises pour établir la normale climatique. */
    const val ANNEES_NORMALE = 10

    fun calculer(temperatureMoyenne: Double, base: Double = BASE_BELGE): Double =
        max(0.0, base - temperatureMoyenne)

    /**
     * « Normale climatique » : DJ moyen de chaque jour calendaire sur les
     * [annees] précédant [jusqua].
     *
     * Sert de référence pour normaliser les années entre elles et pour
     * projeter la consommation des jours restants de l'année en cours.
     */
    fun normales(
        djs: Map<LocalDate, Double>,
        jusqua: LocalDate,
        annees: Int = ANNEES_NORMALE,
    ): Map<Int, Double> {
        val depuis = decaler(jusqua, -annees)
        val cumuls = LinkedHashMap<Int, MutableList<Double>>()
        for (jour in djs.keys.sorted()) {
            if (jour < depuis || jour > jusqua) continue
            cumuls.getOrPut(cleCalendaire(jour)) { mutableListOf() }.add(djs.getValue(jour))
        }
        val resultat = LinkedHashMap<Int, Double>()
        for ((cle, valeurs) in cumuls) {
            if (valeurs.isNotEmpty()) resultat[cle] = valeurs.sum() / valeurs.size
        }
        return resultat
    }

    /** Applique la normale climatique à une plage de dates réelle. */
    fun normalSurPeriode(normales: Map<Int, Double>, debut: LocalDate, fin: LocalDate): Double {
        if (normales.isEmpty() || debut > fin) return 0.0
        var total = 0.0
        for (jour in jours(debut, fin)) total += normales[cleCalendaire(jour)] ?: 0.0
        return total
    }

    /** Total annuel de la normale climatique (année de 365 jours). */
    fun normalAnnuel(normales: Map<Int, Double>): Double = normales.values.sum()

    /** Degrés-jours cumulés par année civile. */
    fun annuels(djs: Map<LocalDate, Double>): Map<Int, Double> {
        val cumuls = sortedMapOf<Int, Double>()
        for (jour in djs.keys.sorted()) {
            cumuls[jour.year] = (cumuls[jour.year] ?: 0.0) + djs.getValue(jour)
        }
        return cumuls
    }
}
