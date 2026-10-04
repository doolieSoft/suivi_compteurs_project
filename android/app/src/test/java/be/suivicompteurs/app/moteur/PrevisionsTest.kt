package be.suivicompteurs.app.moteur

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Transposition de `suivi/tests/test_previsions.py` et `test_degres_jours_et_couts.py`. */
private const val BASE_VRAIE = 0.5
private const val K_VRAI = 0.7

private fun gazTousLes30Jours(
    djs: Map<LocalDate, Double>,
    debut: String,
    fin: String,
    annuels: Set<LocalDate> = emptySet(),
    extra: List<LocalDate> = emptyList(),
): Compteur {
    val conso = djs.mapValues { BASE_VRAIE + K_VRAI * it.value }
    val dates = (Fabrique.datesTousLes(date(debut), date(fin), 30) + extra).distinct()
    return Fabrique.compteur(Energie.GAZ, Fabrique.releves(conso, dates, annuels = annuels))
}

private fun masquerApres(compteur: Compteur, jour: LocalDate) =
    compteur.copy(releves = compteur.releves.filter { it.date <= jour })

class PrevisionGazTest {
    private val djs = Fabrique.climat(date("2019-01-01"), date("2024-12-31"))
    private val compteur = gazTousLes30Jours(djs, "2019-01-01", "2024-12-31")
    private val normales = DegresJours.normales(djs, date("2024-12-31"))
    private fun ligne(c: Compteur = compteur) = lignes(listOf(c), djs).single()

    @Test
    fun `le realise n'est pas reecrit`() {
        val ligne = ligne()
        val p = prevoir(ligne, date("2024-06-30"), normales, annee = 2024)!!
        val attendu = ligne.serie.jours.filterKeys { it.year == 2024 }.values.sum()
        assertEquals(attendu, p.realise, 1e-4)
    }

    @Test
    fun `une annee entierement couverte n'a rien a estimer`() {
        val p = prevoir(ligne(), date("2024-12-31"), normales, annee = 2023)!!
        assertEquals(0, p.joursRestants)
        assertEquals(0.0, p.estimeRestant, 0.0)
        assertEquals(p.realise, p.totalPrevu, 1e-6)
    }

    @Test
    fun `la projection de mi-annee approche le total reel`() {
        val p = prevoir(ligne(masquerApres(compteur, date("2024-06-30"))), date("2024-06-30"), normales, annee = 2024)!!
        val totalReel = djs.filterKeys { it.year == 2024 }.values.sumOf { BASE_VRAIE + K_VRAI * it }
        assertEquals(1.0, p.totalPrevu / totalReel, 0.08)
        assertEquals(Methode.Thermique, p.methode)
    }

    @Test
    fun `la tendance compare a la prevision du releve precedent`() {
        val jour = date("2024-06-30")
        val ligneDuJour = ligne(masquerApres(compteur, jour))
        val p = prevoir(ligneDuJour, jour, normales, annee = 2024)!!
        val avant = prevoirAuRelevePrecedent(ligneDuJour, djs, p)!!
        // Le relevé d'avant, sur la même année : seule l'information a changé.
        assertTrue(avant.releve < jour)
        assertEquals(p.debut, avant.prevision.debut)
        assertEquals(p.fin, avant.prevision.fin)
        assertTrue(avant.prevision.joursRealises < p.joursRealises)
    }

    @Test
    fun `pas de tendance sans releve precedent dans la periode`() {
        // Premier relevé de l'année : le précédent appartient à l'année d'avant.
        val premier = compteur.releves.map { it.date }.filter { it.year == 2024 }.min()
        val ligneDuJour = ligne(masquerApres(compteur, premier))
        val p = prevoir(ligneDuJour, premier, normales, annee = 2024)!!
        val precedent = compteur.releves.map { it.date }.filter { it < premier }.max()
        assertTrue(precedent < p.debut.minusDays(1))
        assertNull(prevoirAuRelevePrecedent(ligneDuJour, djs, p))
    }

    @Test
    fun `la fourchette encadre la prevision`() {
        val p = prevoir(ligne(masquerApres(compteur, date("2024-06-30"))), date("2024-06-30"), normales, annee = 2024)!!
        assertTrue(p.borneBasse!! <= p.totalPrevu)
        assertTrue(p.borneHaute!! >= p.totalPrevu)
    }
}

class PrevisionSansModeleTest {
    @Test
    fun `la projection retrouve le total annuel`() {
        val djs = Fabrique.climat(date("2020-01-01"), date("2024-12-31"))
        // Consommation strictement constante : 0,2 m³/jour.
        val conso = jours(date("2020-01-01"), date("2024-06-30")).associateWith { 0.2 }
        val compteur = Fabrique.compteur(
            Energie.EAU,
            Fabrique.releves(conso, Fabrique.datesTousLes(date("2020-01-01"), date("2024-06-30"), 30)),
        )
        val ligne = lignes(listOf(compteur), djs).single()
        val p = prevoir(ligne, date("2024-06-30"), DegresJours.normales(djs, date("2024-06-30")), annee = 2024)!!
        assertEquals(0.2 * 366, p.totalPrevu, 0.2 * 366 * 0.05)
        assertTrue(p.methode is Methode.ProfilSaisonnier)
    }

    @Test
    fun `une annee de fuite ne sert pas de reference`() {
        val djs = Fabrique.climat(date("2020-01-01"), date("2024-12-31"))
        // 0,2 m³/jour, sauf une fuite au premier trimestre 2023 (0,6 m³/jour) :
        // prise en compte, elle ferait croire que l'année se consomme surtout en
        // début d'année, et la prévision de mi-2024 tomberait 7 % trop bas.
        val conso = jours(date("2020-01-01"), date("2024-06-30")).associateWith { jour ->
            if (jour >= date("2023-01-01") && jour <= date("2023-03-31")) 0.6 else 0.2
        }
        val compteur = Fabrique.compteur(
            Energie.EAU,
            Fabrique.releves(conso, Fabrique.datesTousLes(date("2020-01-01"), date("2024-06-30"), 30)),
        )
        val ligne = lignes(listOf(compteur), djs).single()
        assertTrue(detecterAnomalies(ligne).any { it is Anomalie.Surconsommation && it.debut.year == 2023 })

        val p = prevoir(ligne, date("2024-06-30"), DegresJours.normales(djs, date("2024-06-30")), annee = 2024)!!
        assertEquals(0.2 * 366, p.totalPrevu, 0.2 * 366 * 0.01)
        // Elle reste la référence affichée : c'est bien l'année précédente.
        assertEquals(2023, p.referenceAnnee)
    }

    @Test
    fun `sans annee fiable le rythme de l'annee en cours est prolonge`() {
        val djs = Fabrique.climat(date("2022-01-01"), date("2024-12-31"))
        // Une seule année complète en référence, 2023, et c'est l'année d'une fuite.
        val conso = jours(date("2023-01-01"), date("2024-06-30")).associateWith { jour ->
            if (jour >= date("2023-01-01") && jour <= date("2023-03-31")) 0.6 else 0.2
        }
        val compteur = Fabrique.compteur(
            Energie.EAU,
            Fabrique.releves(conso, Fabrique.datesTousLes(date("2023-01-01"), date("2024-06-30"), 30)),
        )
        val ligne = lignes(listOf(compteur), djs).single()
        val p = prevoir(ligne, date("2024-06-30"), DegresJours.normales(djs, date("2024-06-30")), annee = 2024)!!
        assertTrue(p.methode is Methode.Prorata)
        assertEquals(0.2 * 366, p.totalPrevu, 0.2 * 366 * 0.02)
    }
}

class ComparaisonGlissanteTest {
    @Test
    fun `les deux cumuls portent sur la meme portion d'annee`() {
        val djs = Fabrique.climat(date("2022-01-01"), date("2024-12-31"))
        val conso = djs.mapValues { BASE_VRAIE + K_VRAI * it.value }
        val compteur = Fabrique.compteur(
            Energie.GAZ,
            Fabrique.releves(conso, Fabrique.datesTousLes(date("2022-01-01"), date("2024-12-31"), 15)),
        )
        val c = comparerAAnneePrecedente(lignes(listOf(compteur), djs).single(), date("2024-07-01"))
        assertNotNull(c)
        assertEquals(2024, c!!.annee)
        assertEquals(2023, c.anneePrecedente)
        // Climat identique d'une année à l'autre : l'écart doit être minime.
        assertTrue(kotlin.math.abs(c.evolutionPct!!) < 5.0)
    }
}

class RejeuTest {
    private val djs = Fabrique.climat(date("2019-01-01"), date("2024-12-31"))
    private val compteur = gazTousLes30Jours(djs, "2019-01-01", "2024-12-31")

    @Test
    fun `chaque point ignore les releves posterieurs`() {
        val rejeu = rejouer(listOf(compteur), djs, 2023).single()
        val point = rejeu.points[5]
        // Référence : la même prévision, une fois l'avenir réellement effacé.
        val ligne = lignes(listOf(masquerApres(compteur, point.coupe)), djs).single()
        val attendue = prevoir(ligne, point.coupe, DegresJours.normales(djs, point.coupe), annee = 2023)!!
        assertEquals(attendue.realise, point.prevision.realise, 1e-6)
        assertEquals(attendue.totalPrevu, point.prevision.totalPrevu, 1e-6)
    }

    @Test
    fun `le reel vient de l'historique complet`() {
        val rejeu = rejouer(listOf(compteur), djs, 2023).single()
        val reel = djs.filterKeys { it.year == 2023 }.values.sumOf { BASE_VRAIE + K_VRAI * it }
        assertEquals(1.0, rejeu.reel / reel, 0.01)
        assertTrue(rejeu.ecartMoyenPct!! < 8.0)
    }

    @Test
    fun `une annee sans total reel n'est pas proposee`() {
        val annees = anneesRejouables(lignes(listOf(compteur), djs))
        assertFalse(2025 in annees)
        assertTrue(2023 in annees)
        assertEquals(emptyList<Rejeu>(), rejouer(listOf(compteur), djs, 2025))
    }
}

class ReleveAnnuelTest {
    private val djs = Fabrique.climat(date("2019-01-01"), date("2024-12-31"))
    private val conso = djs.mapValues { BASE_VRAIE + K_VRAI * it.value }
    // Le relevé annuel ne tombe pas le même jour d'une année à l'autre.
    private val annuels = setOf(date("2022-03-10"), date("2023-03-15"), date("2024-03-15"))
    private val compteur = gazTousLes30Jours(djs, "2019-01-01", "2024-12-31", annuels, annuels.toList())
    private val normales = DegresJours.normales(djs, date("2024-12-31"))

    private fun vrai(debut: LocalDate, fin: LocalDate) =
        conso.filterKeys { it >= debut && it <= fin }.values.sum()

    @Test
    fun `la prevision part du dernier releve annuel`() {
        val p = prevoir(lignes(listOf(compteur), djs).single(), date("2024-09-01"), normales)!!
        assertTrue(p.surReleveAnnuel)
        assertEquals(date("2024-03-15"), p.borneDepart)
        assertEquals(date("2025-03-15"), p.fin)
        assertEquals(2024, p.annee)
        assertEquals(2023, p.referenceAnnee)
        assertEquals(1.0, p.reference!! / vrai(date("2023-03-16"), date("2024-03-15")), 0.01)
    }

    @Test
    fun `sans releve annuel l'annee reste civile`() {
        val sansMarque = compteur.copy(releves = compteur.releves.map { it.copy(annuel = false) })
        val p = prevoir(lignes(listOf(sansMarque), djs).single(), date("2024-09-01"), normales)!!
        assertFalse(p.surReleveAnnuel)
        assertEquals(date("2024-01-01"), p.debut)
        assertEquals(date("2024-12-31"), p.fin)
    }

    @Test
    fun `le jour du releve annuel ouvre une periode vide`() {
        val ligne = lignes(listOf(masquerApres(compteur, date("2024-03-15"))), djs).single()
        val p = prevoir(ligne, date("2024-03-15"), normales)!!
        assertEquals(0.0, p.realise, 0.0)
        assertEquals(365, p.joursRestants)
        assertEquals(1.0, p.totalPrevu / vrai(date("2023-03-16"), date("2024-03-15")), 0.08)
    }

    @Test
    fun `le rejeu mesure l'ecart entre releve et borne`() {
        val rejeu = rejouer(listOf(compteur), djs, 2022).single()
        assertEquals(date("2022-03-16") to date("2023-03-15"), rejeu.debut to rejeu.fin)
        assertEquals(1.0, rejeu.reel / vrai(rejeu.debut, rejeu.fin), 0.01)
        assertEquals(date("2022-03-10"), rejeu.borneDebut!!.releve)
        assertEquals(-5, rejeu.borneDebut!!.ecartJours)
        assertEquals(1.0, rejeu.borneDebut!!.volumeEstime / vrai(date("2022-03-11"), date("2022-03-15")), 0.05)
        // Le relevé de 2023 tombe pile sur la borne : rien à estimer.
        assertEquals(0, rejeu.borneFin!!.ecartJours)
        assertEquals(0.0, rejeu.borneFin!!.volumeEstime, 0.0)
    }

    @Test
    fun `toutes les periodes ont la meme longueur`() {
        for (annee in listOf(2021, 2022)) {
            val rejeu = rejouer(listOf(compteur), djs, annee).single()
            assertEquals(365, ecartJours(rejeu.debut, rejeu.fin) + 1)
        }
        assertFalse(2024 in anneesRejouables(lignes(listOf(compteur), djs)))
    }
}

class CoutTest {
    private val djs = Fabrique.climat(date("2023-01-01"), date("2023-12-31"))
    private val ligne = lignes(
        listOf(
            Fabrique.compteur(
                Energie.EAU,
                Fabrique.releves(
                    jours(date("2023-01-01"), date("2023-12-31")).associateWith { 0.25 },
                    Fabrique.datesTousLes(date("2023-01-01"), date("2023-12-31"), 30),
                ),
            )
        ),
        djs,
    ).single()

    @Test
    fun `sans tarif aucun cout n'est produit`() {
        assertEquals(emptyList<CoutAnnuel>(), coutsParAnnee(ligne, emptyList()))
    }

    @Test
    fun `le cout variable suit le prix unitaire`() {
        val tarif = Tarif(Energie.EAU, date("2023-01-01"), date("2023-12-31"), prixUnitaire = 5.0)
        val cout = coutsParAnnee(ligne, listOf(tarif)).single()
        assertEquals(cout.consommation * 5.0, cout.coutVariable, 1e-4)
        assertEquals(5.0, cout.prixMoyen!!, 1e-6)
    }

    @Test
    fun `l'abonnement est reparti au prorata des jours suivis`() {
        val tarif = Tarif(Energie.EAU, date("2023-01-01"), date("2023-12-31"), abonnementMensuel = 10.0)
        val cout = coutsParAnnee(ligne, listOf(tarif)).single()
        // Onze mois pleins environ : jamais plus de douze mensualités.
        assertTrue(cout.coutAbonnement <= 120.0)
        assertTrue(cout.coutAbonnement > 90.0)
    }

    @Test
    fun `un tarif propre a la maison prime sur un tarif generique`() {
        val tarifs = listOf(
            Tarif(Energie.EAU, date("2023-01-01"), prixUnitaire = 1.0, propreALaMaison = false),
            Tarif(Energie.EAU, date("2023-01-01"), prixUnitaire = 9.0, propreALaMaison = true),
        )
        assertEquals(9.0, coutsParAnnee(ligne, tarifs).single().prixMoyen!!, 1e-6)
    }
}

/** Un nouvel utilisateur, sans historique : ses premières prévisions doivent déjà avoir un sens. */
class PrevisionNouvelUtilisateurTest {
    private val djs = Fabrique.climat(date("2023-01-01"), date("2025-12-31"))
    private val conso = djs.mapValues { BASE_VRAIE + K_VRAI * it.value }

    private fun compteur(pas: Long) = Fabrique.compteur(
        Energie.GAZ,
        Fabrique.releves(conso, Fabrique.datesTousLes(date("2024-10-01"), date("2025-12-31"), pas)),
    )

    private fun prevoirAu(c: Compteur, jour: LocalDate): Prevision? {
        val masque = c.copy(releves = c.releves.filter { it.date <= jour })
        return prevoir(lignes(listOf(masque), djs, jusqua = jour).single(), jour, DegresJours.normales(djs, jour))
    }

    @Test
    fun `la premiere annee commencee en cours de route est prevue en entier`() {
        // Suivi commencé le 1er octobre : janvier à septembre sont estimés, pas oubliés.
        val p = prevoirAu(compteur(7), date("2024-11-01"))!!
        val reel = conso.filterKeys { it.year == 2024 }.values.sum()
        assertTrue(p.joursEstimesAvant > 250)
        assertTrue(p.estimeAvant > 0)
        assertEquals(reel, p.totalPrevu, reel * 0.05)
    }

    @Test
    fun `sans releve encore dans l'annee le modele prevoit quand meme`() {
        // Relevés mensuels : le 15 janvier, aucun relevé de l'année encore.
        val c = compteur(30)
        assertTrue(c.releves.none { it.date.year == 2025 && it.date <= date("2025-01-15") })
        val p = prevoirAu(c, date("2025-01-15"))!!
        val reel = conso.filterKeys { it.year == 2025 }.values.sum()
        assertEquals(2025, p.annee)
        assertEquals(reel, p.totalPrevu, reel * 0.05)
    }
}
