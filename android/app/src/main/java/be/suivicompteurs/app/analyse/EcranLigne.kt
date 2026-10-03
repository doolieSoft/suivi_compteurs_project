package be.suivicompteurs.app.analyse

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import be.suivicompteurs.app.Monnaie
import be.suivicompteurs.app.R
import be.suivicompteurs.app.donnees.EvenementLocal
import be.suivicompteurs.app.gestion.Cadre
import be.suivicompteurs.app.gestion.Couleurs
import be.suivicompteurs.app.moteur.AnneeComparee
import be.suivicompteurs.app.moteur.Anomalie
import be.suivicompteurs.app.moteur.ComparaisonGlissante
import be.suivicompteurs.app.moteur.CoutAnnuel
import be.suivicompteurs.app.moteur.Energie
import be.suivicompteurs.app.moteur.Ligne
import be.suivicompteurs.app.moteur.Plage
import be.suivicompteurs.app.moteur.Prevision
import be.suivicompteurs.app.moteur.comparerAAnneePrecedente
import be.suivicompteurs.app.moteur.comparerAnnees
import be.suivicompteurs.app.moteur.coutsParAnnee
import be.suivicompteurs.app.moteur.detecterAnomalies
import be.suivicompteurs.app.moteur.periodes
import be.suivicompteurs.app.moteur.prevoir
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Tout ce que montre le détail d'une énergie, calculé d'un bloc. */
private class DetailLigne(
    val ligne: Ligne,
    val prevision: Prevision?,
    val comparaison: ComparaisonGlissante?,
    val annees: List<AnneeComparee>,
    val couts: List<CoutAnnuel>,
    val anomalies: List<Anomalie>,
    val evenements: List<EvenementLocal>,
)

/** Teintes des années passées : de plus en plus pâles en remontant le temps. */
private fun teinte(base: Color, rang: Int, total: Int): Color =
    if (rang == total - 1) base else Couleurs.encre3.copy(alpha = 0.25f + 0.6f * rang / total.coerceAtLeast(1))

@Composable
fun EcranLigne(maisonId: Long, energie: Energie, plage: Plage, surRetour: () -> Unit) {
    val contexte = LocalContext.current
    val textes = remember { Textes(contexte) }
    var detail by remember { mutableStateOf<DetailLigne?>(null) }

    LaunchedEffect(maisonId, energie, plage) {
        val maison = Analyse(contexte).charger().firstOrNull { it.maison.id == maisonId } ?: return@LaunchedEffect
        detail = withContext(Dispatchers.Default) {
            val ligne = maison.ligne(energie, plage) ?: return@withContext null
            val aujourdhui = LocalDate.now()
            val normales = maison.normales(aujourdhui)
            DetailLigne(
                ligne = ligne,
                prevision = prevoir(ligne, aujourdhui, normales),
                comparaison = comparerAAnneePrecedente(ligne, aujourdhui),
                annees = comparerAnnees(ligne, normales),
                couts = coutsParAnnee(ligne, maison.tarifs),
                anomalies = detecterAnomalies(ligne),
                evenements = maison.evenements.filter { it.energie.isEmpty() || it.energie == energie.code },
            )
        }
    }

    val d = detail
    Cadre(titre = d?.let { textes.libelle(it.ligne) } ?: "", surRetour = surRetour) {
        if (d == null) {
            Chargement()
            return@Cadre
        }
        val ligne = d.ligne
        val unite = ligne.unite
        val couleur = Couleurs.energie(ligne.energie.code)
        val nombre = { v: Double -> textes.nombre(v) }

        // --- prévision et évolution -----------------------------------------
        d.prevision?.let { p ->
            Tuile(
                titre = stringResource(R.string.prevision_annee, p.annee),
                valeur = stringResource(R.string.valeur_unite, nombre(p.totalPrevu), unite),
                details = listOfNotNull(
                    stringResource(R.string.detail_realise, nombre(p.realise), unite, p.joursRealises, nombre(p.estimeRestant), p.joursRestants),
                    if (p.surReleveAnnuel) stringResource(R.string.periode_releve_annuel, textes.date(p.borneDepart), textes.date(p.fin)) else null,
                    p.borneBasse?.let { stringResource(R.string.fourchette, nombre(it), nombre(p.borneHaute!!)) },
                    stringResource(R.string.methode_employee, textes.methode(p.methode)),
                ),
            )
            p.evolutionPct?.let { evo ->
                Tuile(
                    titre = stringResource(R.string.evolution_vs_titre, p.referenceAnnee ?: 0),
                    valeur = textes.pourcent(evo),
                    couleurValeur = when { evo >= 1 -> Couleurs.critique; evo <= -1 -> Couleurs.bien; else -> Couleurs.encre2 },
                    details = listOfNotNull(
                        stringResource(R.string.reference_valeur, p.referenceAnnee ?: 0, nombre(p.reference ?: 0.0), unite),
                        p.evolutionNormaliseePct?.let { stringResource(R.string.evolution_climat_egal, textes.pourcent(it)) },
                    ),
                )
            }
        }
        d.comparaison?.let { c ->
            Tuile(
                titre = stringResource(R.string.depuis_janvier, textes.date(c.jusqua)),
                valeur = stringResource(R.string.valeur_unite, nombre(c.valeur), unite),
                details = listOfNotNull(
                    stringResource(R.string.an_dernier_a_date, nombre(c.valeurPrecedente), unite, c.evolutionPct?.let(textes::pourcent) ?: "—"),
                    c.evolutionDjPct?.takeIf { ligne.thermosensible }?.let { stringResource(R.string.climat_vs_an_dernier, textes.pourcent(it)) },
                ),
            )
        }

        // --- cumul depuis le 1er janvier, une courbe par année ---------------
        val tracees = d.annees.filter { it.joursCouverts >= 30 }.map { it.annee }.takeLast(6)
        if (tracees.isNotEmpty()) {
            Carte(stringResource(R.string.graphique_cumul)) {
                val mois = DateTimeFormatter.ofPattern("MMM")
                GraphiqueXY(
                    series = tracees.mapIndexed { rang, annee ->
                        Serie(
                            nom = annee.toString(),
                            couleur = teinte(couleur, rang, tracees.size),
                            points = ligne.serie.cumulAnnuel(annee).map { (j, v) -> j.dayOfYear.toDouble() to v },
                            epaisseur = if (rang == tracees.size - 1) 4f else 2.5f,
                        )
                    },
                    etiquetteX = { LocalDate.ofYearDay(2001, it.toInt().coerceIn(1, 365)).format(mois) },
                    etiquetteY = nombre,
                    xMin = 1.0, xMax = 366.0,
                    graduationsX = listOf(1, 60, 121, 182, 244, 305).map { it.toDouble() },
                )
            }
        }

        // --- consommation mensuelle -------------------------------------------
        val parMois = ligne.serie.parMois().entries.toList().takeLast(36)
        if (parMois.isNotEmpty()) {
            Carte(stringResource(R.string.graphique_mensuel, unite)) {
                val format = DateTimeFormatter.ofPattern("MMM yy")
                GraphiqueBarres(
                    categories = parMois.map { YearMonth.of(it.key.first, it.key.second).format(format) },
                    groupes = listOf(GroupeBarres(textes.energie(ligne.energie), couleur, parMois.map { it.value })),
                    etiquetteY = nombre,
                )
            }
        }

        // --- signature énergétique ----------------------------------------------
        if (ligne.thermosensible) {
            val djs = ligne.serie.djs
            val points = periodes(ligne.compteurs).mapNotNull { p ->
                val jours = p.jours.toList()
                if (p.volume <= 0 || !jours.all { it in djs }) null
                else jours.sumOf { djs.getValue(it) } / p.nbJours to p.volumeJournalier
            }
            if (points.isNotEmpty()) {
                val m = ligne.serie.modele
                Carte(stringResource(R.string.graphique_signature)) {
                    val xs = points.map { it.first }
                    GraphiqueXY(
                        series = listOfNotNull(
                            Serie(stringResource(R.string.periodes_entre_releves), couleur, points, Trace.POINTS),
                            if (m.fiable) Serie(
                                stringResource(R.string.modele_ajuste),
                                Couleurs.encre2,
                                listOf(xs.min() to m.attendu(xs.min()), xs.max() to m.attendu(xs.max())),
                            ) else null,
                        ),
                        etiquetteX = { textes.nombre(it, 1) },
                        etiquetteY = { textes.nombre(it, 2) },
                    )
                    Text(
                        if (m.fiable) stringResource(
                            R.string.modele_resume, textes.nombre(m.base, 2), textes.nombre(m.k, 3), textes.nombre(m.r2, 2),
                            m.partChauffagePct?.let { textes.nombre(it) } ?: "—",
                        ) else stringResource(R.string.modele_non_fiable),
                        style = MaterialTheme.typography.bodySmall, color = Couleurs.encre3,
                    )
                }
            }
        }

        // --- années comparées -------------------------------------------------
        if (d.annees.isNotEmpty()) {
            Carte(stringResource(R.string.par_annee)) {
                Tableau(
                    entetes = listOfNotNull(
                        stringResource(R.string.annee), stringResource(R.string.consommation_unite, unite),
                        stringResource(R.string.jours), stringResource(R.string.degres_jours_court),
                        if (ligne.thermosensible) stringResource(R.string.climat_normal) else null,
                    ),
                    lignes = d.annees.reversed().map { a ->
                        listOfNotNull(
                            a.annee.toString() + if (a.complete) "" else " *",
                            nombre(a.consommation),
                            a.joursCouverts.toString(),
                            nombre(a.djReel),
                            if (ligne.thermosensible) a.consommationNormalisee?.let(nombre) ?: "—" else null,
                        )
                    },
                    poids = if (ligne.thermosensible) listOf(1.1f, 1.3f, 0.8f, 1f, 1.2f) else listOf(1.1f, 1.3f, 0.8f, 1f),
                )
                Text(stringResource(R.string.annee_incomplete), style = MaterialTheme.typography.bodySmall, color = Couleurs.encre3)
            }
        }

        // --- coûts ------------------------------------------------------------
        if (d.couts.isNotEmpty()) {
            Carte(stringResource(R.string.couts)) {
                Tableau(
                    entetes = listOf(
                        stringResource(R.string.annee), stringResource(R.string.cout_variable),
                        stringResource(R.string.cout_abonnement), stringResource(R.string.cout_total),
                    ),
                    lignes = d.couts.reversed().map { c ->
                        listOf(
                            c.annee.toString(),
                            Monnaie.formater(contexte, c.coutVariable),
                            Monnaie.formater(contexte, c.coutAbonnement),
                            Monnaie.formater(contexte, c.total),
                        )
                    },
                )
            }
        }

        // --- index relevés ------------------------------------------------------
        Carte(stringResource(R.string.graphique_index, unite)) {
            // Une graduation au 1er janvier de chaque année (une sur deux, trois…
            // sur un long historique), plutôt que des jours « ronds » sans sens.
            val dates = ligne.compteurs.flatMap { c -> c.releves.map { it.date } }
            val premiereAnnee = (dates.minOrNull()?.year ?: 2000) + 1
            val derniereAnnee = dates.maxOrNull()?.year ?: premiereAnnee
            val pasAnnees = ((derniereAnnee - premiereAnnee) / 6 + 1).coerceAtLeast(1)
            val graduationsAnnees = (premiereAnnee..derniereAnnee step pasAnnees)
                .map { LocalDate.of(it, 1, 1).toEpochDay().toDouble() }
            GraphiqueXY(
                graduationsX = graduationsAnnees.ifEmpty { null },
                series = ligne.compteurs.mapIndexed { i, c ->
                    Serie(
                        nom = stringResource(R.string.compteur_n, i + 1),
                        couleur = if (i == ligne.compteurs.size - 1) couleur else Couleurs.encre3,
                        points = c.releves.map { it.date.toEpochDay().toDouble() to it.index },
                    )
                },
                etiquetteX = { LocalDate.ofEpochDay(it.toLong()).year.toString() },
                etiquetteY = nombre,
                depuisZero = false,
            )
        }

        // --- points à vérifier et événements -----------------------------------
        if (d.anomalies.isNotEmpty()) {
            Carte(pluralStringResource(R.plurals.points_a_verifier, d.anomalies.size, d.anomalies.size)) {
                d.anomalies.sortedByDescending { it.debut }.forEach {
                    Text("• " + textes.anomalie(it), style = MaterialTheme.typography.bodySmall, color = Couleurs.encre2)
                }
            }
        }
        if (d.evenements.isNotEmpty()) {
            Carte(stringResource(R.string.evenements)) {
                d.evenements.sortedByDescending { it.date }.forEach {
                    Text("${textes.date(LocalDate.parse(it.date))} · ${it.libelle}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
