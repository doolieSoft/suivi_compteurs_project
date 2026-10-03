package be.suivicompteurs.app.moteur

import java.time.LocalDate
import java.time.Year
import java.time.temporal.ChronoUnit

/** Jours de [debut] à [fin], bornes incluses. */
internal fun jours(debut: LocalDate, fin: LocalDate): Sequence<LocalDate> =
    generateSequence(debut) { it.plusDays(1) }.takeWhile { !it.isAfter(fin) }

internal fun ecartJours(debut: LocalDate, fin: LocalDate): Int =
    ChronoUnit.DAYS.between(debut, fin).toInt()

internal fun joursDansAnnee(annee: Int): Int = if (Year.isLeap(annee.toLong())) 366 else 365

/** Même jour, [annees] plus tard (ou plus tôt) ; le 29 février devient le 28. */
internal fun decaler(jour: LocalDate, annees: Int): LocalDate = jour.plusYears(annees.toLong())

/**
 * Jour calendaire (mois, jour) codé en entier `mois × 100 + jour`.
 *
 * Le 29 février est rattaché au 28 pour éviter un trou les années communes.
 */
internal fun cleCalendaire(jour: LocalDate): Int {
    val quantieme = if (jour.monthValue == 2 && jour.dayOfMonth == 29) 28 else jour.dayOfMonth
    return jour.monthValue * 100 + quantieme
}
