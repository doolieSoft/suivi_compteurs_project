package be.suivicompteurs.app.classeur

import be.suivicompteurs.app.donnees.Historique
import be.suivicompteurs.app.donnees.ReleveHistorique
import be.suivicompteurs.app.moteur.Compteur
import be.suivicompteurs.app.moteur.DegresJours
import be.suivicompteurs.app.moteur.Energie
import be.suivicompteurs.app.moteur.Plage
import be.suivicompteurs.app.moteur.Releve
import be.suivicompteurs.app.moteur.lignes
import be.suivicompteurs.app.moteur.prevoir
import java.io.File
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Le téléphone doit relire les exports du serveur.
 *
 * `export-synthetique.xlsx` est produit par l'export du serveur à partir de
 * données fabriquées (aucune donnée personnelle) : c'est le vrai format, tel
 * qu'openpyxl l'écrit.
 */
class ImportClasseurTest {

    private fun synthetique(): ResultatImport {
        val flux = javaClass.classLoader!!.getResourceAsStream("export-synthetique.xlsx")
        return ImportClasseur.importer(Classeur.lire(flux), "essai")
    }

    @Test
    fun `toutes les donnees sont relues`() {
        val h = synthetique().historique
        assertEquals(listOf("Maison d'essai", "Ancienne"), h.maisons.map { it.nom })
        assertEquals(listOf(true, false), h.maisons.map { it.actuelle })
        assertEquals(1, h.stations.size)
        assertEquals(4, h.compteurs.size)
        assertEquals(77, h.releves.size)
        assertEquals(1096, h.degresJours.size)
        assertEquals(2, h.tarifs.size)
        // Le tarif commun à toutes les maisons n'en désigne aucune.
        assertNull(h.tarifs.single { it.energie == "EAU" }.maisonId)
    }

    @Test
    fun `la marque annuelle et les index survivent a l'aller-retour`() {
        val h = synthetique().historique
        val annuel = h.releves.single { it.annuel }
        assertEquals("GAZ", h.compteurs.single { it.id == annuel.compteurId }.energie)
        val haut = h.compteurs.single { it.plage == "HAUT" }
        assertEquals(
            listOf("2021-12-31" to 500.1, "2022-10-31" to 900.4),
            h.releves.filter { it.compteurId == haut.id }.map { it.date to it.index },
        )
    }

    @Test
    fun `seuls les compteurs encore poses de la maison actuelle sont a relever`() {
        val resultat = synthetique()
        val aSaisir = resultat.compteursASaisir
        assertEquals(2, aSaisir.size)
        assertEquals(
            setOf("Maison d'essai – Gaz", "Maison d'essai – Eau – (compteur 2)"),
            aSaisir.map { it.libelle }.toSet(),
        )
        val gaz = aSaisir.single { it.energie == "GAZ" }
        val dernier = resultat.historique.releves.filter { it.compteurId == gaz.id.toLong() }.last()
        assertEquals(dernier.index, gaz.dernierIndex!!, 0.0)
        assertEquals(dernier.date, gaz.dernierReleve)
        assertTrue(gaz.consoJournaliereMax > 1.0)
    }

    @Test
    fun `les releves de l'appareil sont rattaches au bon compteur`() {
        val resultat = synthetique()
        val gaz = resultat.compteursASaisir.single { it.energie == "GAZ" }
        // Avant l'import, l'appareil numérotait ce même compteur 15.
        val ancien = gaz.copy(id = 15)
        val saisies = listOf(
            ReleveHistorique(15, "2025-01-10", 99_999.0, false, commentaire = "cave", photo = "/photo.jpg"),
            // Déjà dans le classeur à cette date : le classeur fait foi.
            ReleveHistorique(15, gaz.dernierReleve!!, 1.0, false),
            // Compteur inconnu du classeur : ignoré plutôt que mal rattaché.
            ReleveHistorique(99, "2025-01-10", 5.0, false),
        )
        val fusion = ImportClasseur.reprendreSaisies(resultat, listOf(ancien), saisies)

        assertEquals(resultat.nbReleves + 1, fusion.nbReleves)
        val nouveau = fusion.historique.releves.single { it.date == "2025-01-10" }
        assertEquals(gaz.id.toLong(), nouveau.compteurId)
        assertEquals(99_999.0, nouveau.index, 0.0)
        assertEquals("/photo.jpg", nouveau.photo)
        assertEquals("2025-01-10", fusion.compteursASaisir.single { it.id == gaz.id }.dernierReleve)
        assertEquals(gaz.dernierIndex, fusion.historique.releves
            .single { it.compteurId == gaz.id.toLong() && it.date == gaz.dernierReleve }.index)
    }

    @Test
    fun `reimporter un classeur plus ancien ne perd pas les releves notes depuis`() {
        val premier = synthetique()
        val gaz = premier.compteursASaisir.single { it.energie == "GAZ" }
        // Un relevé noté sur l'appareil après le premier import.
        val apres = premier.historique.copy(
            releves = premier.historique.releves +
                ReleveHistorique(gaz.id.toLong(), "2026-10-03", 99_999.0, false, commentaire = "cave"),
        )
        val deNouveau = synthetique()
        val fusion = ImportClasseur.reprendreSaisies(
            deNouveau,
            premier.compteursASaisir,
            ImportClasseur.relevesDeLAppareil(apres, premier.compteursASaisir),
        )
        assertEquals(deNouveau.nbReleves + 1, fusion.nbReleves)
        val repris = fusion.historique.releves.single { it.date == "2026-10-03" }
        assertEquals(99_999.0, repris.index, 0.0)
        assertEquals("cave", repris.commentaire)
    }

    @Test
    fun `un compteur remplace n'est pas propose meme sans date de depose`() {
        val h = synthetique().historique
        // Comme après une mise à jour qui ne connaissait pas encore ces champs.
        val sansDates = h.copy(compteurs = h.compteurs.map { it.copy(dateDepose = null, remplaceId = null) })
        val aSaisir = ImportClasseur.compteursASaisir(sansDates).map { it.libelle }.toSet()
        assertEquals(setOf("Maison d'essai – Gaz", "Maison d'essai – Eau – (compteur 2)"), aSaisir)
    }

    @Test
    fun `un fichier qui n'est pas un export est refuse`() {
        assertThrows(ClasseurIllisible::class.java) {
            Classeur.lire("pas un classeur".byteInputStream())
        }
    }

    /**
     * Chaîne complète sur les données réelles : export du serveur, import sur
     * le téléphone, et mêmes chiffres que le moteur Python. Ignoré sans la
     * référence, que produit `python manage.py reference_moteur`.
     */
    @Test
    fun `apres import les calculs sont ceux du serveur`() {
        val classeur = System.getProperty("reference.classeur")?.let(::File)
        val reference = System.getProperty("reference.moteur")?.let(::File)
        assumeTrue(classeur?.exists() == true && reference?.exists() == true)

        val h = ImportClasseur.importer(Classeur.lire(classeur!!.inputStream()), "reel").historique
        val maisons = JSONObject(reference!!.readText()).getJSONArray("maisons")
        var comparees = 0
        for (i in 0 until maisons.length()) {
            val m = maisons.getJSONObject(i)
            val maison = h.maisons.single { it.nom == m.getString("libelle") }
            val djs = djsDe(h, maison.stationId)
            val jour = LocalDate.parse(m.getJSONArray("dates").getString(0))
            val normales = DegresJours.normales(djs, jour)
            val calculees = lignes(compteursDe(h, maison.id), djs)
            val attendues = m.getJSONArray("lignes")
            assertEquals(attendues.length(), calculees.size)
            for (j in 0 until attendues.length()) {
                val a = attendues.getJSONObject(j)
                val ligne = calculees.single {
                    it.energie.code == a.getString("energie") && it.plage.code == a.getString("plage")
                }
                proche(a.getDouble("total"), ligne.serie.total)
                proche(a.getJSONObject("modele").getDouble("k"), ligne.serie.modele.k)
                val prevu = a.getJSONObject("previsions").optJSONObject(jour.toString())
                if (prevu != null) {
                    proche(prevu.getDouble("total_prevu"), prevoir(ligne, jour, normales)!!.totalPrevu)
                }
                comparees++
            }
        }
        assertTrue(comparees > 0)
        println("Import du classeur réel : $comparees lignes identiques au serveur.")
    }

    private fun djsDe(h: Historique, stationId: Long?): Map<LocalDate, Double> =
        h.degresJours.filter { it.stationId == stationId }
            .associateTo(LinkedHashMap()) { LocalDate.parse(it.date) to it.dj }

    private fun compteursDe(h: Historique, maisonId: Long): List<Compteur> =
        h.compteurs.filter { it.maisonId == maisonId }.map { c ->
            Compteur(
                id = c.id,
                energie = Energie.depuisCode(c.energie),
                plage = Plage.depuisCode(c.plage),
                unite = c.unite,
                datePose = c.datePose?.let(LocalDate::parse),
                releves = h.releves.filter { it.compteurId == c.id }
                    .map { Releve(LocalDate.parse(it.date), it.index, it.annuel) },
            )
        }

    private fun proche(attendu: Double, obtenu: Double) {
        assertTrue("attendu $attendu, obtenu $obtenu", abs(attendu - obtenu) <= 1e-9 * max(1.0, abs(attendu)))
    }
}
