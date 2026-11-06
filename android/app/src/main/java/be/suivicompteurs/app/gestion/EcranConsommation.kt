package be.suivicompteurs.app.gestion

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import be.suivicompteurs.app.R
import be.suivicompteurs.app.analyse.Analyse
import be.suivicompteurs.app.analyse.Textes
import be.suivicompteurs.app.donnees.ReleveHistorique
import java.time.LocalDate
import kotlinx.coroutines.launch

/**
 * Relevé d'un compteur saisi en consommations : la consommation d'une période
 * (les kWh du mois), plutôt qu'un index. La période part du relevé précédent ;
 * à la première saisie, on en donne le début. [dateInitiale] désigne le relevé
 * à modifier ; sans elle, c'est un nouveau relevé.
 */
@Composable
fun EcranReleveConsommation(
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
    var origine by remember { mutableStateOf<ReleveHistorique?>(null) }
    var unite by remember { mutableStateOf("") }
    var fin by remember { mutableStateOf<String?>(dateInitiale ?: LocalDate.now().toString()) }
    var debut by remember { mutableStateOf<String?>(LocalDate.now().withDayOfMonth(1).toString()) }
    var consommation by remember { mutableStateOf("") }
    var annuel by remember { mutableStateOf(false) }
    var commentaire by remember { mutableStateOf("") }
    var refus by remember { mutableStateOf<Refus?>(null) }
    var confirmer by remember { mutableStateOf(false) }

    LaunchedEffect(compteurId) {
        unite = gestion.compteur(compteurId)?.unite.orEmpty()
        releves = gestion.releves(compteurId)
        origine = releves.firstOrNull { it.date == dateInitiale }
        origine?.let { consommation = ecrireNombre(it.consommation); annuel = it.annuel; commentaire = it.commentaire }
        charge = true
    }

    // La période s'ouvre au relevé précédent ; sans lui, c'est la première saisie.
    val precedent = releves.filter { it.date < (fin ?: "") }.maxByOrNull { it.date }
    val valeur = lireNombre(consommation)?.takeIf { it >= 0 }
    val debutValide = precedent != null || (debut != null && fin != null && debut!! < fin!!)

    fun enregistrer() = portee.launch {
        val cible = fin ?: return@launch
        try {
            gestion.enregistrerReleveConsommation(
                (origine ?: ReleveHistorique(compteurId, cible, 0.0, false)).copy(
                    date = cible, annuel = annuel, commentaire = commentaire.trim().take(200), consommation = valeur,
                ),
                debut = if (precedent == null) debut else null,
                dateAvant = dateInitiale,
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
        if (precedent == null) {
            Text(
                stringResource(R.string.consommation_premiere),
                style = MaterialTheme.typography.bodySmall, color = Couleurs.encre2,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            ChampDate(stringResource(R.string.consommation_debut), debut, { debut = it })
        } else {
            Text(
                stringResource(R.string.consommation_depuis, jour(precedent.date)),
                style = MaterialTheme.typography.bodySmall, color = Couleurs.encre2,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        ChampDate(stringResource(R.string.consommation_fin), fin, { fin = it; refus = null })
        if (!debutValide) {
            Text(stringResource(R.string.consommation_debut_avant_fin), color = Couleurs.critique, style = MaterialTheme.typography.bodySmall)
        }
        refus?.let { Text(messageRefus(it), color = Couleurs.critique, style = MaterialTheme.typography.bodySmall) }
        ChampTexte(stringResource(R.string.consommation_valeur, unite), consommation, { consommation = it }, numerique = true)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = annuel, onCheckedChange = { annuel = it })
            Text(stringResource(R.string.releve_annuel))
        }
        ChampTexte(stringResource(R.string.commentaire), commentaire, { commentaire = it })
        Button(
            enabled = fin != null && valeur != null && debutValide,
            onClick = { enregistrer() },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) { IconeTexte(R.drawable.ic_valider, stringResource(R.string.enregistrer)) }
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
