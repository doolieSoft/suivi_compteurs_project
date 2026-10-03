package be.suivicompteurs.app.moteur

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max

/**
 * Le moteur Kotlin doit rendre les mêmes chiffres que le moteur Python dont
 * il est le portage.
 *
 * `reference-synthetique.json` fige ce que calculait le Python sur les données
 * fabriquées de `export-synthetique.xlsx`, avant son retrait du dépôt. Une
 * modification du moteur qui change un chiffre doit donc être délibérée.
 *
 * Toutes les divergences sont collectées avant d'échouer : une liste complète
 * se corrige bien plus vite qu'une erreur à la fois.
 */
class PariteReferenceTest {

    private val ecarts = mutableListOf<String>()

    @Test
    fun `le moteur Kotlin rend les memes chiffres que le moteur Python`() {
        val texte = javaClass.classLoader!!.getResourceAsStream("reference-synthetique.json")
            .bufferedReader().use { it.readText() }
        val racine = JSONObject(texte)
        val maisons = racine.getJSONArray("maisons")
        var comparaisons = 0
        for (m in maisons.objets()) comparaisons += verifierMaison(m)

        assertTrue(
            "${ecarts.size} divergence(s) sur $comparaisons valeurs comparées :\n" +
                ecarts.take(60).joinToString("\n"),
            ecarts.isEmpty(),
        )
        println("Parité Python/Kotlin : $comparaisons valeurs identiques.")
    }

    private var compteur = 0

    private fun verifierMaison(m: JSONObject): Int {
        compteur = 0
        val nom = m.getString("nom")
        val djs = LinkedHashMap<LocalDate, Double>()
        val brut = m.getJSONObject("djs")
        for (cle in brut.keys().asSequence().sorted()) djs[LocalDate.parse(cle)] = brut.getDouble(cle)

        val compteurs = m.getJSONArray("compteurs").objets().map { c ->
            Compteur(
                id = c.getLong("id"),
                energie = Energie.depuisCode(c.getString("energie")),
                plage = Plage.depuisCode(c.getString("plage")),
                unite = c.getString("unite"),
                datePose = c.dateOuNull("date_pose"),
                releves = c.getJSONArray("releves").objets().map {
                    Releve(LocalDate.parse(it.getString("date")), it.getDouble("index"), it.getBoolean("annuel"))
                },
            )
        }
        val tarifs = m.getJSONArray("tarifs").objets().map {
            Tarif(
                energie = Energie.depuisCode(it.getString("energie")),
                dateDebut = LocalDate.parse(it.getString("debut")),
                dateFin = it.dateOuNull("fin"),
                prixUnitaire = it.getDouble("prix"),
                abonnementMensuel = it.getDouble("abonnement"),
                propreALaMaison = it.getBoolean("propre"),
            )
        }
        val dates = m.getJSONArray("dates").chaines().map(LocalDate::parse)
        val normalesParDate = dates.associateWith { DegresJours.normales(djs, it) }
        val lignes = lignes(compteurs, djs)

        val attendues = m.getJSONArray("lignes").objets()
        egal("$nom nombre de lignes", attendues.size, lignes.size)
        for (a in attendues) {
            val energie = Energie.depuisCode(a.getString("energie"))
            val plage = Plage.depuisCode(a.getString("plage"))
            val ligne = lignes.firstOrNull { it.energie == energie && it.plage == plage }
            val ou = "$nom/${energie.code}/${plage.code}"
            if (ligne == null) {
                ecarts += "$ou : ligne absente côté Kotlin"
                continue
            }
            verifierLigne(ou, a, ligne, tarifs, dates, normalesParDate)
        }

        egal("$nom années rejouables", m.getJSONArray("annees_rejouables").entiers(), anneesRejouables(lignes))
        val rejeux = m.getJSONObject("rejeux")
        for (annee in rejeux.keys()) {
            val obtenus = rejouer(compteurs, djs, annee.toInt())
            val attendus = rejeux.getJSONArray(annee).objets()
            egal("$nom rejeu $annee nombre", attendus.size, obtenus.size)
            for ((a, r) in attendus.zip(obtenus)) {
                val ou = "$nom rejeu $annee ${a.getString("energie")}"
                egal("$ou énergie", a.getString("energie"), r.energie.code)
                proche("$ou réel", a.getDouble("reel"), r.reel)
                egal("$ou jours", a.getInt("jours_reels"), r.joursReels)
                egal("$ou début", a.getString("debut"), r.debut.toString())
                egal("$ou fin", a.getString("fin"), r.fin.toString())
                val bornes = listOfNotNull(r.borneDebut, r.borneFin)
                val bornesAttendues = a.getJSONArray("bornes").objets()
                egal("$ou bornes", bornesAttendues.size, bornes.size)
                for ((ba, b) in bornesAttendues.zip(bornes)) {
                    egal("$ou borne date", ba.getString("date"), b.date.toString())
                    egal("$ou borne relevé", ba.chaineOuNull("releve"), b.releve?.toString())
                    proche("$ou borne volume", ba.getDouble("volume_estime"), b.volumeEstime)
                }
                val points = a.getJSONArray("points").objets()
                egal("$ou points", points.size, r.points.size)
                for ((pa, p) in points.zip(r.points)) {
                    egal("$ou coupe", pa.getString("coupe"), p.coupe.toString())
                    verifierPrevision("$ou au ${p.coupe}", pa.getJSONObject("prevision"), p.prevision)
                }
            }
        }
        return compteur
    }

    private fun verifierLigne(
        ou: String,
        a: JSONObject,
        ligne: Ligne,
        tarifs: List<Tarif>,
        dates: List<LocalDate>,
        normalesParDate: Map<LocalDate, Map<Int, Double>>,
    ) {
        val serie = ligne.serie
        proche("$ou total", a.getDouble("total"), serie.total)
        egal("$ou nombre de jours", a.getInt("nb_jours"), serie.jours.size)
        egal("$ou premier jour", a.chaineOuNull("premier_jour"), serie.premierJour?.toString())
        egal("$ou dernier jour", a.chaineOuNull("dernier_jour"), serie.dernierJour?.toString())
        val parAnnee = a.getJSONObject("par_annee")
        egal("$ou années", parAnnee.keys().asSequence().map { it.toInt() }.toSortedSet().toList(), serie.parAnnee().keys.toList())
        for (annee in parAnnee.keys()) proche("$ou $annee", parAnnee.getDouble(annee), serie.parAnnee()[annee.toInt()])
        egal(
            "$ou lacunes",
            a.getJSONArray("lacunes").let { l -> (0 until l.length()).map { l.getJSONArray(it).chaines() } },
            serie.lacunes.map { listOf(it.first.toString(), it.second.toString()) },
        )

        val m = a.getJSONObject("modele")
        proche("$ou modèle base", m.getDouble("base"), serie.modele.base)
        proche("$ou modèle k", m.getDouble("k"), serie.modele.k)
        proche("$ou modèle r²", m.getDouble("r2"), serie.modele.r2)
        egal("$ou modèle périodes", m.getInt("nb_periodes"), serie.modele.nbPeriodes)
        egal("$ou modèle fiable", m.getBoolean("fiable"), serie.modele.fiable)
        proche("$ou part chauffage", m.doubleOuNull("part_chauffage_pct"), serie.modele.partChauffagePct)

        val annees = comparerAnnees(ligne, normalesParDate.getValue(dates.first()))
        val anneesAttendues = a.getJSONArray("annees").objets()
        egal("$ou années comparées", anneesAttendues.size, annees.size)
        for ((aa, an) in anneesAttendues.zip(annees)) {
            val o = "$ou année ${aa.getInt("annee")}"
            egal("$o", aa.getInt("annee"), an.annee)
            proche("$o consommation", aa.getDouble("consommation"), an.consommation)
            egal("$o jours", aa.getInt("jours_couverts"), an.joursCouverts)
            proche("$o DJ réel", aa.getDouble("dj_reel"), an.djReel)
            proche("$o DJ normal", aa.getDouble("dj_normal"), an.djNormal)
            proche("$o normalisée", aa.doubleOuNull("consommation_normalisee"), an.consommationNormalisee)
            egal("$o complète", aa.getBoolean("complete"), an.complete)
        }

        egal(
            "$ou anomalies",
            a.getJSONArray("anomalies").objets().map {
                listOf(it.getString("genre"), it.getString("niveau"), it.getString("debut"), it.getString("fin"))
            },
            detecterAnomalies(ligne).map { listOf(genre(it), it.niveau.name.lowercase(), it.debut.toString(), it.fin.toString()) },
        )

        val couts = coutsParAnnee(ligne, tarifs)
        val coutsAttendus = a.getJSONArray("couts").objets()
        egal("$ou coûts", coutsAttendus.size, couts.size)
        for ((ca, c) in coutsAttendus.zip(couts)) {
            val o = "$ou coût ${ca.getInt("annee")}"
            egal(o, ca.getInt("annee"), c.annee)
            proche("$o variable", ca.getDouble("variable"), c.coutVariable)
            proche("$o abonnement", ca.getDouble("abonnement"), c.coutAbonnement)
            egal("$o jours tarifés", ca.getInt("jours_tarifes"), c.joursTarifes)
        }

        val previsions = a.getJSONObject("previsions")
        val comparaisons = a.getJSONObject("comparaisons")
        for (jour in dates) {
            val cle = jour.toString()
            val p = prevoir(ligne, jour, normalesParDate.getValue(jour))
            if (previsions.isNull(cle)) {
                egal("$ou prévision au $cle", null, p?.totalPrevu)
            } else if (p == null) {
                ecarts += "$ou prévision au $cle : absente côté Kotlin"
            } else {
                verifierPrevision("$ou prévision au $cle", previsions.getJSONObject(cle), p)
            }

            val c = comparerAAnneePrecedente(ligne, jour)
            if (comparaisons.isNull(cle)) {
                egal("$ou comparaison au $cle", null, c?.valeur)
            } else if (c == null) {
                ecarts += "$ou comparaison au $cle : absente côté Kotlin"
            } else {
                val ca = comparaisons.getJSONObject(cle)
                egal("$ou comparaison au $cle borne", ca.getString("jusqua"), c.jusqua.toString())
                proche("$ou comparaison au $cle valeur", ca.getDouble("valeur"), c.valeur)
                proche("$ou comparaison au $cle précédente", ca.getDouble("valeur_precedente"), c.valeurPrecedente)
                proche("$ou comparaison au $cle DJ", ca.getDouble("dj"), c.dj)
                proche("$ou comparaison au $cle DJ précédents", ca.getDouble("dj_precedent"), c.djPrecedent)
            }
        }
    }

    private fun verifierPrevision(ou: String, a: JSONObject, p: Prevision) {
        egal("$ou année", a.getInt("annee"), p.annee)
        proche("$ou réalisé", a.getDouble("realise"), p.realise)
        egal("$ou jours réalisés", a.getInt("jours_realises"), p.joursRealises)
        proche("$ou estimé", a.getDouble("estime_restant"), p.estimeRestant)
        egal("$ou jours restants", a.getInt("jours_restants"), p.joursRestants)
        proche("$ou total", a.getDouble("total_prevu"), p.totalPrevu)
        proche("$ou borne basse", a.doubleOuNull("borne_basse"), p.borneBasse)
        proche("$ou borne haute", a.doubleOuNull("borne_haute"), p.borneHaute)
        egal("$ou méthode", a.getString("methode"), libellePython(p.methode))
        proche("$ou référence", a.doubleOuNull("reference"), p.reference)
        egal("$ou année de référence", a.entierOuNull("reference_annee"), p.referenceAnnee)
        proche("$ou référence normalisée", a.doubleOuNull("reference_normalisee"), p.referenceNormalisee)
        egal("$ou début", a.getString("debut"), p.debut.toString())
        egal("$ou fin", a.getString("fin"), p.fin.toString())
        egal("$ou sur relevé annuel", a.getBoolean("sur_releve_annuel"), p.surReleveAnnuel)
    }

    // -- traductions vers les libellés du serveur --------------------------

    private fun libellePython(m: Methode): String = when (m) {
        Methode.Thermique -> "modèle thermique (DJ normaux)"
        is Methode.ProfilSaisonnier -> "profil saisonnier (${m.nbAnnees} année(s) de référence)"
        is Methode.MoyennePassee -> "moyenne de ${m.nbAnnees} année(s) passée(s)"
        Methode.Prorata -> "prorata temporis (pas d'historique complet)"
        Methode.Aucune -> "linéaire"
    }

    private fun genre(a: Anomalie): String = when (a) {
        is Anomalie.Lacune -> "lacune"
        is Anomalie.IndexFige -> "index_fige"
        is Anomalie.Surconsommation -> if (a.faceAuClimat) "surconsommation_climat" else "surconsommation"
    }

    // -- comparaisons ---------------------------------------------------------

    private fun egal(ou: String, attendu: Any?, obtenu: Any?) {
        compteur++
        if (attendu != obtenu) ecarts += "$ou : Python $attendu, Kotlin $obtenu"
    }

    /** Même ordre de calcul des deux côtés : seul l'arrondi final peut différer. */
    private fun proche(ou: String, attendu: Double?, obtenu: Double?) {
        compteur++
        if (attendu == null || obtenu == null) {
            if (attendu != obtenu) ecarts += "$ou : Python $attendu, Kotlin $obtenu"
            return
        }
        if (abs(attendu - obtenu) > 1e-9 * max(1.0, abs(attendu))) {
            ecarts += "$ou : Python $attendu, Kotlin $obtenu"
        }
    }

    private fun JSONArray.objets() = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.chaines() = (0 until length()).map { getString(it) }
    private fun JSONArray.entiers() = (0 until length()).map { getInt(it) }
    private fun JSONObject.chaineOuNull(cle: String) = if (isNull(cle)) null else getString(cle)
    private fun JSONObject.dateOuNull(cle: String) = chaineOuNull(cle)?.let(LocalDate::parse)
    private fun JSONObject.doubleOuNull(cle: String) = if (isNull(cle)) null else getDouble(cle)
    private fun JSONObject.entierOuNull(cle: String) = if (isNull(cle)) null else getInt(cle)
}
