package be.suivicompteurs.app.gestion

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import be.suivicompteurs.app.R
import be.suivicompteurs.app.donnees.AchatLocal
import be.suivicompteurs.app.donnees.CompteurHistorique
import be.suivicompteurs.app.donnees.RadiateurLocal
import be.suivicompteurs.app.donnees.ReleveHistorique
import java.time.LocalDate
import kotlinx.coroutines.launch

@Composable
fun EcranCompteur(
    gestion: Gestion,
    id: Long,
    maisonId: Long,
    surRetour: () -> Unit,
    surCompteur: (Long) -> Unit,
    surReleveRadiateurs: (Long, String?) -> Unit,
    surReleveSolde: (Long, String?) -> Unit,
) {
    val portee = rememberCoroutineScope()
    // Confirmation brève après un enregistrement : l'écran ne change pas.
    val messages = remember { SnackbarHostState() }
    val enregistre = stringResource(R.string.enregistre)
    var compteurId by remember { mutableStateOf(id) }
    var origine by remember { mutableStateOf<CompteurHistorique?>(null) }
    var energie by remember { mutableStateOf("GAZ") }
    var plage by remember { mutableStateOf("UNIQUE") }
    var libelle by remember { mutableStateOf("") }
    var numero by remember { mutableStateOf("") }
    var unite by remember { mutableStateOf("m³") }
    var coef by remember { mutableStateOf("11") }
    var pose by remember { mutableStateOf<String?>(null) }
    var depose by remember { mutableStateOf<String?>(null) }
    var releves by remember { mutableStateOf<List<ReleveHistorique>>(emptyList()) }
    var radiateurs by remember { mutableStateOf<List<RadiateurLocal>>(emptyList()) }
    var radiateurEdite by remember { mutableStateOf<RadiateurLocal?>(null) }
    var prepaye by remember { mutableStateOf(false) }
    var achats by remember { mutableStateOf<List<AchatLocal>>(emptyList()) }
    var achatEdite by remember { mutableStateOf<AchatLocal?>(null) }
    var releveEdite by remember { mutableStateOf<Pair<ReleveHistorique, String?>?>(null) }
    var remplacer by remember { mutableStateOf(false) }
    var indexPasses by remember { mutableStateOf(false) }
    var confirmer by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }
    // La maison du compteur, pour le titre.
    var nomMaison by remember { mutableStateOf("") }

    LaunchedEffect(compteurId, version) {
        if (compteurId == 0L) return@LaunchedEffect
        val c = gestion.compteur(compteurId) ?: return@LaunchedEffect
        if (origine == null) {
            energie = c.energie; plage = c.plage; libelle = c.libelle; numero = c.numero
            unite = c.unite; coef = ecrireNombre(c.coefKwh); pose = c.datePose; depose = c.dateDepose
            prepaye = c.prepaye
        }
        origine = c
        nomMaison = gestion.maison(c.maisonId)?.nom.orEmpty()
        releves = gestion.releves(compteurId).sortedByDescending { it.date }
        radiateurs = gestion.radiateurs(compteurId).filter { it.dateRetrait == null }
        achats = gestion.achats(compteurId).sortedByDescending { it.date }
    }
    val repartiteur = energie == "REP"
    // Ces deux-là tiennent leurs index de leur propre saisie.
    val indexCalcule = repartiteur || prepaye

    fun enregistrer() = portee.launch {
        val c = (origine ?: CompteurHistorique(0, maisonId, energie, plage, unite, "", null)).copy(
            energie = energie, plage = plage, libelle = libelle, numero = numero.trim(), unite = unite.trim(),
            coefKwh = lireNombre(coef) ?: 1.0, datePose = pose, dateDepose = depose, prepaye = prepaye,
        )
        compteurId = gestion.enregistrerCompteur(c)
        portee.launch { messages.showSnackbar(enregistre) }
        version++
    }

    Cadre(
        titre = if (compteurId == 0L) stringResource(R.string.nouveau_compteur)
            else origine?.let { listOf(nomMaison, libelleCompteur(it)).filter { t -> t.isNotEmpty() }.joinToString(" – ") }.orEmpty(),
        surRetour = surRetour,
        messages = messages,
        surSuppression = if (compteurId != 0L) ({ confirmer = true }) else null,
    ) {
        // Un répartiteur n'a pas les mêmes relevés : on ne devient pas l'un ou l'autre en route.
        ChampChoix(
            stringResource(R.string.energie),
            energies().filter { compteurId == 0L || ((it.first == "REP") == repartiteur && (!prepaye || it.first == "ELEC")) },
            energie,
        ) {
            energie = it
            // Valeurs usuelles : le gaz se relève en m³ et se convertit à ~11 kWh/m³.
            if (compteurId == 0L) {
                unite = when (it) { "ELEC" -> "kWh"; "MAZ" -> "L"; "REP" -> "u"; else -> "m³" }
                // Un litre de mazout libère environ 10 kWh.
                coef = when (it) { "GAZ" -> "11"; "MAZ" -> "10"; else -> "1" }
                if (it != "ELEC") prepaye = false
            }
        }
        // Le choix se fait à la création : les relevés n'ont pas le même sens.
        if (energie == "ELEC" && compteurId == 0L) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = prepaye, onCheckedChange = { prepaye = it; plage = "UNIQUE"; unite = "kWh"; coef = "1" })
                Text(stringResource(R.string.compteur_prepaye))
            }
        }
        if (energie == "ELEC" && !prepaye) ChampChoix(stringResource(R.string.plage), plages(), plage) { plage = it }
        ChampTexte(stringResource(R.string.libelle_facultatif), libelle, { libelle = it })
        ChampTexte(stringResource(R.string.numero_compteur), numero, { numero = it })
        if (!indexCalcule) Row {
            ChampTexte(stringResource(R.string.unite), unite, { unite = it }, Modifier.weight(1f).padding(end = 8.dp))
            ChampTexte(stringResource(R.string.coef_kwh), coef, { coef = it }, Modifier.weight(1f), numerique = true)
        }
        ChampDate(stringResource(R.string.pose_le), pose, { pose = it }, facultatif = true)
        ChampDate(stringResource(R.string.depose_le_champ), depose, { depose = it }, facultatif = true)
        Button(onClick = { enregistrer() }, Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            IconeTexte(R.drawable.ic_valider, stringResource(R.string.enregistrer))
        }
        // Pour un répartiteur, un boîtier neuf est une simple remise à zéro.
        if (compteurId != 0L && depose == null && !indexCalcule) {
            OutlinedButton(onClick = { remplacer = true }, Modifier.fillMaxWidth()) {
                IconeTexte(R.drawable.ic_compteur, stringResource(R.string.remplacer_compteur))
            }
        }

        if (compteurId != 0L && repartiteur) {
            TitreSection(stringResource(R.string.radiateurs_titre, radiateurs.size), icone = R.drawable.ic_repartiteur, action = {
                TextButton(onClick = { radiateurEdite = RadiateurLocal(0, compteurId, "", 1.0) }) {
                    IconeTexte(R.drawable.ic_ajouter, stringResource(R.string.ajouter))
                }
            })
            if (radiateurs.isEmpty()) {
                Text(
                    stringResource(R.string.radiateurs_astuce),
                    style = MaterialTheme.typography.bodySmall, color = Couleurs.encre2,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            radiateurs.forEach { r ->
                LigneListe(
                    titre = r.nom,
                    detail = stringResource(R.string.radiateur_coefficient_detail, afficherNombre(r.coefficient)),
                    surAppui = { radiateurEdite = r },
                )
            }
        }

        if (compteurId != 0L && prepaye) {
            TitreSection(stringResource(R.string.achats_titre, achats.size), icone = R.drawable.ic_electricite, action = {
                TextButton(onClick = { achatEdite = AchatLocal(0, compteurId, LocalDate.now().toString(), 0.0) }) {
                    IconeTexte(R.drawable.ic_ajouter, stringResource(R.string.ajouter))
                }
            })
            achats.forEach { a ->
                LigneListe(
                    titre = "${jour(a.date)} · ${afficherNombre(a.quantite)} $unite",
                    detail = a.montant?.let(::afficherNombre).orEmpty(),
                    surAppui = { achatEdite = a },
                )
            }
        }

        if (compteurId != 0L) {
            TitreSection(stringResource(R.string.releves_titre, releves.size), icone = R.drawable.ic_releve, action = {
                TextButton(onClick = {
                    if (repartiteur) surReleveRadiateurs(compteurId, null)
                    else if (prepaye) surReleveSolde(compteurId, null)
                    else releveEdite = ReleveHistorique(compteurId, LocalDate.now().toString(), releves.firstOrNull()?.index ?: 0.0, false) to null
                }) { IconeTexte(R.drawable.ic_ajouter, stringResource(R.string.ajouter)) }
            })
            // Les anciennes factures donnent tout de suite un historique.
            if (releves.size <= 1 && !indexCalcule) {
                Text(
                    stringResource(R.string.index_passes_astuce),
                    style = MaterialTheme.typography.bodySmall, color = Couleurs.encre2,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (!indexCalcule) OutlinedButton(onClick = { indexPasses = true }, Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                IconeTexte(R.drawable.ic_releve, stringResource(R.string.index_passes))
            }
            releves.forEach { r ->
                LigneListe(
                    titre = "${jour(r.date)} · ${afficherNombre(r.solde ?: r.index)} $unite",
                    detail = listOfNotNull(
                        if (r.annuel) stringResource(R.string.releve_annuel) else null,
                        r.commentaire.ifEmpty { null },
                    ).joinToString(" · "),
                    surAppui = {
                        when {
                            repartiteur -> surReleveRadiateurs(compteurId, r.date)
                            prepaye -> surReleveSolde(compteurId, r.date)
                            else -> releveEdite = r to r.date
                        }
                    },
                )
            }
        }
    }

    releveEdite?.let { (r, dateAvant) ->
        DialogueReleve(
            r, unite,
            surFermeture = { releveEdite = null },
            surEnregistrement = { nouveau ->
                try {
                    gestion.enregistrerReleve(nouveau, dateAvant)
                    releveEdite = null
                    version++
                    portee.launch { messages.showSnackbar(enregistre) }
                    null
                } catch (refus: Refus) {
                    refus
                }
            },
            surSuppression = if (dateAvant != null) ({
                portee.launch { gestion.supprimerReleve(r.compteurId, dateAvant); releveEdite = null; version++ }
            }) else null,
        )
    }
    radiateurEdite?.let { r ->
        DialogueRadiateur(
            r,
            surFermeture = { radiateurEdite = null },
            surEnregistrement = { nouveau ->
                portee.launch { gestion.enregistrerRadiateur(nouveau); radiateurEdite = null; version++ }
            },
            surRetrait = if (r.id != 0L) ({
                portee.launch { gestion.retirerRadiateur(r, LocalDate.now()); radiateurEdite = null; version++ }
            }) else null,
        )
    }
    achatEdite?.let { a ->
        DialogueAchat(
            a,
            datesRelevees = releves.map { it.date }.toSet(),
            surFermeture = { achatEdite = null },
            surEnregistrement = { nouveau ->
                portee.launch { gestion.enregistrerAchat(nouveau); achatEdite = null; version++ }
            },
            surSuppression = if (a.id != 0L) ({
                portee.launch { gestion.supprimerAchat(a); achatEdite = null; version++ }
            }) else null,
        )
    }
    if (indexPasses) {
        val nbAjoutes = stringResource(R.string.index_passes_ajoutes)
        DialogueIndexPasses(
            unite = unite,
            // Une ligne par année, en remontant depuis le plus ancien relevé connu.
            depart = releves.minOfOrNull { LocalDate.parse(it.date) } ?: LocalDate.now(),
            surFermeture = { indexPasses = false },
            surEnregistrement = { lignes ->
                val refus = mutableMapOf<Int, Refus>()
                var ajoutes = 0
                // Du plus ancien au plus récent : chaque index est contrôlé par rapport à ses voisins.
                for ((rang, ligne) in lignes.withIndex().sortedBy { it.value.first }) {
                    try {
                        gestion.enregistrerReleve(
                            ReleveHistorique(compteurId, ligne.first.toString(), ligne.second, annuel = ligne.third, source = "FOURNISSEUR"),
                            dateAvant = null,
                        )
                        ajoutes++
                    } catch (e: Refus) {
                        refus[rang] = e
                    }
                }
                version++
                if (refus.isEmpty()) {
                    indexPasses = false
                    portee.launch { messages.showSnackbar(nbAjoutes.format(ajoutes)) }
                }
                refus
            },
        )
    }
    if (remplacer) {
        DialogueRemplacement(
            surFermeture = { remplacer = false },
            surConfirmation = { date, index, nouveauNumero ->
                portee.launch {
                    val nouveau = gestion.remplacerCompteur(compteurId, date, index, nouveauNumero)
                    remplacer = false
                    surCompteur(nouveau)
                }
            },
        )
    }
    if (confirmer) {
        ConfirmerSuppression(
            stringResource(R.string.supprimer_compteur_confirmation, releves.size),
            surConfirmation = { portee.launch { gestion.supprimerCompteur(compteurId); confirmer = false; surRetour() } },
            surAbandon = { confirmer = false },
        )
    }
}

@Composable
fun messageRefus(refus: Refus): String = when (refus.motif) {
    Motif.INDEX_RECULE -> stringResource(R.string.refus_index_recule, jour(refus.details[0] as String), ecrireNombre(refus.details[1] as Double))
    Motif.INDEX_DEPASSE -> stringResource(R.string.refus_index_depasse, jour(refus.details[0] as String), ecrireNombre(refus.details[1] as Double))
    Motif.DATE_DEJA_RELEVEE -> stringResource(R.string.refus_date_deja_relevee, jour(refus.details[0] as String))
    Motif.NOM_OBLIGATOIRE -> stringResource(R.string.nom_obligatoire)
    Motif.DATE_OBLIGATOIRE -> stringResource(R.string.date_obligatoire)
}

@Composable
fun DialogueReleve(
    releve: ReleveHistorique,
    unite: String,
    surFermeture: () -> Unit,
    surEnregistrement: suspend (ReleveHistorique) -> Refus?,
    surSuppression: (() -> Unit)?,
) {
    val portee = rememberCoroutineScope()
    var date by remember { mutableStateOf<String?>(releve.date) }
    var index by remember { mutableStateOf(ecrireNombre(releve.index)) }
    var annuel by remember { mutableStateOf(releve.annuel) }
    var source by remember { mutableStateOf(releve.source) }
    var commentaire by remember { mutableStateOf(releve.commentaire) }
    var refus by remember { mutableStateOf<Refus?>(null) }
    AlertDialog(
        onDismissRequest = surFermeture,
        title = { Text(stringResource(R.string.releve)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ChampDate(stringResource(R.string.date_du_releve), date, { date = it })
                ChampTexte(
                    stringResource(R.string.index_unite, unite), index, { index = it; refus = null },
                    numerique = true, erreur = refus?.let { messageRefus(it) },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = annuel, onCheckedChange = { annuel = it })
                    Text(stringResource(R.string.releve_annuel))
                }
                ChampChoix(
                    stringResource(R.string.source),
                    listOf(
                        "MANUEL" to stringResource(R.string.source_manuel),
                        "FOURNISSEUR" to stringResource(R.string.source_fournisseur),
                        "ESTIME" to stringResource(R.string.source_estime),
                        "IMPORT" to stringResource(R.string.source_import),
                    ),
                    source,
                ) { source = it }
                ChampTexte(stringResource(R.string.commentaire), commentaire, { commentaire = it })
            }
        },
        confirmButton = {
            TextButton(
                enabled = lireNombre(index) != null && date != null,
                onClick = {
                    portee.launch {
                        refus = surEnregistrement(
                            releve.copy(
                                date = date!!, index = lireNombre(index)!!, annuel = annuel,
                                source = source, commentaire = commentaire.trim().take(200),
                            )
                        )
                    }
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

@Composable
fun DialogueRemplacement(
    surFermeture: () -> Unit,
    surConfirmation: (LocalDate, Double, String) -> Unit,
) {
    var date by remember { mutableStateOf<String?>(LocalDate.now().toString()) }
    var index by remember { mutableStateOf("0") }
    var numero by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = surFermeture,
        title = { Text(stringResource(R.string.remplacer_compteur)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.remplacer_explication), style = MaterialTheme.typography.bodyMedium)
                ChampDate(stringResource(R.string.date_remplacement), date, { date = it })
                ChampTexte(stringResource(R.string.index_depart), index, { index = it }, numerique = true)
                ChampTexte(stringResource(R.string.numero_nouveau), numero, { numero = it })
            }
        },
        confirmButton = {
            TextButton(
                enabled = date != null && lireNombre(index) != null,
                onClick = { surConfirmation(LocalDate.parse(date), lireNombre(index)!!, numero) },
            ) { Text(stringResource(R.string.remplacer)) }
        },
        dismissButton = { TextButton(onClick = surFermeture) { Text(stringResource(R.string.annuler)) } },
    )
}

/**
 * Plusieurs index d'un coup, typiquement repris des factures annuelles : une
 * ligne par année, datée d'un an avant la précédente.
 */
@Composable
fun DialogueIndexPasses(
    unite: String,
    depart: LocalDate,
    surFermeture: () -> Unit,
    surEnregistrement: suspend (List<Triple<LocalDate, Double, Boolean>>) -> Map<Int, Refus>,
) {
    val portee = rememberCoroutineScope()
    val dates = remember { mutableStateListOf<String?>(*Array(3) { depart.minusYears(it + 1L).toString() }) }
    val index = remember { mutableStateListOf("", "", "") }
    var annuels by remember { mutableStateOf(true) }
    var erreurs by remember { mutableStateOf<Map<Int, Refus>>(emptyMap()) }

    AlertDialog(
        onDismissRequest = surFermeture,
        title = { Text(stringResource(R.string.index_passes)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.index_passes_explication), style = MaterialTheme.typography.bodySmall, color = Couleurs.encre2)
                for (i in dates.indices) {
                    Row(verticalAlignment = Alignment.Top) {
                        ChampDate(stringResource(R.string.date), dates[i], { dates[i] = it }, Modifier.weight(1f).padding(end = 8.dp))
                        ChampTexte(
                            stringResource(R.string.index_unite, unite), index[i], { index[i] = it; erreurs = erreurs - i },
                            Modifier.weight(1f), numerique = true,
                            erreur = erreurs[i]?.let { messageRefus(it) },
                        )
                    }
                }
                TextButton(onClick = {
                    val plusAncienne = dates.filterNotNull().minOfOrNull { LocalDate.parse(it) } ?: depart
                    dates.add(plusAncienne.minusYears(1).toString())
                    index.add("")
                }) { IconeTexte(R.drawable.ic_ajouter, stringResource(R.string.ajouter_une_ligne)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = annuels, onCheckedChange = { annuels = it })
                    Text(stringResource(R.string.index_passes_annuels))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // Lignes remplies seulement ; les autres sont ignorées.
                val remplies = dates.indices.mapNotNull { i ->
                    val d = dates[i] ?: return@mapNotNull null
                    val v = lireNombre(index[i]) ?: return@mapNotNull null
                    i to Triple(LocalDate.parse(d), v, annuels)
                }
                if (remplies.isEmpty()) { surFermeture(); return@TextButton }
                portee.launch {
                    val refus = surEnregistrement(remplies.map { it.second })
                    // Les lignes enregistrées quittent le formulaire ; seules restent,
                    // avec leur motif, celles qui ont été refusées.
                    val refusees = refus.keys.map { remplies[it].first }.toSet()
                    val gardees = dates.indices.filter { it in refusees || remplies.none { r -> r.first == it } }
                    val nouvellesDates = gardees.map { dates[it] }
                    val nouveauxIndex = gardees.map { index[it] }
                    erreurs = refus.mapKeys { (rang, _) -> gardees.indexOf(remplies[rang].first) }
                    dates.clear(); dates.addAll(nouvellesDates)
                    index.clear(); index.addAll(nouveauxIndex)
                }
            }) { Text(stringResource(R.string.enregistrer)) }
        },
        dismissButton = { TextButton(onClick = surFermeture) { Text(stringResource(R.string.annuler)) } },
    )
}
