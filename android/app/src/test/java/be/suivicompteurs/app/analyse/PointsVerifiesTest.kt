package be.suivicompteurs.app.analyse

import be.suivicompteurs.app.donnees.PointVerifie
import be.suivicompteurs.app.moteur.Anomalie
import be.suivicompteurs.app.moteur.Energie
import be.suivicompteurs.app.moteur.Plage
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Un point déclaré résolu ne revient plus parmi les points à vérifier. */
class PointsVerifiesTest {

    private val fuite = Anomalie.Surconsommation(
        debut = LocalDate.of(2023, 3, 1), fin = LocalDate.of(2023, 5, 31),
        volume = 40.0, unite = "m³", nbJours = 92, rapport = 3.1, faceAuClimat = false,
    )
    private val lacune = Anomalie.Lacune(LocalDate.of(2024, 1, 10), LocalDate.of(2024, 1, 20))

    private fun verifie(anomalie: Anomalie, energie: Energie = Energie.EAU, debut: LocalDate = anomalie.debut) =
        PointVerifie(
            maisonId = 1, energie = energie.code, plage = Plage.UNIQUE.code, genre = anomalie.genre,
            debut = debut.toString(), fin = anomalie.fin.toString(), description = "",
            resoluLe = "2023-06-15", note = "Chasse d'eau réparée",
        )

    @Test
    fun `un point resolu n'est plus a verifier, les autres restent`() {
        val restants = aVerifier(Energie.EAU, Plage.UNIQUE, listOf(fuite, lacune), listOf(verifie(fuite)))
        assertEquals(listOf<Anomalie>(lacune), restants)
    }

    @Test
    fun `le point reste reconnu si un releve corrige deplace ses dates`() {
        val decale = verifie(fuite, debut = fuite.debut.plusDays(10))
        assertTrue(decale.couvre(Energie.EAU, Plage.UNIQUE, fuite))
    }

    @Test
    fun `un point resolu sur une autre energie ou d'un autre genre ne compte pas`() {
        assertFalse(verifie(fuite, energie = Energie.GAZ).couvre(Energie.EAU, Plage.UNIQUE, fuite))
        assertFalse(verifie(lacune).copy(debut = fuite.debut.toString(), fin = fuite.fin.toString())
            .couvre(Energie.EAU, Plage.UNIQUE, fuite))
    }
}
