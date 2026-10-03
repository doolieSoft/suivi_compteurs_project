package be.suivicompteurs.app.analyse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import be.suivicompteurs.app.R
import be.suivicompteurs.app.gestion.Cadre
import be.suivicompteurs.app.gestion.Couleurs
import be.suivicompteurs.app.moteur.AnneeComparee
import be.suivicompteurs.app.moteur.DegresJours
import be.suivicompteurs.app.moteur.Energie
import be.suivicompteurs.app.moteur.Ligne
import be.suivicompteurs.app.moteur.Plage
import be.suivicompteurs.app.moteur.Rejeu
import be.suivicompteurs.app.moteur.anneesRejouables
import be.suivicompteurs.app.moteur.comparerAnnees
import be.suivicompteurs.app.moteur.rejouer
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class Comparees(val maisonId: Long, val maison: String, val ligne: Ligne, val annees: List<AnneeComparee>)

private class Climat(val annuels: List<Pair<Int, Double>>, val normale: Double)

/** Consommation annuelle, brute et à climat normal, puis rigueur des hivers. */
@Composable
fun EcranComparaison(surRetour: () -> Unit, surLigne: (Long, Energie, Plage) -> Unit) {
    val contexte = LocalContext.current
    val textes = remember { Textes(contexte) }
    var comparees by remember { mutableStateOf<List<Comparees>?>(null) }
    var climat by remember { mutableStateOf<Climat?>(null) }

    LaunchedEffect(Unit) {
        val maisons = Analyse(contexte).charger()
        withContext(Dispatchers.Default) {
            val aujourdhui = LocalDate.now()
            comparees = maisons.flatMap { m ->
                val normales = m.normales(aujourdhui)
                m.lignes.map { Comparees(m.maison.id, m.maison.nom, it, comparerAnnees(it, normales)) }
            }
            // La météo de la maison actuelle : c'est elle qu'on compare d'un hiver à l'autre.
            maisons.firstOrNull { it.maison.actuelle && it.djs.isNotEmpty() }?.let { m ->
                val parAnnee = m.djs.keys.groupBy { it.year }.mapValues { it.value.size }
                climat = Climat(
                    // Seules les années presque complètes se comparent à la normale.
                    annuels = DegresJours.annuels(m.djs).filter { (parAnnee[it.key] ?: 0) >= 360 }.toList(),
                    normale = DegresJours.normalAnnuel(m.normales(aujourdhui)),
                )
            }
        }
    }

    Cadre(stringResource(R.string.titre_comparaison), surRetour) {
        val liste = comparees
        if (liste == null) {
            Chargement()
            return@Cadre
        }
        for (c in liste) {
            val retenues = c.annees.filter { it.joursCouverts >= 30 }
            if (retenues.isEmpty()) continue
            val couleur = Couleurs.energie(c.ligne.energie.code)
            Carte("${c.maison} – ${textes.libelle(c.ligne)} (${c.ligne.unite})") {
                GraphiqueBarres(
                    categories = retenues.map { it.annee.toString() + if (it.complete) "" else "*" },
                    groupes = listOfNotNull(
                        GroupeBarres(stringResource(R.string.mesuree), couleur, retenues.map { it.consommation }),
                        if (retenues.any { it.consommationNormalisee != null }) GroupeBarres(
                            stringResource(R.string.climat_normal), couleur.copy(alpha = 0.45f),
                            retenues.map { it.consommationNormalisee },
                        ) else null,
                    ),
                    etiquetteY = { textes.nombre(it) },
                )
                Text(
                    stringResource(R.string.voir_le_detail),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 8.dp).clickable { surLigne(c.maisonId, c.ligne.energie, c.ligne.plage) },
                )
            }
        }
        climat?.takeIf { it.annuels.isNotEmpty() }?.let { cl ->
            Carte(stringResource(R.string.titre_climat)) {
                GraphiqueBarres(
                    categories = cl.annuels.map { it.first.toString() },
                    groupes = listOf(GroupeBarres(stringResource(R.string.degres_jours), Couleurs.primaire, cl.annuels.map { it.second })),
                    etiquetteY = { textes.nombre(it) },
                    reference = stringResource(R.string.normale) to cl.normale,
                )
                Tableau(
                    entetes = listOf(stringResource(R.string.annee), stringResource(R.string.degres_jours), stringResource(R.string.ecart_normale)),
                    lignes = cl.annuels.reversed().map { (annee, dj) ->
                        listOf(annee.toString(), textes.nombre(dj), textes.pourcent(100 * (dj - cl.normale) / cl.normale))
                    },
                )
                Text(stringResource(R.string.climat_explication), style = MaterialTheme.typography.bodySmall, color = Couleurs.encre3)
            }
        }
    }
}

private class Justesse(val maison: String, val rejeu: Rejeu, val unite: String, val ligne: Ligne)

/** Les prévisions d'une année passée, rejouées puis confrontées au réel. */
@Composable
fun EcranJustesse(surRetour: () -> Unit) {
    val contexte = LocalContext.current
    val textes = remember { Textes(contexte) }
    var maisons by remember { mutableStateOf<List<MaisonChargee>?>(null) }
    var annees by remember { mutableStateOf<List<Int>>(emptyList()) }
    var annee by remember { mutableStateOf<Int?>(null) }
    var resultats by remember { mutableStateOf<List<Justesse>?>(null) }

    LaunchedEffect(Unit) {
        val chargees = Analyse(contexte).charger()
        maisons = chargees
        annees = withContext(Dispatchers.Default) { anneesRejouables(chargees.flatMap { it.lignes }) }
        annee = annees.firstOrNull()
    }
    LaunchedEffect(annee) {
        val a = annee ?: return@LaunchedEffect
        resultats = null
        resultats = withContext(Dispatchers.Default) {
            maisons.orEmpty().flatMap { m ->
                rejouer(m.compteurs, m.djs, a).mapNotNull { r ->
                    val ligne = m.ligne(r.energie, r.plage) ?: return@mapNotNull null
                    Justesse(m.maison.nom, r, ligne.unite, ligne)
                }
            }
        }
    }

    Cadre(stringResource(R.string.titre_justesse), surRetour) {
        Text(stringResource(R.string.justesse_explication), style = MaterialTheme.typography.bodySmall, color = Couleurs.encre2)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            annees.forEach { a -> FilterChip(selected = a == annee, onClick = { annee = a }, label = { Text(a.toString()) }) }
        }
        val liste = resultats
        if (liste == null) {
            if (annee != null || maisons == null) Chargement()
            else Text(stringResource(R.string.rien_a_rejouer))
            return@Cadre
        }
        for (j in liste) {
            val r = j.rejeu
            Carte("${j.maison} – ${textes.libelle(j.ligne)}") {
                Text(
                    stringResource(R.string.reel_et_ecart, textes.nombre(r.reel), j.unite, r.ecartMoyenPct?.let { textes.nombre(it, 1) } ?: "—"),
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (r.surReleveAnnuel) stringResource(R.string.periode_annuelle, textes.date(r.borneDebut!!.date), textes.date(r.fin))
                    else stringResource(R.string.periode_civile),
                    style = MaterialTheme.typography.bodySmall, color = Couleurs.encre3,
                )
                listOfNotNull(r.borneDebut, r.borneFin).forEach { b ->
                    val ecart = b.ecartJours
                    Text(
                        when {
                            b.releve == null -> stringResource(R.string.borne_estimee, textes.date(b.date))
                            ecart == 0 -> stringResource(R.string.borne_pile, textes.date(b.releve))
                            else -> stringResource(R.string.borne_ecart, textes.date(b.releve), kotlin.math.abs(ecart!!), textes.nombre(b.volumeEstime, 1), j.unite)
                        },
                        style = MaterialTheme.typography.bodySmall, color = Couleurs.encre3,
                    )
                }
                Tableau(
                    entetes = listOf(stringResource(R.string.au), stringResource(R.string.prevu), stringResource(R.string.ecart), stringResource(R.string.fourchette_court)),
                    lignes = r.points.map { p ->
                        listOf(
                            textes.date(p.coupe),
                            textes.nombre(p.prevision.totalPrevu),
                            p.ecartPct?.let(textes::pourcent) ?: "—",
                            when (p.dansFourchette) { true -> stringResource(R.string.oui); false -> stringResource(R.string.non); null -> "—" },
                        )
                    },
                    poids = listOf(1.4f, 1f, 1f, 0.9f),
                )
            }
        }
    }
}
