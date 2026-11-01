package be.suivicompteurs.app.classeur

import be.suivicompteurs.app.donnees.AchatLocal
import be.suivicompteurs.app.donnees.CompteurHistorique
import be.suivicompteurs.app.donnees.Historique
import be.suivicompteurs.app.donnees.RadiateurLocal
import be.suivicompteurs.app.donnees.ReleveHistorique
import be.suivicompteurs.app.donnees.ReleveRadiateur
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

    /** Une maison qu'on ne relève plus, sans date de sortie connue, le reste après sauvegarde. */
    @Test
    fun `une maison plus relevee sans date de sortie le reste`() {
        val avant = synthetique().let { h ->
            h.copy(maisons = h.maisons.map { it.copy(actuelle = false, dateSortie = null) })
        }
        val apres = importer(exporter(avant).inputStream())
        assertTrue(apres.historique.maisons.none { it.actuelle })
        assertTrue(apres.compteursASaisir.isEmpty())
    }

    /** Les points vérifiés font partie de la sauvegarde. */
    @Test
    fun `les points verifies survivent a l'export`() {
        val avant = synthetique().let { h ->
            h.copy(
                pointsVerifies = listOf(
                    be.suivicompteurs.app.donnees.PointVerifie(
                        maisonId = h.maisons.first().id, energie = "EAU", plage = "UNIQUE", genre = "surconsommation",
                        debut = "2023-03-01", fin = "2023-05-31", description = "Consommation 3 fois l'habituelle",
                        resoluLe = "2023-06-15", note = "Chasse d'eau réparée",
                    ),
                ),
            )
        }
        val apres = importer(exporter(avant).inputStream()).historique
        assertEquals(avant.pointsVerifies.map { it.copy(id = 0, maisonId = 0) }, apres.pointsVerifies.map { it.copy(id = 0, maisonId = 0) })
        assertEquals(avant.maisons.first().nom, apres.maisons.single { it.id == apres.pointsVerifies.single().maisonId }.nom)
    }

    @Test
    fun `les radiateurs et leurs chiffres survivent a l'export`() {
        val base = synthetique()
        val maison = base.maisons.first().id
        val compteur = CompteurHistorique(9000, maison, "REP", "UNIQUE", "u", "", null)
        val salon = RadiateurLocal(70, compteur.id, "Salon", 2.0)
        val chambre = RadiateurLocal(71, compteur.id, "Chambre", 0.5, dateRetrait = "2025-06-01")
        val h = base.copy(
            compteurs = base.compteurs + compteur,
            releves = base.releves + listOf(
                ReleveHistorique(compteur.id, "2024-09-01", 0.0, false),
                ReleveHistorique(compteur.id, "2025-01-01", 180.0, false),
            ),
            radiateurs = listOf(salon, chambre),
            relevesRadiateurs = listOf(
                ReleveRadiateur(salon.id, compteur.id, "2024-09-01", 100.0),
                ReleveRadiateur(chambre.id, compteur.id, "2024-09-01", 100.0),
                ReleveRadiateur(salon.id, compteur.id, "2025-01-01", 180.0),
                ReleveRadiateur(chambre.id, compteur.id, "2025-01-01", 140.0),
            ),
        )

        val apres = importer(exporter(h).inputStream()).historique

        val repris = apres.compteurs.single { it.energie == "REP" }
        assertEquals("u", repris.unite)
        assertEquals(listOf(0.0, 180.0), apres.releves.filter { it.compteurId == repris.id }.map { it.index })
        val radiateurs = apres.radiateurs.associateBy { it.nom }
        assertEquals(setOf("Salon", "Chambre"), radiateurs.keys)
        assertEquals(repris.id, radiateurs.getValue("Salon").compteurId)
        assertEquals(0.5, radiateurs.getValue("Chambre").coefficient, 0.0)
        assertEquals("2025-06-01", radiateurs.getValue("Chambre").dateRetrait)
        val chiffres = apres.relevesRadiateurs.associateBy { it.radiateurId to it.date }
        assertEquals(4, chiffres.size)
        assertEquals(180.0, chiffres.getValue(radiateurs.getValue("Salon").id to "2025-01-01").unites, 0.0)
    }

    @Test
    fun `les soldes et les achats d un compteur prepaye survivent a l'export`() {
        val base = synthetique()
        val compteur = CompteurHistorique(9000, base.maisons.first().id, "ELEC", "UNIQUE", "kWh", "prépayé", "2026-01-01", prepaye = true)
        val h = base.copy(
            compteurs = base.compteurs + compteur,
            releves = base.releves + listOf(
                ReleveHistorique(compteur.id, "2026-11-01", 0.0, false, solde = 120.0),
                ReleveHistorique(compteur.id, "2026-11-10", 70.0, false, solde = 250.0),
            ),
            achats = listOf(
                AchatLocal(1, compteur.id, "2026-11-05", 200.0, montant = 700.0),
                AchatLocal(2, compteur.id, "2026-11-10", 50.0, tarif = 3.4, apresReleve = true),
            ),
        )

        val apres = importer(exporter(h).inputStream())

        val repris = apres.historique.compteurs.single { it.libelle == "prépayé" }
        assertTrue(repris.prepaye)
        val releves = apres.historique.releves.filter { it.compteurId == repris.id }
        assertEquals(listOf(120.0, 250.0), releves.map { it.solde })
        assertEquals(listOf(0.0, 70.0), releves.map { it.index })
        val achats = apres.historique.achats.sortedBy { it.date }
        assertEquals(listOf(repris.id, repris.id), achats.map { it.compteurId })
        assertEquals(700.0, achats[0].montant!!, 0.0)
        assertEquals(3.4, achats[1].tarif!!, 0.0)
        assertTrue(achats[1].apresReleve)
        // Le dernier solde lu, plus l'achat fait après lui.
        assertEquals(300.0, apres.compteursASaisir.single { it.id.toLong() == repris.id }.solde!!, 0.0)
    }

    @Test
    fun `sans repartiteur ni prepaye le classeur ne change pas`() {
        val noms = Classeur.lire(exporter(synthetique()).inputStream()).noms
        assertTrue("Répartiteurs" !in noms)
        assertTrue("Radiateurs" !in noms)
        assertTrue("Achats" !in noms)
        assertTrue("Soldes" !in noms)
    }

    @Test
    fun `la presentation est celle de l'export du serveur`() {
        val classeur = Classeur.lire(exporter(synthetique()).inputStream())
        assertEquals(
            listOf("Eau", "Gaz", "Électricité", "Mazout", "Par année", "Maisons", "Compteurs", "Événements", "Points vérifiés", "Tarifs", "Degrés-jours"),
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
