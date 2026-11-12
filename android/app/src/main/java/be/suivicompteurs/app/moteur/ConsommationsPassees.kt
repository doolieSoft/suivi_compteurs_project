package be.suivicompteurs.app.moteur

import java.time.LocalDate
import java.time.YearMonth
import java.util.TreeMap

/*
 * Consommations passées d'un compteur saisi en consommations : les kWh de
 * chaque mois, ou de l'année, repris des relevés d'un service de lecture.
 *
 * Un relevé daté du jour J porte la consommation de la période qui court
 * depuis le relevé précédent (exclu) jusqu'à J (inclus). Le premier relevé
 * ouvre la série : sa consommation ne compte pas. Les périodes doivent se
 * suivre sans trou, sinon l'index cumulé y verrait une consommation nulle.
 */

/** Consommation [volume] de la période qui suit [debut] et s'achève à [fin] inclus. */
data class ConsommationSaisie(val debut: LocalDate, val fin: LocalDate, val volume: Double)

/** Le mois [mois] : du dernier jour du mois précédent au dernier jour du mois. */
fun periodeDuMois(mois: YearMonth, volume: Double) =
    ConsommationSaisie(mois.minusMonths(1).atEndOfMonth(), mois.atEndOfMonth(), volume)

/** L'année civile [annee], du 31 décembre précédent au 31 décembre. */
fun periodeDeLAnnee(annee: Int, volume: Double) =
    ConsommationSaisie(LocalDate.of(annee - 1, 12, 31), LocalDate.of(annee, 12, 31), volume)

/** Pourquoi une saisie ne peut pas s'intégrer aux relevés existants. */
class ConflitConsommation(
    val chevauchement: Boolean,
    /** La période en cause : celle qui chevauche, ou le trou laissé. */
    val debut: LocalDate,
    val fin: LocalDate,
) : Exception()

/**
 * Consommation déjà connue pour la période [saisie] exactement, s'il en est
 * une : sert à préremplir le formulaire.
 */
fun consommationConnue(existants: Map<LocalDate, Double?>, saisie: ConsommationSaisie): Double? {
    val dates = TreeMap(existants)
    if (saisie.fin !in dates || dates.lowerKey(saisie.fin) != saisie.debut) return null
    return dates[saisie.fin]
}

/**
 * Relevés après intégration de [saisies] à [existants] (consommation par date
 * de relevé). Une saisie qui recouvre exactement une période connue la
 * remplace. Refuse ce qui chevaucherait une période connue sans la recouvrir
 * exactement, et ce qui laisserait un trou entre les périodes.
 */
fun integrerConsommations(
    existants: Map<LocalDate, Double?>,
    saisies: List<ConsommationSaisie>,
): Map<LocalDate, Double?> {
    val dates = TreeMap(existants)
    // Relevés qui ouvrent une série : le premier, et les débuts ajoutés ici.
    val ouvertures = mutableSetOf<LocalDate>()
    if (dates.isNotEmpty()) ouvertures += dates.firstKey()

    for (s in saisies.sortedBy { it.debut }) {
        require(s.debut < s.fin) { "période vide" }
        // Un relevé à l'intérieur : la saisie couvrirait plusieurs périodes connues.
        dates.subMap(s.debut, false, s.fin, false).keys.firstOrNull()?.let {
            throw ConflitConsommation(true, dates.lowerKey(it) ?: s.debut, it)
        }
        if (s.debut !in dates) {
            // Au milieu d'une période connue, le début la couperait en deux.
            val suivant = dates.higherKey(s.debut)
            if (suivant != null && suivant !in ouvertures && dates.lowerKey(s.debut) != null) {
                throw ConflitConsommation(true, dates.lowerKey(s.debut)!!, suivant)
            }
            dates[s.debut] = 0.0
            ouvertures += s.debut
        }
        if (s.fin !in dates) {
            val suivant = dates.higherKey(s.fin)
            if (suivant != null && suivant !in ouvertures) {
                throw ConflitConsommation(true, s.debut, suivant)
            }
        }
        dates[s.fin] = s.volume
        ouvertures -= s.fin
    }

    // Seul le premier relevé peut encore ouvrir une série : un autre laisserait
    // un trou juste avant lui.
    if (dates.isEmpty()) return dates
    (ouvertures - dates.firstKey()).minOrNull()?.let { trou ->
        throw ConflitConsommation(false, dates.lowerKey(trou)!!, trou)
    }
    return dates
}
