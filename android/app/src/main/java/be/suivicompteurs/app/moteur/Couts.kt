package be.suivicompteurs.app.moteur

import java.time.LocalDate
import java.time.YearMonth

/*
 * Valorisation des consommations en euros.
 *
 * Le coût d'une journée est `consommation × prix unitaire en vigueur`, auquel
 * s'ajoute l'abonnement au prorata des jours du mois. Les tarifs propres à la
 * maison priment sur les tarifs génériques.
 *
 * Portage de `suivi/services/couts.py`.
 */

data class CoutAnnuel(
    val annee: Int,
    val consommation: Double,
    val coutVariable: Double,
    val coutAbonnement: Double,
    val joursTarifes: Int,
    val joursCouverts: Int,
) {
    val total: Double get() = coutVariable + coutAbonnement

    val prixMoyen: Double? get() = if (consommation <= 0) null else coutVariable / consommation

    /** Vrai si tous les jours consommés sont couverts par un tarif. */
    val complet: Boolean get() = joursCouverts > 0 && joursTarifes >= joursCouverts
}

/** Tarifs de l'énergie, dans l'ordre où le serveur les lit. */
private fun applicables(tarifs: List<Tarif>, energie: Energie): List<Tarif> =
    tarifs.filter { it.energie == energie }
        .sortedWith(compareBy({ it.dateDebut }, { it.propreALaMaison }))

private fun tarifDuJour(tarifs: List<Tarif>, jour: LocalDate): Tarif? =
    // Un tarif propre à la maison l'emporte ; à égalité, le plus récent gagne.
    tarifs.filter { it.couvre(jour) }
        .sortedWith(compareBy({ it.propreALaMaison }, { it.dateDebut }))
        .lastOrNull()

/** Coût annuel de la ligne, part variable et abonnement séparés. */
fun coutsParAnnee(ligne: Ligne, tarifs: List<Tarif>): List<CoutAnnuel> {
    val serie = ligne.serie
    if (serie.jours.isEmpty()) return emptyList()
    val retenus = applicables(tarifs, ligne.energie)
    if (retenus.isEmpty()) return emptyList()

    class Cumul {
        var consommation = 0.0
        var variable = 0.0
        var abonnement = 0.0
        var joursTarifes = 0
        var joursCouverts = 0
    }

    val cumuls = sortedMapOf<Int, Cumul>()
    // Un abonnement mensuel est réparti sur les jours du mois effectivement suivis.
    val joursParMois = LinkedHashMap<YearMonth, MutableList<LocalDate>>()

    for (jour in serie.jours.keys.sorted()) {
        val valeur = serie.jours.getValue(jour)
        val tarif = tarifDuJour(retenus, jour)
        val cellule = cumuls.getOrPut(jour.year) { Cumul() }
        cellule.consommation += valeur
        cellule.joursCouverts += 1
        if (tarif == null) continue
        cellule.joursTarifes += 1
        cellule.variable += valeur * tarif.prixUnitaire
        joursParMois.getOrPut(YearMonth.from(jour)) { mutableListOf() } += jour
    }

    for ((mois, jours) in joursParMois) {
        val tarif = tarifDuJour(retenus, jours.first()) ?: continue
        val part = jours.size.toDouble() / mois.lengthOfMonth()
        cumuls.getValue(mois.year).abonnement += tarif.abonnementMensuel * part
    }

    return cumuls.map { (annee, c) ->
        CoutAnnuel(annee, c.consommation, c.variable, c.abonnement, c.joursTarifes, c.joursCouverts)
    }
}

/** Convertit une quantité en euros au tarif en vigueur à une date donnée. */
fun valoriser(ligne: Ligne, tarifs: List<Tarif>, quantite: Double, jour: LocalDate): Double? {
    val tarif = tarifDuJour(applicables(tarifs, ligne.energie), jour) ?: return null
    return quantite * tarif.prixUnitaire
}
