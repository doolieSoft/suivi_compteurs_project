package be.suivicompteurs.app.classeur

import be.suivicompteurs.app.donnees.Historique
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L'export du téléphone est la sauvegarde du mode autonome : réimporté, il
 * doit rendre exactement les mêmes données.
 */
class ExportClasseurTest {

    private fun importer(flux: java.io.InputStream) = ImportClasseur.importer(Classeur.lire(flux), "essai")

    private fun synthetique() =
        importer(javaClass.classLoader!!.getResourceAsStream("export-synthetique.xlsx")).historique

    private fun exporter(h: Historique): ByteArray =
        ByteArrayOutputStream().also { ExportClasseur.ecrire(h, it, LocalDate.of(2025, 1, 1)) }.toByteArray()

    /** Les identifiants sont propres à chaque base : on compare ce qu'ils désignent. */
    private fun comparable(h: Historique): Map<String, Any> {
        val maison = h.maisons.associate { it.id to it.nom }
        val compteur = h.compteurs.associate { it.id to "${maison[it.maisonId]}/${it.energie}/${it.plage}/${it.libelle}" }
        val station = h.stations.associate { it.id to it.nom }
        return mapOf(
            "maisons" to h.maisons.map { it.copy(id = 0, stationId = null) to station[it.stationId] }.sortedBy { it.toString() },
            "stations" to h.stations.map { it.copy(id = 0) }.sortedBy { it.nom },
            "compteurs" to h.compteurs.map {
                listOf(it.copy(id = 0, maisonId = 0, remplaceId = null), maison[it.maisonId], it.remplaceId?.let(compteur::get))
            }.sortedBy { it.toString() },
            "releves" to h.releves.map { compteur[it.compteurId] to it.copy(compteurId = 0) }.sortedBy { it.toString() },
            "dj" to h.degresJours.map { station[it.stationId] to it.copy(stationId = 0) }.sortedBy { it.toString() },
            "tarifs" to h.tarifs.map { it.maisonId?.let(maison::get) to it.copy(id = 0, maisonId = null) }.sortedBy { it.toString() },
            "evenements" to h.evenements.map { maison[it.maisonId] to it.copy(id = 0, maisonId = 0) }.sortedBy { it.toString() },
        )
    }

    @Test
    fun `exporter puis reimporter rend les memes donnees`() {
        val avant = synthetique()
        val apres = importer(exporter(avant).inputStream()).historique

        val a = comparable(avant)
        val b = comparable(apres)
        for (cle in a.keys) assertEquals(cle, a[cle], b[cle])
        assertTrue(avant.evenements.isNotEmpty())
        assertTrue(avant.compteurs.any { it.remplaceId != null })
    }

    @Test
    fun `la presentation est celle de l'export du serveur`() {
        val classeur = Classeur.lire(exporter(synthetique()).inputStream())
        assertEquals(
            listOf("Eau", "Gaz", "Électricité", "Mazout", "Par année", "Maisons", "Compteurs", "Événements", "Tarifs", "Degrés-jours"),
            classeur.noms,
        )
        val gaz = classeur["Gaz"]!!
        assertEquals("maison-dessai-gaz-mono-horaire", gaz.valeur(0, 0))
        assertEquals("Date", gaz.valeur(1, 0))
        assertTrue(classeur["Par année"]!!.lignes.size > 1)
    }

    @Test
    fun `slugifier suit Django`() {
        assertEquals("maison-dessai", ExportClasseur.slugifier("Maison d'essai"))
        assertEquals("electricite", ExportClasseur.slugifier("Électricité"))
        assertEquals("heures-pleines", ExportClasseur.slugifier("Heures pleines"))
    }
}
