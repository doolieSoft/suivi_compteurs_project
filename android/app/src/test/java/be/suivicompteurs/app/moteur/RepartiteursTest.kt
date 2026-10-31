package be.suivicompteurs.app.moteur

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class RepartiteursTest {

    private val salon = Radiateur(1, 2.0)
    private val chambre = Radiateur(2, 0.5)
    private val radiateurs = listOf(salon, chambre)

    private fun jour(texte: String) = LocalDate.parse(texte)

    private fun releve(date: String, vararg chiffres: Pair<Radiateur, Double>) =
        chiffres.map { (r, unites) -> ChiffreRadiateur(r.id, jour(date), unites) }

    @Test
    fun `chaque ecart est multiplie par le coefficient de son radiateur`() {
        val index = indexCumules(
            radiateurs,
            releve("2026-09-01", salon to 100.0, chambre to 100.0) +
                releve("2027-01-01", salon to 180.0, chambre to 140.0),
        )
        assertEquals(0.0, index.getValue(jour("2026-09-01")), 1e-9)
        assertEquals(180.0, index.getValue(jour("2027-01-01")), 1e-9)
    }

    @Test
    fun `une remise a zero ne fait pas reculer l index`() {
        val index = indexCumules(
            radiateurs,
            releve("2026-09-01", salon to 100.0, chambre to 100.0) +
                releve("2027-01-01", salon to 180.0, chambre to 140.0) +
                releve("2027-03-01", salon to 30.0, chambre to 20.0),
        )
        assertEquals(250.0, index.getValue(jour("2027-03-01")), 1e-9)
    }

    @Test
    fun `un seul radiateur peut repartir de zero`() {
        val index = indexCumules(
            radiateurs,
            releve("2026-09-01", salon to 100.0, chambre to 100.0) +
                releve("2027-01-01", salon to 10.0, chambre to 120.0),
        )
        assertEquals(30.0, index.getValue(jour("2027-01-01")), 1e-9)
    }

    @Test
    fun `un radiateur ajoute en route part de son premier chiffre`() {
        val index = indexCumules(
            radiateurs,
            releve("2026-09-01", salon to 100.0) +
                releve("2027-01-01", salon to 110.0, chambre to 500.0) +
                releve("2027-03-01", salon to 120.0, chambre to 510.0),
        )
        assertEquals(20.0, index.getValue(jour("2027-01-01")), 1e-9)
        assertEquals(45.0, index.getValue(jour("2027-03-01")), 1e-9)
    }

    @Test
    fun `un radiateur oublie reprend depuis son dernier chiffre connu`() {
        val index = indexCumules(
            radiateurs,
            releve("2026-09-01", salon to 100.0, chambre to 100.0) +
                releve("2027-01-01", chambre to 110.0) +
                releve("2027-03-01", salon to 150.0, chambre to 120.0),
        )
        assertEquals(5.0, index.getValue(jour("2027-01-01")), 1e-9)
        assertEquals(110.0, index.getValue(jour("2027-03-01")), 1e-9)
    }

    @Test
    fun `un coefficient corrige vaut pour tout l historique`() {
        val chiffres = releve("2026-09-01", salon to 100.0) + releve("2027-01-01", salon to 110.0)
        assertEquals(20.0, indexCumules(listOf(salon), chiffres).getValue(jour("2027-01-01")), 1e-9)
        assertEquals(30.0, indexCumules(listOf(salon.copy(coefficient = 3.0)), chiffres).getValue(jour("2027-01-01")), 1e-9)
    }

    @Test
    fun `les relevés sont pris dans l ordre des dates`() {
        val index = indexCumules(
            radiateurs,
            releve("2027-03-01", salon to 120.0) + releve("2026-09-01", salon to 100.0) + releve("2027-01-01", salon to 110.0),
        )
        assertEquals(listOf(jour("2026-09-01"), jour("2027-01-01"), jour("2027-03-01")), index.keys.toList())
        assertEquals(40.0, index.getValue(jour("2027-03-01")), 1e-9)
    }

    @Test
    fun `les coefficients decimaux ne laissent pas de bruit`() {
        val r = Radiateur(1, 0.1)
        val index = indexCumules(listOf(r), releve("2026-09-01", Radiateur(1, 0.1) to 0.0) + releve("2027-01-01", r to 3.0))
        assertEquals(0.3, index.getValue(jour("2027-01-01")), 0.0)
    }

    @Test
    fun `seuls les chiffres plus bas sont signales`() {
        val baisses = radiateursEnBaisse(mapOf(1L to 100.0, 2L to 100.0), mapOf(1L to 90.0, 2L to 100.0, 3L to 5.0))
        assertEquals(setOf(1L), baisses)
    }
}
