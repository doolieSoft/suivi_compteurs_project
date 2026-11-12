package be.suivicompteurs.app.moteur

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class ConsommationsPasseesTest {

    private fun jour(texte: String) = LocalDate.parse(texte)
    private fun mois(texte: String, volume: Double) = periodeDuMois(YearMonth.parse(texte), volume)

    private fun conflit(existants: Map<LocalDate, Double?>, saisies: List<ConsommationSaisie>): ConflitConsommation {
        try {
            integrerConsommations(existants, saisies)
        } catch (c: ConflitConsommation) {
            return c
        }
        fail("conflit attendu")
        throw IllegalStateException()
    }

    @Test
    fun `un mois va du dernier jour du mois precedent au dernier jour du mois`() {
        val janvier = mois("2025-01", 10.0)
        assertEquals(jour("2024-12-31"), janvier.debut)
        assertEquals(jour("2025-01-31"), janvier.fin)
        assertEquals(jour("2025-02-28"), mois("2025-02", 1.0).fin)
        assertEquals(jour("2024-12-31"), periodeDeLAnnee(2025, 1.0).debut)
    }

    @Test
    fun `une annee de mois sur un compteur vide se suit sans trou`() {
        val r = integrerConsommations(emptyMap(), (1..12).map { mois("2025-%02d".format(it), it * 10.0) })
        assertEquals(13, r.size)
        assertEquals(0.0, r[jour("2024-12-31")])
        assertEquals(120.0, r[jour("2025-12-31")])
        val index = indexDesConsommations(r.map { it.key to (it.value ?: 0.0) })
        assertEquals(780.0, index.getValue(jour("2025-12-31")), 1e-9)
    }

    @Test
    fun `l annee precedente se raccorde a la serie existante`() {
        val existants = mapOf(jour("2024-12-31") to 0.0, jour("2025-01-31") to 50.0)
        val r = integrerConsommations(existants, listOf(periodeDeLAnnee(2024, 900.0)))
        assertEquals(0.0, r[jour("2023-12-31")])
        assertEquals(900.0, r[jour("2024-12-31")])
        assertEquals(50.0, r[jour("2025-01-31")])
    }

    @Test
    fun `une periode connue se corrige`() {
        val existants = mapOf(jour("2024-12-31") to 0.0, jour("2025-01-31") to 50.0, jour("2025-02-28") to 40.0)
        val r = integrerConsommations(existants, listOf(mois("2025-01", 55.0)))
        assertEquals(55.0, r[jour("2025-01-31")])
        assertEquals(40.0, r[jour("2025-02-28")])
        assertEquals(55.0, consommationConnue(existants.plus(jour("2025-01-31") to 55.0), mois("2025-01", 0.0)))
        assertNull(consommationConnue(existants, mois("2025-03", 0.0)))
    }

    @Test
    fun `une annee ne recouvre pas des mois deja saisis`() {
        val existants = mapOf(jour("2024-12-31") to 0.0, jour("2025-01-31") to 50.0, jour("2025-02-28") to 40.0)
        val c = conflit(existants, listOf(periodeDeLAnnee(2025, 900.0)))
        assertTrue(c.chevauchement)
    }

    @Test
    fun `un mois ne coupe pas une periode plus longue`() {
        val existants = mapOf(jour("2024-12-31") to 0.0, jour("2025-12-31") to 900.0)
        assertTrue(conflit(existants, listOf(mois("2025-03", 50.0))).chevauchement)
        assertTrue(conflit(existants, listOf(mois("2025-12", 50.0))).chevauchement)
    }

    @Test
    fun `un mois oublie laisse un trou`() {
        val c = conflit(emptyMap(), listOf(mois("2025-01", 10.0), mois("2025-03", 30.0)))
        assertFalse(c.chevauchement)
        assertEquals(jour("2025-01-31"), c.debut)
        assertEquals(jour("2025-02-28"), c.fin)
    }

    @Test
    fun `une annee eloignee de la serie laisse un trou`() {
        val existants = mapOf(jour("2024-12-31") to 0.0, jour("2025-01-31") to 50.0)
        assertFalse(conflit(existants, listOf(periodeDeLAnnee(2022, 900.0))).chevauchement)
        assertFalse(conflit(existants, listOf(mois("2025-04", 10.0))).chevauchement)
    }

    @Test
    fun `la suite de la serie s ajoute`() {
        val existants = mapOf(jour("2024-12-31") to 0.0, jour("2025-01-31") to 50.0)
        val r = integrerConsommations(existants, listOf(mois("2025-02", 40.0), mois("2025-03", 30.0)))
        assertEquals(30.0, r[jour("2025-03-31")])
        assertEquals(4, r.size)
    }
}
