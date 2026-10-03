package be.suivicompteurs.app.moteur

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Transposition de `suivi/tests/test_consommation.py`.
 *
 * Le fil conducteur : on fabrique une consommation journalière connue, on en
 * déduit des relevés d'index, et on vérifie que le moteur la retrouve.
 */
private const val BASE_VRAIE = 0.5 // m³/jour indépendants du climat
private const val K_VRAI = 0.7 // m³ par degré-jour

class VentilationTest {
    private val djs = Fabrique.climat(date("2022-01-01"), date("2024-12-31"))
    private val conso = djs.mapValues { BASE_VRAIE + K_VRAI * it.value }
    // Relevés espacés de 40 jours : assez irréguliers pour que la ventilation
    // ait un vrai travail à faire.
    private val dates = Fabrique.datesTousLes(date("2022-01-01"), date("2024-12-31"), 40)
    private val compteur = Fabrique.compteur(Energie.GAZ, Fabrique.releves(conso, dates))
    private val serie = ventiler(listOf(compteur), djs)

    @Test
    fun `le total ventile egale la somme des ecarts d'index`() {
        val attendu = compteur.releves.last().index - compteur.releves.first().index
        assertEquals(attendu, serie.total, 1e-4)
    }

    @Test
    fun `chaque periode conserve son volume`() {
        for (periode in periodes(listOf(compteur))) {
            val ventile = periode.jours.sumOf { serie.jours.getValue(it) }
            assertEquals(periode.volume, ventile, 1e-5)
        }
    }

    @Test
    fun `aucun jour hors des periodes couvertes`() {
        assertEquals(dates.first().plusDays(1), serie.premierJour)
        assertEquals(dates.last(), serie.dernierJour)
    }

    @Test
    fun `le modele retrouve les parametres qui ont genere les donnees`() {
        assertTrue(serie.modele.fiable)
        assertEquals(BASE_VRAIE, serie.modele.base, 0.05)
        assertEquals(K_VRAI, serie.modele.k, 0.005)
        assertTrue(serie.modele.r2 > 0.95)
    }

    @Test
    fun `la ventilation thermique restitue la saisonnalite`() {
        val janvier = serie.entre(date("2023-01-01"), date("2023-01-31"))
        val juillet = serie.entre(date("2023-07-01"), date("2023-07-31"))
        assertTrue(janvier > 3 * juillet)
        val vraiJanvier = conso.filterKeys { it >= date("2023-01-01") && it <= date("2023-01-31") }
            .values.sum()
        // Tolérance de 10 % : la ventilation lisse à l'intérieur des périodes.
        assertEquals(1.0, janvier / vraiJanvier, 0.10)
    }
}

class RemplacementDeCompteurTest {
    private val djs = Fabrique.climat(date("2023-01-01"), date("2023-12-31"))
    private val ancien = Fabrique.compteur(
        Energie.EAU,
        listOf("2023-01-01", "2023-03-01", "2023-05-01").mapIndexed { rang, j ->
            Releve(date(j), 800.0 + 20 * rang)
        },
        id = 1,
    )
    private val nouveau = Fabrique.compteur(
        Energie.EAU,
        listOf("2023-06-01", "2023-08-01", "2023-10-01").mapIndexed { rang, j ->
            Releve(date(j), 0.0 + 20 * rang)
        },
        id = 2,
    )

    @Test
    fun `aucune periode ne chevauche deux compteurs`() {
        for (p in periodes(listOf(ancien, nouveau))) {
            assertTrue(p.volume >= 0)
            assertTrue(p.volume < 100)
        }
    }

    @Test
    fun `le total ignore le saut d'index`() {
        // 40 m³ sur l'ancien compteur + 40 sur le nouveau, jamais -840.
        assertEquals(80.0, ventiler(listOf(ancien, nouveau), djs).total, 1e-3)
    }

    @Test
    fun `la periode a cheval est signalee comme lacune`() {
        val serie = ventiler(listOf(ancien, nouveau), djs)
        assertEquals(listOf(date("2023-05-02") to date("2023-06-01")), serie.lacunes)
    }
}

class NormalisationTest {
    private val djs = Fabrique.climat(date("2018-01-01"), date("2024-12-31"))
    private val ligne = lignes(
        listOf(
            Fabrique.compteur(
                Energie.GAZ,
                Fabrique.releves(
                    djs.mapValues { BASE_VRAIE + K_VRAI * it.value },
                    Fabrique.datesTousLes(date("2018-01-01"), date("2024-12-31"), 30),
                ),
            )
        ),
        djs,
    ).single()
    private val normales = DegresJours.normales(djs, date("2024-12-31"))

    @Test
    fun `les annees normalisees sont quasi identiques`() {
        val annees = comparerAnnees(ligne, normales).filter { it.complete }
        assertTrue(annees.size >= 5)
        val valeurs = annees.map { it.consommationNormalisee!! }
        val ecart = (valeurs.max() - valeurs.min()) / valeurs.average()
        assertTrue(ecart < 0.05)
    }

    @Test
    fun `une annee plus froide voit sa consommation revue a la baisse`() {
        val annee = comparerAnnees(ligne, normales).first { it.complete }
        val attendu = annee.consommation + ligne.serie.modele.k * (annee.djNormal - annee.djReel)
        assertEquals(maxOf(0.0, attendu), annee.consommationNormalisee!!, 1e-3)
    }
}

class ThermosensibiliteTest {
    private val djs = Fabrique.climat(date("2020-01-01"), date("2024-12-31"))
    // Consommation d'eau délibérément corrélée au froid.
    private val ligne = lignes(
        listOf(
            Fabrique.compteur(
                Energie.EAU,
                Fabrique.releves(
                    djs.mapValues { 0.2 + 0.05 * it.value },
                    Fabrique.datesTousLes(date("2020-01-01"), date("2024-12-31"), 30),
                ),
            )
        ),
        djs,
    ).single()

    @Test
    fun `le modele thermique n'est pas ajuste sur l'eau`() {
        assertFalse(ligne.serie.modele.fiable)
        assertEquals(0.0, ligne.serie.modele.k, 0.0)
    }

    @Test
    fun `aucune consommation normalisee n'est produite`() {
        for (annee in comparerAnnees(ligne, DegresJours.normales(djs, date("2024-12-31")))) {
            assertNull(annee.consommationNormalisee)
        }
    }
}

class AnomaliesTest {
    private val djs = Fabrique.climat(date("2023-01-01"), date("2024-12-31"))

    @Test
    fun `un index fige longtemps est signale`() {
        val compteur = Fabrique.compteur(
            Energie.EAU,
            listOf(Releve(date("2023-01-01"), 100.0), Releve(date("2023-06-01"), 100.0)),
        )
        val anomalies = detecterAnomalies(lignes(listOf(compteur), djs).single())
        assertTrue(anomalies.any { it is Anomalie.IndexFige && it.niveau == Niveau.ALERTE })
    }

    @Test
    fun `une surconsommation franche est signalee`() {
        var index = 100.0
        var jour = date("2023-01-01")
        val releves = mutableListOf(Releve(jour, index))
        for (semaine in 0 until 20) {
            jour = jour.plusDays(14)
            // Une fuite au milieu de la série : dix fois le débit ordinaire.
            index += if (semaine == 10) 140.0 else 14.0
            releves += Releve(jour, index)
        }
        val anomalies = detecterAnomalies(
            lignes(listOf(Fabrique.compteur(Energie.EAU, releves)), djs).single()
        )
        assertTrue(anomalies.any { it is Anomalie.Surconsommation && !it.faceAuClimat })
    }

    @Test
    fun `l'hiver n'est pas pris pour une anomalie`() {
        val compteur = Fabrique.compteur(
            Energie.GAZ,
            Fabrique.releves(
                djs.mapValues { BASE_VRAIE + K_VRAI * it.value },
                Fabrique.datesTousLes(date("2023-01-01"), date("2024-12-31"), 14),
            ),
        )
        val ligne = lignes(listOf(compteur), djs).single()
        assertTrue(ligne.serie.modele.fiable)
        assertEquals(emptyList<Anomalie>(), detecterAnomalies(ligne).filter { it.niveau == Niveau.ALERTE })
    }
}

class DegresJoursTest {
    @Test
    fun `la formule de base n'est jamais negative`() {
        assertEquals(14.0, DegresJours.calculer(2.5), 1e-9)
        assertEquals(0.0, DegresJours.calculer(16.5), 0.0)
        assertEquals(0.0, DegresJours.calculer(24.0), 0.0)
    }

    @Test
    fun `le cumul annuel et la normale sont coherents`() {
        val djs = Fabrique.climat(date("2020-01-01"), date("2024-12-31"))
        val annuels = DegresJours.annuels(djs)
        assertEquals(setOf(2020, 2021, 2022, 2023, 2024), annuels.keys)
        val normale = DegresJours.normalAnnuel(DegresJours.normales(djs, date("2024-12-31")))
        // Le climat de fabrique se répète : la normale doit coller aux années.
        for ((annee, total) in annuels) {
            if (annee == 2020) continue // bissextile : un jour de plus
            assertEquals(1.0, total / normale, 0.02)
        }
    }

    @Test
    fun `le 29 fevrier est rattache au 28`() {
        val normales = DegresJours.normales(
            Fabrique.climat(date("2020-01-01"), date("2020-12-31")),
            date("2020-12-31"),
        )
        assertFalse(229 in normales)
        assertTrue(228 in normales)
    }
}
