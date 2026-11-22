package be.suivicompteurs.app.gestion

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import be.suivicompteurs.app.R
import be.suivicompteurs.app.classeur.ImportCsv
import be.suivicompteurs.app.donnees.AchatLocal
import be.suivicompteurs.app.donnees.CompteurHistorique
import be.suivicompteurs.app.donnees.RadiateurLocal
import be.suivicompteurs.app.donnees.ReleveHistorique
import be.suivicompteurs.app.moteur.maximumApresTour
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun EcranCompteur(
    gestion: Gestion,
    id: Long,
    maisonId: Long,
    surRetour: () -> Unit,
    surCompteur: (Long) -> Unit,
    surReleveRadiateurs: (Long, String?) -> Unit,
    surReleveSolde: (Long, String?) -> Unit,
    surReleveConsommation: (Long, String?) -> Unit,
    surConsommationsPassees: (Long) -> Unit,
) {
    val portee = rememberCoroutineScope()
    // Confirmation brève après un enregistrement : l'écran ne change pas.
    val messages = remember { SnackbarHostState() }
    val enregistre = stringResource(R.string.enregistre)
    // Gardé au retour d'un écran ouvert d'ici : un compteur qui vient d'être
    // créé ne redevient pas un formulaire vide.
    var compteurId by rememberSaveable { mutableStateOf(id) }
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
    var parConsommation by remember { mutableStateOf(false) }
    var achats by remember { mutableStateOf<List<AchatLocal>>(emptyList()) }
    var achatEdite by remember { mutableStateOf<AchatLocal?>(null) }
    var releveEdite by remember { mutableStateOf<Pair<ReleveHistorique, String?>?>(null) }
    var remplacer by remember { mutableStateOf(false) }
    var indexPasses by remember { mutableStateOf(false) }
    var confirmer by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }
    // Fichier CSV lu, en attente de confirmation.
    var importCsv by remember { mutableStateOf<Pair<ImportCsv.Lecture, ImportCsv.Tri>?>(null) }
    val contexte = LocalContext.current
    val csvIllisible = stringResource(R.string.import_csv_illisible)
    val choisirCsv = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        portee.launch {
            val lecture = withContext(Dispatchers.IO) {
                runCatching {
                    contexte.contentResolver.openInputStream(uri)?.use { flux ->
                        // readNBytes n'existe qu'à partir d'Android 13.
                        val octets = flux.buffered().let { b ->
                            val sortie = java.io.ByteArrayOutputStream()
                            val tampon = ByteArray(64 * 1024)
                            while (sortie.size() <= ImportCsv.TAILLE_MAX) {
                                val n = b.read(tampon)
                                if (n < 0) break
                                sortie.write(tampon, 0, n)
                            }
                            sortie.toByteArray()
                        }
                        if (octets.size > ImportCsv.TAILLE_MAX) return@use null
                        // UTF-8 le plus souvent ; un vieil export Windows est en Latin-1.
                        val texte = String(octets, Charsets.UTF_8)
                        ImportCsv.lire(if ('\uFFFD' in texte) String(octets, Charsets.ISO_8859_1) else texte)
                    }
                }.getOrNull()
            }
            if (lecture == null || lecture.releves.isEmpty()) messages.showSnackbar(csvIllisible)
            else importCsv = lecture to gestion.trierImport(compteurId, lecture.releves)
        }
    }
    // La maison du compteur, pour le titre.
    var nomMaison by remember { mutableStateOf("") }

    LaunchedEffect(compteurId, version) {
        if (compteurId == 0L) return@LaunchedEffect
        val c = gestion.compteur(compteurId) ?: return@LaunchedEffect
        if (origine == null) {
            energie = c.energie; plage = c.plage; libelle = c.libelle; numero = c.numero
            unite = c.unite; coef = ecrireNombre(c.coefKwh); pose = c.datePose; depose = c.dateDepose
            prepaye = c.prepaye
            parConsommation = c.parConsommation
        }
        origine = c
        nomMaison = gestion.maison(c.maisonId)?.nom.orEmpty()
        releves = gestion.releves(compteurId).sortedByDescending { it.date }
        radiateurs = gestion.radiateurs(compteurId).filter { it.dateRetrait == null }
        achats = gestion.achats(compteurId).sortedByDescending { it.date }
    }
    val repartiteur = energie == "REP"
    // Ceux-là tiennent leurs index de leur propre saisie.
    val indexCalcule = repartiteur || prepaye || parConsommation
    val cuve = prepaye && energie == "MAZ"

    fun enregistrer() = portee.launch {
        val c = (origine ?: CompteurHistorique(0, maisonId, energie, plage, unite, "", null)).copy(
            energie = energie, plage = plage, libelle = libelle, numero = numero.trim(), unite = unite.trim(),
            coefKwh = lireNombre(coef) ?: 1.0, datePose = pose, dateDepose = depose, prepaye = prepaye,
            parConsommation = parConsommation,
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
            energies().filter { compteurId == 0L || ((it.first == "REP") == repartiteur && (!prepaye || it.first == energie)) },
            energie,
        ) {
            energie = it
            // Valeurs usuelles : le gaz se relève en m³ et se convertit à ~11 kWh/m³.
            if (compteurId == 0L) {
                unite = when (it) { "ELEC", "CHA" -> "kWh"; "MAZ" -> "L"; "REP" -> "u"; else -> "m³" }
                // Un litre de mazout libère environ 10 kWh.
                coef = when (it) { "GAZ" -> "11"; "MAZ" -> "10"; else -> "1" }
                // Le mazout se lit le plus souvent à la jauge de la cuve.
                prepaye = it == "MAZ"
                if (it == "REP") parConsommation = false
            }
        }
        // Le choix se fait à la création : les relevés n'ont pas le même sens.
        if (energie in setOf("ELEC", "MAZ") && compteurId == 0L) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = prepaye, onCheckedChange = { prepaye = it; plage = "UNIQUE"; if (it) parConsommation = false })
                Text(stringResource(if (energie == "MAZ") R.string.compteur_cuve else R.string.compteur_prepaye))
            }
        }
        // Modifiable tant que le compteur n'a aucun relevé : rien à réinterpréter.
        if (energie != "REP" && !prepaye && (compteurId == 0L || releves.isEmpty())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = parConsommation, onCheckedChange = { parConsommation = it })
                Text(stringResource(R.string.compteur_par_consommation))
            }
        }
        if (energie == "ELEC" && !prepaye) ChampChoix(stringResource(R.string.plage), plages(), plage) { plage = it }
        ChampTexte(stringResource(R.string.libelle_facultatif), libelle, { libelle = it })
        ChampTexte(stringResource(R.string.numero_compteur), numero, { numero = it })
        if (!repartiteur && !prepaye) Row {
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
            TitreSection(
                stringResource(if (cuve) R.string.livraisons_titre else R.string.achats_titre, achats.size),
                icone = iconeEnergie(energie),
                action = {
                TextButton(onClick = { achatEdite = AchatLocal(0, compteurId, LocalDate.now().toString(), 0.0) }) {
                    IconeTexte(R.drawable.ic_ajouter, stringResource(R.string.ajouter))
                }
                },
            )
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
                    else if (parConsommation) surReleveConsommation(compteurId, null)
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
            if (!indexCalcule) OutlinedButton(
                onClick = { choisirCsv.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream")) },
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                IconeTexte(R.drawable.ic_releve, stringResource(R.string.import_csv))
            }
            if (parConsommation && origine?.parConsommation == true) {
                OutlinedButton(onClick = { surConsommationsPassees(compteurId) }, Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    IconeTexte(R.drawable.ic_releve, stringResource(R.string.consommations_passees))
                }
            }
            releves.forEach { r ->
                LigneListe(
                    titre = if (parConsommation) "${jour(r.date)} · +${afficherNombre(r.consommation ?: 0.0)} $unite"
                        else "${jour(r.date)} · ${afficherNombre(r.solde ?: r.index)} $unite",
                    detail = listOfNotNull(
                        if (r.annuel) stringResource(R.string.releve_annuel) else null,
                        r.commentaire.ifEmpty { null },
                    ).joinToString(" · "),
                    surAppui = {
                        when {
                            repartiteur -> surReleveRadiateurs(compteurId, r.date)
                            prepaye -> surReleveSolde(compteurId, r.date)
                            parConsommation -> surReleveConsommation(compteurId, r.date)
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
            unite = unite,
            cuve = cuve,
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
    importCsv?.let { (lecture, tri) ->
        val resources = contexte.resources
        DialogueImportCsv(
            lecture, tri, unite,
            surFermeture = { importCsv = null },
            surImport = {
                portee.launch {
                    val n = gestion.importerReleves(compteurId, lecture.releves)
                    importCsv = null
                    version++
                    messages.showSnackbar(resources.getQuantityString(R.plurals.import_csv_importes, n, n))
                }
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
    Motif.INDEX_RECULE -> stringResource(R.string.refus_index_recule, jour(refus.details[0] as String), ecrireNombre(refus.details[1] as Double)) +
        (if (refus.details.getOrNull(2) == true) "\n" + stringResource(R.string.astuce_par_consommation) else "")
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
    // Index plus bas, qu'un tour complet du compteur peut expliquer.
    var tourPropose by remember { mutableStateOf<Pair<ReleveHistorique, Double>?>(null) }
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
                        val nouveau = releve.copy(
                            date = date!!, index = lireNombre(index)!!, annuel = annuel,
                            source = source, commentaire = commentaire.trim().take(200),
                        )
                        val raison = surEnregistrement(nouveau)
                        val precedent = raison?.precedentAvantTour(nouveau.index)
                        if (precedent != null) tourPropose = nouveau to precedent else refus = raison
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
    tourPropose?.let { (nouveau, precedent) ->
        val maximum = maximumApresTour(precedent, nouveau.index) ?: 0.0
        AlertDialog(
            onDismissRequest = { tourPropose = null },
            title = { Text(stringResource(R.string.tour_complet_titre)) },
            text = {
                Text(
                    stringResource(
                        R.string.tour_complet_question,
                        afficherNombre(nouveau.index), afficherNombre(precedent),
                        afficherNombre(maximum - precedent + nouveau.index), unite,
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    tourPropose = null
                    portee.launch { refus = surEnregistrement(nouveau.copy(tourComplet = maximum)) }
                }) { Text(stringResource(R.string.tour_complet_oui)) }
            },
            dismissButton = { TextButton(onClick = { tourPropose = null }) { Text(stringResource(R.string.corriger)) } },
        )
    }
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

/** Aperçu d'un fichier CSV avant import : ce qui sera repris, ce qui ne le sera pas. */
@Composable
fun DialogueImportCsv(
    lecture: ImportCsv.Lecture,
    tri: ImportCsv.Tri,
    unite: String,
    surFermeture: () -> Unit,
    surImport: () -> Unit,
) {
    val petit = MaterialTheme.typography.bodySmall
    AlertDialog(
        onDismissRequest = surFermeture,
        title = { Text(stringResource(R.string.import_csv)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (tri.retenus.isEmpty()) {
                    Text(stringResource(R.string.import_csv_rien))
                } else {
                    Text(
                        pluralStringResource(
                            R.plurals.import_csv_retenus, tri.retenus.size, tri.retenus.size,
                            jour(tri.retenus.first().first.toString()), jour(tri.retenus.last().first.toString()),
                        )
                    )
                    val (dernierJour, dernierIndex) = tri.retenus.last()
                    Text(
                        stringResource(R.string.import_csv_dernier, jour(dernierJour.toString()), afficherNombre(dernierIndex), unite),
                        style = petit, color = Couleurs.encre2, modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (tri.dejaReleves.isNotEmpty()) {
                    Text(pluralStringResource(R.plurals.import_csv_deja, tri.dejaReleves.size, tri.dejaReleves.size), style = petit, modifier = Modifier.padding(top = 8.dp))
                }
                if (lecture.memeJour > 0) {
                    Text(pluralStringResource(R.plurals.import_csv_meme_jour, lecture.memeJour, lecture.memeJour), style = petit, modifier = Modifier.padding(top = 8.dp))
                }
                if (tri.incoherents.isNotEmpty()) {
                    Text(pluralStringResource(R.plurals.import_csv_incoherents, tri.incoherents.size, tri.incoherents.size), style = petit, color = Couleurs.critique, modifier = Modifier.padding(top = 8.dp))
                    tri.incoherents.take(5).forEach { (j, v) ->
                        Text("• ${jour(j.toString())} · ${afficherNombre(v)} $unite", style = petit, color = Couleurs.encre2)
                    }
                }
                if (lecture.ignorees.isNotEmpty()) {
                    Text(pluralStringResource(R.plurals.import_csv_ignorees, lecture.ignorees.size, lecture.ignorees.size), style = petit, color = Couleurs.critique, modifier = Modifier.padding(top = 8.dp))
                    lecture.ignorees.take(5).forEach {
                        Text(stringResource(R.string.import_csv_ligne, it.numero, it.contenu.take(40)), style = petit, color = Couleurs.encre2)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = tri.retenus.isNotEmpty(), onClick = surImport) { Text(stringResource(R.string.importer)) }
        },
        dismissButton = { TextButton(onClick = surFermeture) { Text(stringResource(R.string.annuler)) } },
    )
}
