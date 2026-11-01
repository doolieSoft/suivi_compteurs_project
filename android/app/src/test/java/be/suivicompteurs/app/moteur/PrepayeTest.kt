package be.suivicompteurs.app.moteur

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PrepayeTest {

    private fun jour(texte: String) = LocalDate.parse(texte)
    private fun solde(date: String, valeur: Double) = SoldeReleve(jour(date), valeur)
    private fun achat(date: String, kwh: Double, apres: Boolean = false, montant: Double? = null) =
        AchatEnergie(jour(date), kwh, apres, montant)

    @Test
    fun `la consommation est le solde qui a baisse`() {
        val index = indexPrepayes(listOf(solde("2026-11-01", 120.0), solde("2026-11-10", 90.0)), emptyList())
        assertEquals(0.0, index.getValue(jour("2026-11-01")), 1e-9)
        assertEquals(30.0, index.getValue(jour("2026-11-10")), 1e-9)
    }

    @Test
    fun `un achat entre deux releves s ajoute au solde precedent`() {
        val index = indexPrepayes(
            listOf(solde("2026-11-01", 120.0), solde("2026-11-10", 250.0)),
            listOf(achat("2026-11-05", 200.0)),
        )
        assertEquals(70.0, index.getValue(jour("2026-11-10")), 1e-9)
    }

    @Test
    fun `l index ne recule jamais`() {
        val index = indexPrepayes(
            listOf(solde("2026-11-01", 120.0), solde("2026-11-10", 90.0), solde("2026-11-20", 250.0), solde("2026-11-30", 200.0)),
            listOf(achat("2026-11-15", 200.0)),
        )
        assertEquals(listOf(0.0, 30.0, 70.0, 120.0), index.values.toList())
    }

    @Test
    fun `un achat fait avant le releve du jour compte dans la periode qui s acheve`() {
        val index = indexPrepayes(
            listOf(solde("2026-11-01", 120.0), solde("2026-11-10", 250.0), solde("2026-11-20", 200.0)),
            listOf(achat("2026-11-10", 200.0)),
        )
        assertEquals(70.0, index.getValue(jour("2026-11-10")), 1e-9)
        assertEquals(120.0, index.getValue(jour("2026-11-20")), 1e-9)
    }

    @Test
    fun `un achat fait apres le releve du jour compte dans la periode suivante`() {
        val index = indexPrepayes(
            listOf(solde("2026-11-01", 120.0), solde("2026-11-10", 50.0), solde("2026-11-20", 200.0)),
            listOf(achat("2026-11-10", 200.0, apres = true)),
        )
        assertEquals(70.0, index.getValue(jour("2026-11-10")), 1e-9)
        assertEquals(120.0, index.getValue(jour("2026-11-20")), 1e-9)
    }

    @Test
    fun `un solde monte sans achat ne donne pas de consommation negative`() {
        assertEquals(0.0, consommationPrepayee(100.0, 0.0, 150.0), 0.0)
    }

    @Test
    fun `les releves sont pris dans l ordre des dates`() {
        val index = indexPrepayes(listOf(solde("2026-11-10", 90.0), solde("2026-11-01", 120.0)), emptyList())
        assertEquals(listOf(jour("2026-11-01"), jour("2026-11-10")), index.keys.toList())
        assertEquals(30.0, index.getValue(jour("2026-11-10")), 1e-9)
    }

    @Test
    fun `le solde estime ajoute les achats faits depuis le dernier releve`() {
        val soldes = listOf(solde("2026-11-01", 120.0), solde("2026-11-10", 90.0))
        val achats = listOf(achat("2026-11-05", 50.0), achat("2026-11-10", 20.0), achat("2026-11-10", 30.0, apres = true), achat("2026-11-12", 100.0))
        assertEquals(220.0, soldeEstime(soldes, achats)!!, 1e-9)
        assertNull(soldeEstime(emptyList(), achats))
    }

    @Test
    fun `un achat paye donne son prix au kwh jusqu au suivant`() {
        val tarifs = tarifsDesAchats(
            listOf(achat("2026-01-01", 100.0, montant = 300.0), achat("2026-02-01", 50.0), achat("2026-03-01", 100.0, montant = 350.0)),
            Energie.ELECTRICITE,
        )
        assertEquals(2, tarifs.size)
        assertEquals(3.0, tarifs[0].prixUnitaire, 1e-9)
        assertEquals(3.5, tarifs[1].prixUnitaire, 1e-9)
        assertTrue(tarifs.all { it.dateFin == null && it.propreALaMaison && it.energie == Energie.ELECTRICITE })
    }
}
