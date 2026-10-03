package be.suivicompteurs.app.moteur

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.cos

/**
 * Jeux de données synthétiques, calqués sur `suivi/tests/fabrique.py`.
 *
 * Climat déterministe (sinusoïde annuelle) : les tests tournent hors ligne et
 * donnent le même résultat à chaque exécution.
 */
object Fabrique {

    /** Température moyenne journalière : minimum mi-janvier, maximum mi-juillet. */
    fun temperature(jour: LocalDate, moyenne: Double = 10.5, amplitude: Double = 8.0): Double =
        moyenne - amplitude * cos(2 * PI * (jour.dayOfYear - 15) / 365.0)

    /** Degrés-jours de la période, arrondis comme en base (3 décimales). */
    fun climat(debut: LocalDate, fin: LocalDate, base: Double = 16.5): LinkedHashMap<LocalDate, Double> {
        val table = LinkedHashMap<LocalDate, Double>()
        for (jour in jours(debut, fin)) {
            table[jour] = arrondi(DegresJours.calculer(temperature(jour), base), 3)
        }
        return table
    }

    fun datesTousLes(debut: LocalDate, fin: LocalDate, pas: Long): List<LocalDate> =
        generateSequence(debut) { it.plusDays(pas) }.takeWhile { it <= fin }.toList()

    /**
     * Relevés d'index cohérents avec une consommation journalière connue.
     *
     * C'est l'inverse de ce que fait l'application : on part de la vérité
     * journalière pour produire des index, afin de vérifier que la ventilation
     * la retrouve.
     */
    fun releves(
        conso: Map<LocalDate, Double>,
        dates: List<LocalDate>,
        indexInitial: Double = 1000.0,
        annuels: Set<LocalDate> = emptySet(),
    ): List<Releve> {
        val triees = dates.sorted()
        var index = indexInitial
        var precedent = triees.first()
        val resultat = mutableListOf(Releve(precedent, arrondi(index, 3), precedent in annuels))
        for (jour in triees.drop(1)) {
            var cumul = 0.0
            for ((j, v) in conso) if (j > precedent && j <= jour) cumul += v
            index += cumul
            resultat += Releve(jour, arrondi(index, 3), jour in annuels)
            precedent = jour
        }
        return resultat
    }

    fun compteur(
        energie: Energie,
        releves: List<Releve>,
        id: Long = 1,
        plage: Plage = Plage.UNIQUE,
        unite: String = "m³",
    ) = Compteur(id = id, energie = energie, plage = plage, unite = unite, releves = releves)

    fun arrondi(valeur: Double, decimales: Int): Double =
        BigDecimal(valeur).setScale(decimales, RoundingMode.HALF_EVEN).toDouble()
}

fun date(texte: String): LocalDate = LocalDate.parse(texte)
