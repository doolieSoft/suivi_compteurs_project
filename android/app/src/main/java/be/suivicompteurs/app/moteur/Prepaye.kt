package be.suivicompteurs.app.moteur

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.round

/*
 * Compteurs prépayés : on achète des kWh d'avance, le compteur les ajoute à
 * son solde et le décompte au fil de la consommation. Une cuve de mazout à
 * jauge fonctionne de même : le niveau descend, les livraisons le remontent.
 *
 * Comme pour les répartiteurs, le moteur ne voit qu'un compteur ordinaire :
 * chaque relevé porte un index cumulé, la somme des consommations depuis le
 * premier relevé, qui ne recule jamais.
 */

data class SoldeReleve(val date: LocalDate, val solde: Double)

/** [apresReleve] : acheté après le relevé de ce jour-là, s'il y en a un. */
data class AchatEnergie(
    val date: LocalDate,
    val quantite: Double,
    val apresReleve: Boolean = false,
    /** Prix payé, taxes comprises. Absent, l'achat ne dit rien du coût. */
    val montant: Double? = null,
)

/** Le jour d'un relevé, un achat tombe avant ou après lui. */
private fun rangAchat(achat: AchatEnergie) = achat.date to if (achat.apresReleve) 2 else 0
private fun rangReleve(date: LocalDate) = date to 1

private val ordre = compareBy<Pair<LocalDate, Int>>({ it.first }, { it.second })

/** kWh achetés entre deux relevés ; [depuis] nul veut dire depuis toujours. */
fun achatsEntre(achats: List<AchatEnergie>, depuis: LocalDate?, jusqua: LocalDate?): Double =
    achats.filter { a ->
        (depuis == null || ordre.compare(rangAchat(a), rangReleve(depuis)) > 0) &&
            (jusqua == null || ordre.compare(rangAchat(a), rangReleve(jusqua)) < 0)
    }.sumOf { it.quantite }

/** Un solde qui monte plus que les achats trahit un achat oublié : rien n'a été consommé. */
fun consommationPrepayee(soldePrecedent: Double, achats: Double, soldeActuel: Double): Double =
    max(0.0, soldePrecedent + achats - soldeActuel)

/** Une jauge de cuve se lit à quelques pour cent près. */
const val TOLERANCE_JAUGE = 0.05

/**
 * Ce que le solde lu dépasse de l'attendu, s'il le dépasse de plus que
 * [tolerance] (part de l'attendu) : un achat a sans doute été oublié.
 */
fun soldeExcedentaire(
    soldePrecedent: Double,
    achats: Double,
    soldeActuel: Double,
    tolerance: Double = 0.0,
): Double? {
    val attendu = soldePrecedent + achats
    return (soldeActuel - attendu).takeIf { it > tolerance * attendu }
}

/** Index cumulé de chaque date relevée. Le premier relevé vaut zéro. */
fun indexPrepayes(soldes: List<SoldeReleve>, achats: List<AchatEnergie>): Map<LocalDate, Double> {
    val resultat = LinkedHashMap<LocalDate, Double>()
    var cumul = 0.0
    var precedent: SoldeReleve? = null
    for (releve in soldes.sortedBy { it.date }) {
        precedent?.let {
            cumul += consommationPrepayee(it.solde, achatsEntre(achats, it.date, releve.date), releve.solde)
        }
        // Mêmes arrondis qu'ailleurs : pas de 249,99999999 sur des décimales.
        resultat[releve.date] = round(cumul * 1e6) / 1e6
        precedent = releve
    }
    return resultat
}

/** Dernier solde lu, augmenté de ce qui a été acheté depuis. */
fun soldeEstime(soldes: List<SoldeReleve>, achats: List<AchatEnergie>): Double? {
    val dernier = soldes.maxByOrNull { it.date } ?: return null
    return dernier.solde + achatsEntre(achats, dernier.date, null)
}

/** Le rythme se mesure sur ces derniers jours de relevés… */
const val JOURS_RYTHME_PREPAYE = 60L

/** … et sur au moins cette durée : deux relevés rapprochés ne disent rien. */
const val JOURS_RYTHME_MINIMUM = 7L

/** Jour où le solde devrait s'épuiser, au rythme des derniers relevés. */
fun epuisementEstime(soldes: List<SoldeReleve>, achats: List<AchatEnergie>): LocalDate? {
    val tries = soldes.sortedBy { it.date }
    val dernier = tries.lastOrNull() ?: return null
    val anterieurs = tries.dropLast(1)
    val depart = anterieurs.firstOrNull { it.date >= dernier.date.minusDays(JOURS_RYTHME_PREPAYE) }
        ?: anterieurs.lastOrNull() ?: return null
    val jours = ChronoUnit.DAYS.between(depart.date, dernier.date)
    if (jours < JOURS_RYTHME_MINIMUM) return null
    val consomme = indexPrepayes(tries.filter { it.date >= depart.date }, achats).getValue(dernier.date)
    if (consomme <= 0) return null
    val solde = soldeEstime(soldes, achats) ?: return null
    return dernier.date.plusDays(max(0.0, solde / (consomme / jours)).toLong())
}

/**
 * Chaque achat payé devient un tarif : ce qu'il a coûté par kWh, jusqu'à
 * l'achat suivant. Les écrans de coût n'ont ainsi rien à savoir du prépayé.
 * Un kWh d'un ancien achat est donc compté au prix du dernier, ce qui reste
 * proche de la réalité tant que les achats sont réguliers.
 */
fun tarifsDesAchats(achats: List<AchatEnergie>, energie: Energie): List<Tarif> =
    achats.filter { it.montant != null && it.quantite > 0 }.map {
        Tarif(energie, it.date, prixUnitaire = it.montant!! / it.quantite)
    }
