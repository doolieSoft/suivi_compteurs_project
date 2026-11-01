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
import androidx.compose.runtime.mutableStateMapOf
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
import be.suivicompteurs.app.donnees.RadiateurLocal
import be.suivicompteurs.app.donnees.ReleveHistorique
import be.suivicompteurs.app.donnees.ReleveRadiateur
import be.suivicompteurs.app.moteur.Radiateur
import be.suivicompteurs.app.moteur.ecartPondere
import be.suivicompteurs.app.moteur.radiateursEnBaisse
import java.time.LocalDate
import kotlinx.coroutines.launch

@Composable
fun DialogueRadiateur(
    radiateur: RadiateurLocal,
    surFermeture: () -> Unit,
    surEnregistrement: (RadiateurLocal) -> Unit,
    surRetrait: (() -> Unit)?,
) {
    var nom by remember { mutableStateOf(radiateur.nom) }
    var coefficient by remember { mutableStateOf(ecrireNombre(radiateur.coefficient)) }
    val valeur = lireNombre(coefficient)?.takeIf { it > 0 }
    AlertDialog(
        onDismissRequest = surFermeture,
        title = { Text(stringResource(R.string.radiateur)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ChampTexte(stringResource(R.string.radiateur_nom), nom, { nom = it })
                ChampTexte(stringResource(R.string.radiateur_coefficient), coefficient, { coefficient = it }, numerique = true)
                Text(
                    stringResource(R.string.radiateur_coefficient_astuce),
                    style = MaterialTheme.typography.bodySmall, color = Couleurs.encre2,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = nom.isNotBlank() && valeur != null,
                onClick = { surEnregistrement(radiateur.copy(nom = nom, coefficient = valeur!!)) },
            ) { Text(stringResource(R.string.enregistrer)) }
        },
        dismissButton = {
            Row {
                surRetrait?.let { TextButton(onClick = it) { Text(stringResource(R.string.retirer), color = Couleurs.critique) } }
                TextButton(onClick = surFermeture) { Text(stringResource(R.string.annuler)) }
            }
        },
    )
}

/**
 * Relevé d'un compteur de type répartiteur : on tape ce qu'affiche chaque
 * boîtier, et l'application en déduit l'index cumulé. [dateInitiale] désigne
 * le relevé à modifier ; sans elle, c'est un nouveau relevé.
 */
@Composable
fun EcranReleveRadiateurs(
    gestion: Gestion,
    compteurId: Long,
    dateInitiale: String?,
    surRetour: () -> Unit,
    surCompteur: () -> Unit,
) {
    val portee = rememberCoroutineScope()
    val contexte = LocalContext.current
    var impact by remember { mutableStateOf<String?>(null) }
    var charge by remember { mutableStateOf(false) }
    var radiateurs by remember { mutableStateOf<List<RadiateurLocal>>(emptyList()) }
    var historique by remember { mutableStateOf<List<ReleveRadiateur>>(emptyList()) }
    var origine by remember { mutableStateOf<ReleveHistorique?>(null) }
    var date by remember { mutableStateOf<String?>(dateInitiale ?: LocalDate.now().toString()) }
    var annuel by remember { mutableStateOf(false) }
    var commentaire by remember { mutableStateOf("") }
    val saisis = remember { mutableStateMapOf<Long, String>() }
    var refus by remember { mutableStateOf<Refus?>(null) }
    var incomplet by remember { mutableStateOf(false) }
    var enBaisse by remember { mutableStateOf<List<RadiateurLocal>>(emptyList()) }
    var confirmer by remember { mutableStateOf(false) }

    LaunchedEffect(compteurId) {
        historique = gestion.chiffresRadiateurs(compteurId)
        val duReleve = historique.filter { it.date == dateInitiale }
        // Un radiateur retiré reste modifiable sur les relevés où il figure.
        radiateurs = gestion.radiateurs(compteurId).filter { r ->
            r.dateRetrait == null || duReleve.any { it.radiateurId == r.id }
        }
        duReleve.forEach { saisis[it.radiateurId] = ecrireNombre(it.unites) }
        origine = gestion.releves(compteurId).firstOrNull { it.date == dateInitiale }
        origine?.let { annuel = it.annuel; commentaire = it.commentaire }
        charge = true
    }

    // Chaque chiffre se compare au dernier connu avant cette date.
    val precedents = historique.filter { it.date < (date ?: "") }.associate { it.radiateurId to it.unites }
    val actuels = saisis.mapNotNull { (id, texte) -> lireNombre(texte)?.takeIf { it >= 0 }?.let { id to it } }.toMap()
    val coefficients = radiateurs.map { Radiateur(it.id, it.coefficient) }

    fun enregistrer() = portee.launch {
        val cible = date ?: return@launch
        try {
            gestion.enregistrerReleveRadiateurs(
                (origine ?: ReleveHistorique(compteurId, cible, 0.0, false)).copy(
                    date = cible, annuel = annuel, commentaire = commentaire.trim().take(200),
                ),
                actuels,
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
        if (radiateurs.isEmpty()) {
            Text(
                stringResource(R.string.radiateurs_astuce),
                style = MaterialTheme.typography.bodyMedium, color = Couleurs.encre2,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            OutlinedButton(onClick = surCompteur, Modifier.fillMaxWidth()) {
                IconeTexte(R.drawable.ic_repartiteur, stringResource(R.string.radiateurs_gerer))
            }
            return@Cadre
        }
        ChampDate(stringResource(R.string.date_du_releve), date, { date = it; refus = null })
        refus?.let { Text(messageRefus(it), color = Couleurs.critique, style = MaterialTheme.typography.bodySmall) }
        radiateurs.forEach { r ->
            val texte = saisis[r.id].orEmpty()
            ChampTexte(
                stringResource(R.string.radiateur_chiffre, r.nom, afficherNombre(r.coefficient)),
                texte,
                { saisis[r.id] = it; incomplet = false },
                numerique = true,
                erreur = if (incomplet && r.id !in actuels) stringResource(R.string.radiateur_chiffre_obligatoire) else null,
            )
            precedents[r.id]?.let {
                Text(
                    stringResource(R.string.radiateur_chiffre_precedent, afficherNombre(it)),
                    style = MaterialTheme.typography.bodySmall, color = Couleurs.encre2,
                )
            }
        }
        Text(
            if (precedents.isEmpty()) stringResource(R.string.radiateurs_premier_releve)
            else stringResource(R.string.radiateurs_periode, afficherNombre(ecartPondere(coefficients, precedents, actuels))),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = annuel, onCheckedChange = { annuel = it })
            Text(stringResource(R.string.releve_annuel))
        }
        ChampTexte(stringResource(R.string.commentaire), commentaire, { commentaire = it })
        Button(
            enabled = date != null,
            onClick = {
                if (radiateurs.any { it.id !in actuels }) {
                    incomplet = true
                    return@Button
                }
                val baisses = radiateursEnBaisse(precedents, actuels)
                enBaisse = radiateurs.filter { it.id in baisses }
                if (enBaisse.isEmpty()) enregistrer()
            },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) { IconeTexte(R.drawable.ic_valider, stringResource(R.string.enregistrer)) }
    }

    if (enBaisse.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { enBaisse = emptyList() },
            title = { Text(stringResource(R.string.remise_a_zero_titre)) },
            text = {
                Text(stringResource(R.string.remise_a_zero_question, enBaisse.joinToString(", ") { it.nom }))
            },
            confirmButton = {
                TextButton(onClick = { enBaisse = emptyList(); enregistrer() }) {
                    Text(stringResource(R.string.remise_a_zero_oui))
                }
            },
            dismissButton = {
                TextButton(onClick = { enBaisse = emptyList() }) { Text(stringResource(R.string.corriger)) }
            },
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
