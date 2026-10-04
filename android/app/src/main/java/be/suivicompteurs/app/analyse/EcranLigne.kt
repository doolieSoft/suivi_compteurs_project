package be.suivicompteurs.app.analyse

import androidx.compose.material3.SnackbarHostState
import be.suivicompteurs.app.gestion.iconeEnergie
import be.suivicompteurs.app.gestion.BoutonIcone
import be.suivicompteurs.app.gestion.IconeTexte
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import be.suivicompteurs.app.Monnaie
import be.suivicompteurs.app.R
import be.suivicompteurs.app.donnees.EvenementLocal
import be.suivicompteurs.app.donnees.PointVerifie
import be.suivicompteurs.app.gestion.Cadre
import be.suivicompteurs.app.gestion.ChampDate
import be.suivicompteurs.app.gestion.ChampTexte
import be.suivicompteurs.app.gestion.Couleurs
import be.suivicompteurs.app.gestion.Gestion
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Tout ce que montre le détail d'une énergie, calculé d'un bloc. */
private class DetailLigne(
    val ligne: Ligne,
    val prevision: Prevision?,
    val comparaison: ComparaisonGlissante?,
    val annees: List<AnneeComparee>,
    val couts: List<CoutAnnuel>,
    /** Points encore à vérifier. */
    val anomalies: List<Anomalie>,
    /** Points déjà vérifiés et résolus : l'historique de la ligne. */
    val verifies: List<PointVerifie>,
    val evenements: List<EvenementLocal>,
)

/**
 * L'année en cours dans la couleur de l'énergie, l'année passée — la
 * comparaison qu'on fait d'abord — dans la même couleur atténuée, les
 * précédentes en gris discret.
 */
private fun teinte(base: Color, rang: Int, total: Int): Color = when (rang) {
    total - 1 -> base
    total - 2 -> base.copy(alpha = 0.45f)
    else -> Couleurs.encre3.copy(alpha = 0.3f)
}

@Composable
fun EcranLigne(maisonId: Long, energie: Energie, plage: Plage, surRetour: () -> Unit) {
    val contexte = LocalContext.current
    val textes = remember { Textes(contexte) }
    var detail by remember { mutableStateOf<DetailLigne?>(null) }
    // Un point résolu ou rouvert : on recalcule.
    var version by remember { mutableIntStateOf(0) }
    val portee = rememberCoroutineScope()
    // Confirmation brève après un enregistrement : l'écran ne change pas.
    val messages = remember { SnackbarHostState() }
    val enregistre = stringResource(R.string.enregistre)
    val gestion = remember { Gestion(contexte) }
    // Point en cours de résolution, ou point vérifié en cours de modification.
    var aResoudre by remember { mutableStateOf<PointVerifie?>(null) }

    LaunchedEffect(maisonId, energie, plage, version) {
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
                anomalies = aVerifier(energie, plage, detecterAnomalies(ligne), maison.pointsVerifies),
                verifies = maison.pointsVerifies.filter { it.energie == energie.code && it.plage == plage.code }
                    .sortedByDescending { it.resoluLe },
                evenements = maison.evenements.filter { it.energie.isEmpty() || it.energie == energie.code },
            )
        }
    }

    val d = detail
    aResoudre?.let { point ->
        DialoguePointVerifie(
            point,
            surFermeture = { aResoudre = null },
            surEnregistrement = { p ->
                portee.launch { gestion.enregistrerPointVerifie(p); aResoudre = null; version++; portee.launch { messages.showSnackbar(enregistre) } }
            },
            // Un point déjà vérifié peut être rouvert : il redevient à vérifier.
            surReouverture = if (point.id != 0L) ({
                portee.launch { gestion.supprimerPointVerifie(point.id); aResoudre = null; version++ }
            }) else null,
        )
    }

    Cadre(titre = d?.let { textes.libelle(it.ligne) } ?: "", surRetour = surRetour, messages = messages) {
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
            Carte(stringResource(R.string.graphique_cumul), icone = iconeEnergie(ligne.energie.code), couleurIcone = couleur) {
                val mois = DateTimeFormatter.ofPattern("MMM")
                // Les relevés de l'année en cours, sur sa courbe : entre deux, la
                // consommation est répartie (selon le froid, ou uniformément),
                // d'où des segments droits qu'il faut pouvoir situer.
                val enCours = tracees.last()
                val cumulEnCours = ligne.serie.cumulAnnuel(enCours).toMap()
                val datesReleves = ligne.compteurs.flatMap { c -> c.releves.map { it.date } }
                    .filter { it.year == enCours }.distinct().sorted()
                val pointsReleves = datesReleves.mapNotNull { d -> cumulEnCours[d]?.let { d.dayOfYear.toDouble() to it } }
                GraphiqueXY(
                    series = tracees.mapIndexed { rang, annee ->
                        Serie(
                            nom = annee.toString(),
                            couleur = teinte(couleur, rang, tracees.size),
                            points = ligne.serie.cumulAnnuel(annee).map { (j, v) -> j.dayOfYear.toDouble() to v },
                            epaisseur = when (rang) {
                                tracees.size - 1 -> 4f
                                tracees.size - 2 -> 3.5f
                                else -> 2f
                            },
                        )
                    } + listOfNotNull(
                        pointsReleves.takeIf { it.isNotEmpty() }?.let {
                            Serie(stringResource(R.string.releves_annee, enCours), Couleurs.encre, it, Trace.POINTS)
                        },
                    ),
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
            Carte(stringResource(R.string.couts), icone = R.drawable.ic_tarif) {
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
            Carte(
                pluralStringResource(R.plurals.points_a_verifier, d.anomalies.size, d.anomalies.size).removePrefix("⚠ "),
                icone = R.drawable.ic_attention, couleurIcone = Couleurs.critique,
            ) {
                d.anomalies.sortedByDescending { it.debut }.forEach { a ->
                    Text("• " + textes.anomalie(a), style = MaterialTheme.typography.bodySmall, color = Couleurs.encre2)
                    TextButton(onClick = {
                        aResoudre = PointVerifie(
                            maisonId = maisonId, energie = energie.code, plage = plage.code, genre = a.genre,
                            debut = a.debut.toString(), fin = a.fin.toString(), description = textes.anomalie(a),
                            resoluLe = LocalDate.now().toString(), note = "",
                        )
                    }) { IconeTexte(R.drawable.ic_verifie, stringResource(R.string.marquer_resolu)) }
                }
            }
        }
        // L'historique : ce qui a été vérifié, quand, et ce qui a été fait.
        if (d.verifies.isNotEmpty()) {
            Carte(stringResource(R.string.points_verifies, d.verifies.size), icone = R.drawable.ic_verifie, couleurIcone = Couleurs.bien) {
                d.verifies.forEach { p ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text("✓ " + p.description, style = MaterialTheme.typography.bodySmall, color = Couleurs.encre2)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                listOfNotNull(
                                    stringResource(R.string.resolu_le, textes.date(LocalDate.parse(p.resoluLe))),
                                    p.note.ifEmpty { null },
                                ).joinToString(" — "),
                                style = MaterialTheme.typography.bodySmall, color = Couleurs.encre3,
                                modifier = Modifier.weight(1f),
                            )
                            BoutonIcone(stringResource(R.string.modifier), R.drawable.ic_modifier, { aResoudre = p })
                        }
                    }
                }
            }
        }
        if (d.evenements.isNotEmpty()) {
            Carte(stringResource(R.string.evenements), icone = R.drawable.ic_evenement) {
                d.evenements.sortedByDescending { it.date }.forEach {
                    Text("${textes.date(LocalDate.parse(it.date))} · ${it.libelle}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** Déclarer un point vérifié et résolu : la date, et ce qui a été fait. */
@Composable
private fun DialoguePointVerifie(
    point: PointVerifie,
    surFermeture: () -> Unit,
    surEnregistrement: (PointVerifie) -> Unit,
    surReouverture: (() -> Unit)?,
) {
    var resoluLe by remember { mutableStateOf<String?>(point.resoluLe) }
    var note by remember { mutableStateOf(point.note) }
    AlertDialog(
        onDismissRequest = surFermeture,
        title = { Text(stringResource(R.string.point_resolu)) },
        text = {
            Column {
                Text(point.description, style = MaterialTheme.typography.bodySmall, color = Couleurs.encre2)
                ChampDate(stringResource(R.string.resolu_le_champ), resoluLe, { resoluLe = it })
                ChampTexte(stringResource(R.string.ce_qui_a_ete_fait), note, { note = it }, lignes = 3)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                surEnregistrement(point.copy(resoluLe = resoluLe ?: point.resoluLe, note = note))
            }) { Text(stringResource(R.string.enregistrer)) }
        },
        dismissButton = {
            Row {
                surReouverture?.let { BoutonIcone(stringResource(R.string.rouvrir), R.drawable.ic_rouvrir, it, couleur = Couleurs.critique) }
                TextButton(onClick = surFermeture) { Text(stringResource(R.string.annuler)) }
            }
        },
    )
}
