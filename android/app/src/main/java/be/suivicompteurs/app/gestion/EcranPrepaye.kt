package be.suivicompteurs.app.gestion

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import be.suivicompteurs.app.Monnaie
import be.suivicompteurs.app.R
import be.suivicompteurs.app.analyse.Analyse
import be.suivicompteurs.app.analyse.Textes
import be.suivicompteurs.app.donnees.AchatLocal
import be.suivicompteurs.app.donnees.ReleveHistorique
import be.suivicompteurs.app.donnees.pourMoteur
import be.suivicompteurs.app.moteur.TOLERANCE_JAUGE
import be.suivicompteurs.app.moteur.achatsEntre
import be.suivicompteurs.app.moteur.consommationPrepayee
import be.suivicompteurs.app.moteur.soldeExcedentaire
import java.time.LocalDate
import kotlinx.coroutines.launch

/** [datesRelevees] : l'ordre avec le relevé ne se pose que ces jours-là. */
@Composable
fun DialogueAchat(
    achat: AchatLocal,
    datesRelevees: Set<String>,
    unite: String,
    cuve: Boolean,
    surFermeture: () -> Unit,
    surEnregistrement: (AchatLocal) -> Unit,
    surSuppression: (() -> Unit)?,
) {
    var date by remember { mutableStateOf<String?>(achat.date) }
    var quantite by remember { mutableStateOf(if (achat.quantite > 0) ecrireNombre(achat.quantite) else "") }
    var montant by remember { mutableStateOf(ecrireNombre(achat.montant)) }
    var tarif by remember { mutableStateOf(ecrireNombre(achat.tarif)) }
    var apresReleve by remember { mutableStateOf(achat.apresReleve) }
    val symbole = Monnaie.symbole(LocalContext.current)
    val kwh = lireNombre(quantite)?.takeIf { it > 0 }
    AlertDialog(
        onDismissRequest = surFermeture,
        title = { Text(stringResource(if (cuve) R.string.livraison else R.string.achat)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ChampDate(stringResource(R.string.date), date, { date = it })
                ChampTexte(stringResource(if (cuve) R.string.livraison_quantite else R.string.achat_quantite, unite), quantite, { quantite = it }, numerique = true)
                ChampTexte(stringResource(R.string.achat_montant, symbole), montant, { montant = it }, numerique = true)
                ChampTexte(stringResource(R.string.achat_tarif, unite, symbole), tarif, { tarif = it }, numerique = true)
                if (date in datesRelevees) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = apresReleve, onCheckedChange = { apresReleve = it })
                        Text(stringResource(if (cuve) R.string.livraison_apres_releve else R.string.achat_apres_releve))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = date != null && kwh != null,
                onClick = {
                    surEnregistrement(
                        achat.copy(
                            date = date!!, quantite = kwh!!, montant = lireNombre(montant), tarif = lireNombre(tarif),
                            apresReleve = apresReleve && date in datesRelevees,
                        )
                    )
                },
            ) { Text(stringResource(R.string.enregistrer)) }
        },
        dismissButton = {
            Row {
                surSuppression?.let { TextButton(onClick = it) { Text(stringResource(R.string.supprimer), color = Couleurs.critique) } }
                TextButton(onClick = surFermeture) { Text(stringResource(R.string.annuler)) }
            }
        },
    )
}

/**
 * Relevé d'un compteur prépayé ou d'une cuve : on lit le solde ou le niveau,
 * et l'application en déduit ce qui a été consommé. [dateInitiale] désigne le
 * relevé à modifier ; sans elle, c'est un nouveau relevé.
 */
@Composable
fun EcranReleveSolde(
    gestion: Gestion,
    compteurId: Long,
    dateInitiale: String?,
    surRetour: () -> Unit,
) {
    val portee = rememberCoroutineScope()
    val contexte = LocalContext.current
    var impact by remember { mutableStateOf<String?>(null) }
    var charge by remember { mutableStateOf(false) }
    var releves by remember { mutableStateOf<List<ReleveHistorique>>(emptyList()) }
    var achats by remember { mutableStateOf<List<AchatLocal>>(emptyList()) }
    var origine by remember { mutableStateOf<ReleveHistorique?>(null) }
    var date by remember { mutableStateOf<String?>(dateInitiale ?: LocalDate.now().toString()) }
    var solde by remember { mutableStateOf("") }
    var annuel by remember { mutableStateOf(false) }
    var commentaire by remember { mutableStateOf("") }
    var refus by remember { mutableStateOf<Refus?>(null) }
    var achatEdite by remember { mutableStateOf<AchatLocal?>(null) }
    var excedent by remember { mutableStateOf<Double?>(null) }
    var confirmer by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }
    var unite by remember { mutableStateOf("") }
    var cuve by remember { mutableStateOf(false) }

    LaunchedEffect(compteurId, version) {
        gestion.compteur(compteurId)?.let { unite = it.unite; cuve = it.energie == "MAZ" }
        releves = gestion.releves(compteurId)
        achats = gestion.achats(compteurId)
        if (!charge) {
            origine = releves.firstOrNull { it.date == dateInitiale }
            origine?.let { solde = ecrireNombre(it.solde); annuel = it.annuel; commentaire = it.commentaire }
            charge = true
        }
    }

    val jour = date?.let(LocalDate::parse)
    val precedent = releves.filter { it.solde != null && it.date < (date ?: "") }.maxByOrNull { it.date }
    val depuis = precedent?.let { LocalDate.parse(it.date) }
    val moteur = achats.map { it.pourMoteur() }
    val achetes = achatsEntre(moteur, depuis, jour)
    val lu = lireNombre(solde)?.takeIf { it >= 0 }

    fun enregistrer() = portee.launch {
        val cible = date ?: return@launch
        try {
            gestion.enregistrerReleveSolde(
                (origine ?: ReleveHistorique(compteurId, date!!, 0.0, false)).copy(
                    date = date!!, annuel = annuel, commentaire = commentaire.trim().take(200), solde = lu,
                ),
                dateInitiale,
            )
            impact = dateInitiale.takeIf { it == null }?.let {
                runCatching { Analyse(contexte).impactReleve(compteurId, LocalDate.parse(cible)) }.getOrNull()
            }?.let { Textes(contexte).impact(it) }
            if (impact == null) surRetour()
        } catch (e: Refus) {
            refus = e
        }
    }

    impact?.let { DialogueImpact(it, surRetour) }

    Cadre(
        titre = stringResource(R.string.releve),
        surRetour = surRetour,
        surSuppression = if (dateInitiale != null) ({ confirmer = true }) else null,
    ) {
        if (!charge) return@Cadre
        ChampDate(stringResource(R.string.date_du_releve), date, { date = it; refus = null })
        refus?.let { Text(messageRefus(it), color = Couleurs.critique, style = MaterialTheme.typography.bodySmall) }
        ChampTexte(stringResource(if (cuve) R.string.niveau_lu else R.string.solde_lu, unite), solde, { solde = it }, numerique = true)
        val texteAide = MaterialTheme.typography.bodySmall
        precedent?.solde?.let {
            Text(stringResource(if (cuve) R.string.niveau_precedent else R.string.solde_precedent, afficherNombre(it), unite), style = texteAide, color = Couleurs.encre2)
            Text(stringResource(if (cuve) R.string.livraisons_periode else R.string.achats_periode, afficherNombre(achetes), unite), style = texteAide, color = Couleurs.encre2)
        }
        Text(
            when {
                precedent == null -> stringResource(R.string.solde_premier_releve)
                lu == null -> ""
                else -> stringResource(
                    R.string.solde_conso_periode,
                    afficherNombre(consommationPrepayee(precedent.solde!!, achetes, lu)),
                    unite,
                )
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        OutlinedButton(
            onClick = { achatEdite = AchatLocal(0, compteurId, date ?: LocalDate.now().toString(), 0.0) },
            Modifier.fillMaxWidth(),
        ) { IconeTexte(R.drawable.ic_ajouter, stringResource(if (cuve) R.string.livraison_ajouter else R.string.achat_ajouter)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = annuel, onCheckedChange = { annuel = it })
            Text(stringResource(R.string.releve_annuel))
        }
        ChampTexte(stringResource(R.string.commentaire), commentaire, { commentaire = it })
        Button(
            enabled = date != null && lu != null,
            onClick = {
                val trop = precedent?.solde?.let {
                    soldeExcedentaire(it, achetes, lu!!, if (cuve) TOLERANCE_JAUGE else 0.0)
                }
                if (trop != null) excedent = trop else enregistrer()
            },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) { IconeTexte(R.drawable.ic_valider, stringResource(R.string.enregistrer)) }
    }

    achatEdite?.let { a ->
        DialogueAchat(
            a,
            datesRelevees = releves.map { it.date }.toSet(),
            unite = unite,
            cuve = cuve,
            surFermeture = { achatEdite = null },
            surEnregistrement = { nouveau ->
                portee.launch { gestion.enregistrerAchat(nouveau); achatEdite = null; version++ }
            },
            surSuppression = null,
        )
    }
    excedent?.let { trop ->
        AlertDialog(
            onDismissRequest = { excedent = null },
            title = { Text(stringResource(if (cuve) R.string.livraison_oubliee_titre else R.string.achat_oublie_titre)) },
            text = { Text(stringResource(if (cuve) R.string.livraison_oubliee_question else R.string.achat_oublie_question, afficherNombre(trop), unite)) },
            confirmButton = {
                TextButton(onClick = {
                    excedent = null
                    achatEdite = AchatLocal(0, compteurId, date!!, trop)
                }) { Text(stringResource(if (cuve) R.string.livraison_oubliee_oui else R.string.achat_oublie_oui)) }
            },
            dismissButton = { TextButton(onClick = { excedent = null }) { Text(stringResource(R.string.corriger)) } },
        )
    }
    if (confirmer) {
        ConfirmerSuppression(
            stringResource(R.string.supprimer_releve_confirmation),
            surConfirmation = {
                portee.launch { gestion.supprimerReleve(compteurId, dateInitiale!!); confirmer = false; surRetour() }
            },
            surAbandon = { confirmer = false },
        )
    }
}
