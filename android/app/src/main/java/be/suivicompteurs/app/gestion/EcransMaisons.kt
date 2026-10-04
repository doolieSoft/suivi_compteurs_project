package be.suivicompteurs.app.gestion

import androidx.compose.material3.SnackbarHostState
import be.suivicompteurs.app.gestion.BoutonIcone
import be.suivicompteurs.app.gestion.IconeTexte
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Switch
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
import be.suivicompteurs.app.donnees.CompteurHistorique
import be.suivicompteurs.app.donnees.EvenementLocal
import be.suivicompteurs.app.donnees.MaisonLocale
import be.suivicompteurs.app.donnees.TarifLocal
import be.suivicompteurs.app.moteur.Energie
import be.suivicompteurs.app.moteur.Plage
import be.suivicompteurs.app.reseau.Lieu
import be.suivicompteurs.app.reseau.OpenMeteo
import be.suivicompteurs.app.reseau.Resultat
import kotlinx.coroutines.launch

@Composable
fun EcranMaisons(gestion: Gestion, surRetour: () -> Unit, surMaison: (Long) -> Unit) {
    var maisons by remember { mutableStateOf<List<MaisonLocale>>(emptyList()) }
    LaunchedEffect(Unit) { maisons = gestion.maisons() }

    Cadre(stringResource(R.string.titre_gestion), surRetour, surAjout = { surMaison(0L) }) {
        if (maisons.isEmpty()) Text(stringResource(R.string.aucune_maison), Modifier.padding(vertical = 16.dp))
        maisons.forEach { m ->
            LigneListe(
                titre = m.nom,
                detail = listOfNotNull(
                    m.adresse.ifEmpty { null },
                    m.dateEntree?.let { stringResource(R.string.occupee_depuis, jour(it)) },
                    m.dateSortie?.let { stringResource(R.string.quittee_le, jour(it)) },
                    if (!m.actuelle && m.dateSortie == null) stringResource(R.string.non_relevee) else null,
                ).joinToString(" · "),
                icone = R.drawable.ic_maison,
                surAppui = { surMaison(m.id) },
            )
        }
    }
}

/** Libellés des énergies et plages, depuis les ressources. */
@Composable
fun energies() = listOf(
    "EAU" to stringResource(R.string.energie_eau),
    "GAZ" to stringResource(R.string.energie_gaz),
    "ELEC" to stringResource(R.string.energie_electricite),
    "MAZ" to stringResource(R.string.energie_mazout),
)

@Composable
fun plages() = listOf(
    "UNIQUE" to stringResource(R.string.plage_unique),
    "HAUT" to stringResource(R.string.plage_haute),
    "BAS" to stringResource(R.string.plage_basse),
)

@Composable
fun libelleCompteur(c: CompteurHistorique): String {
    val energie = energies().first { it.first == c.energie }.second
    val plage = plages().first { it.first == c.plage }.takeIf { c.plage != "UNIQUE" }?.second
    return listOfNotNull(energie, plage, c.libelle.ifEmpty { null }).joinToString(" – ")
}

@Composable
fun EcranMaison(
    gestion: Gestion,
    id: Long,
    surRetour: () -> Unit,
    surCompteur: (compteur: Long, maison: Long) -> Unit,
) {
    val portee = rememberCoroutineScope()
    // Confirmation brève après un enregistrement : l'écran ne change pas.
    val messages = remember { SnackbarHostState() }
    val enregistre = stringResource(R.string.enregistre)
    val contexte = LocalContext.current
    var maisonId by remember { mutableStateOf(id) }
    var nom by remember { mutableStateOf("") }
    var adresse by remember { mutableStateOf("") }
    var facades by remember { mutableStateOf("") }
    var surface by remember { mutableStateOf("") }
    var entree by remember { mutableStateOf<String?>(null) }
    var sortie by remember { mutableStateOf<String?>(null) }
    var relevee by remember { mutableStateOf(true) }
    var latitude by remember { mutableStateOf("") }
    var longitude by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var ville by remember { mutableStateOf("") }
    var lieux by remember { mutableStateOf<List<Lieu>>(emptyList()) }
    var messageLieu by remember { mutableStateOf<String?>(null) }
    var rechercheEnCours by remember { mutableStateOf(false) }
    var origine by remember { mutableStateOf<MaisonLocale?>(null) }
    var compteurs by remember { mutableStateOf<List<CompteurHistorique>>(emptyList()) }
    var tarifs by remember { mutableStateOf<List<TarifLocal>>(emptyList()) }
    var evenements by remember { mutableStateOf<List<EvenementLocal>>(emptyList()) }
    var erreurNom by remember { mutableStateOf(false) }
    var confirmer by remember { mutableStateOf(false) }
    var tarifEdite by remember { mutableStateOf<TarifLocal?>(null) }
    var evenementEdite by remember { mutableStateOf<EvenementLocal?>(null) }
    var version by remember { mutableIntStateOf(0) }

    LaunchedEffect(maisonId, version) {
        if (maisonId == 0L) return@LaunchedEffect
        val m = gestion.maison(maisonId) ?: return@LaunchedEffect
        if (origine == null) {
            nom = m.nom; adresse = m.adresse
            facades = m.nbFacades?.toString().orEmpty(); surface = m.surface?.toString().orEmpty()
            entree = m.dateEntree; sortie = m.dateSortie; relevee = m.actuelle; notes = m.notes; ville = m.ville
            gestion.station(m.stationId)?.let { latitude = it.latitude.toString(); longitude = it.longitude.toString() }
        }
        origine = m
        compteurs = gestion.compteurs(maisonId)
        tarifs = gestion.tarifs(maisonId)
        evenements = gestion.evenements(maisonId)
    }

    fun enregistrer(ensuite: (Long) -> Unit = {}) = portee.launch {
        erreurNom = nom.isBlank()
        if (erreurNom) return@launch
        val m = (origine ?: MaisonLocale(0, "", true, null)).copy(
            nom = nom, adresse = adresse.trim(), nbFacades = facades.toIntOrNull(),
            surface = surface.toIntOrNull(), dateEntree = entree, dateSortie = sortie, notes = notes.trim(),
            actuelle = relevee && sortie == null,
            ville = ville.trim(),
        )
        maisonId = gestion.enregistrerMaison(m, lireNombre(latitude), lireNombre(longitude))
        portee.launch { messages.showSnackbar(enregistre) }
        version++
        ensuite(maisonId)
    }

    // Placer la maison par le nom de sa ville : personne ne connaît ses
    // coordonnées par cœur.
    val introuvable = stringResource(R.string.lieu_introuvable, ville.trim())
    val horsLigne = stringResource(R.string.lieu_hors_ligne)
    val clavier = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    fun chercherLieu() = portee.launch {
        // Le clavier cacherait les résultats.
        clavier?.hide()
        focus.clearFocus()
        rechercheEnCours = true
        messageLieu = null
        val langue = contexte.resources.configuration.locales[0].language
        when (val r = OpenMeteo().chercherLieux(ville, langue)) {
            is Resultat.Succes -> {
                lieux = r.valeur
                if (r.valeur.isEmpty()) messageLieu = introuvable
            }
            is Resultat.Echec -> messageLieu = horsLigne
        }
        rechercheEnCours = false
    }

    Cadre(
        titre = if (maisonId == 0L) stringResource(R.string.nouvelle_maison) else nom,
        surRetour = surRetour,
        messages = messages,
        surSuppression = if (maisonId != 0L) ({ confirmer = true }) else null,
    ) {
        ChampTexte(stringResource(R.string.nom), nom, { nom = it }, erreur = if (erreurNom) stringResource(R.string.nom_obligatoire) else null)
        ChampTexte(stringResource(R.string.adresse), adresse, { adresse = it })
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChampTexte(
                stringResource(R.string.ville), ville,
                { ville = it; lieux = emptyList(); messageLieu = null },
                Modifier.weight(1f).padding(end = 8.dp),
                surRecherche = { if (ville.isNotBlank()) chercherLieu() },
            )
            BoutonIcone(stringResource(R.string.chercher), R.drawable.ic_chercher, { chercherLieu() },
                actif = ville.isNotBlank() && !rechercheEnCours)
        }
        lieux.forEach { l ->
            LigneListe(
                titre = l.libelle,
                detail = stringResource(R.string.coordonnees, textes4(l.latitude), textes4(l.longitude)),
                icone = R.drawable.ic_maison,
                surAppui = {
                    latitude = textes4(l.latitude); longitude = textes4(l.longitude)
                    ville = l.libelle; lieux = emptyList()
                },
            )
        }
        messageLieu?.let { Text(it, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = Couleurs.critique) }
        Row {
            ChampTexte(stringResource(R.string.latitude), latitude, { latitude = it }, Modifier.weight(1f).padding(end = 8.dp), numerique = true)
            ChampTexte(stringResource(R.string.longitude), longitude, { longitude = it }, Modifier.weight(1f), numerique = true)
        }
        Text(stringResource(R.string.position_aide), style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = Couleurs.encre3)
        Row {
            ChampTexte(stringResource(R.string.facades), facades, { facades = it }, Modifier.weight(1f).padding(end = 8.dp), numerique = true)
            ChampTexte(stringResource(R.string.surface), surface, { surface = it }, Modifier.weight(1f), numerique = true)
        }
        ChampDate(stringResource(R.string.occupee_depuis_champ), entree, { entree = it }, facultatif = true)
        // Quitter la maison arrête ses relevés ; les reprendre, c'est l'occuper encore.
        ChampDate(stringResource(R.string.occupee_jusqua_champ), sortie, { sortie = it; if (it != null) relevee = false }, facultatif = true)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.compteurs_a_relever), Modifier.weight(1f))
            Switch(checked = relevee, onCheckedChange = { relevee = it; if (it) sortie = null })
        }
        Text(stringResource(R.string.compteurs_a_relever_aide), style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = Couleurs.encre3)
        ChampTexte(stringResource(R.string.notes), notes, { notes = it }, lignes = 3)
        Button(onClick = { enregistrer() }, Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            IconeTexte(R.drawable.ic_valider, stringResource(R.string.enregistrer))
        }

        if (maisonId != 0L) {
            TitreSection(stringResource(R.string.compteurs), icone = R.drawable.ic_compteur, action = {
                BoutonIcone(stringResource(R.string.ajouter), R.drawable.ic_ajouter, { surCompteur(0L, maisonId) })
            })
            compteurs.forEach { c ->
                LigneListe(
                    titre = libelleCompteur(c),
                    detail = listOfNotNull(
                        c.numero.ifEmpty { null }?.let { stringResource(R.string.numero_court, it) },
                        c.dateDepose?.let { stringResource(R.string.depose_le, jour(it)) },
                    ).joinToString(" · "),
                    couleur = Couleurs.energie(c.energie),
                    icone = iconeEnergie(c.energie),
                    surAppui = { surCompteur(c.id, maisonId) },
                )
            }

            TitreSection(stringResource(R.string.tarifs), icone = R.drawable.ic_tarif, action = {
                TextButton(onClick = {
                    tarifEdite = TarifLocal(maisonId = maisonId, energie = "GAZ", debut = java.time.LocalDate.now().toString(), fin = null, prix = 0.0, abonnement = 0.0)
                }) { IconeTexte(R.drawable.ic_ajouter, stringResource(R.string.ajouter)) }
            })
            tarifs.forEach { t ->
                LigneListe(
                    titre = "${energies().first { it.first == t.energie }.second} · ${jour(t.debut)}" + (t.fin?.let { " → ${jour(it)}" } ?: ""),
                    detail = listOfNotNull(
                        stringResource(
                            R.string.tarif_detail,
                            Monnaie.formater(contexte, t.prix, decimales = 5),
                            Monnaie.formater(contexte, t.abonnement, decimales = 2),
                        ),
                        t.fournisseur.ifEmpty { null },
                        if (t.maisonId == null) stringResource(R.string.toutes_maisons) else null,
                    ).joinToString(" · "),
                    couleur = Couleurs.energie(t.energie),
                    icone = R.drawable.ic_tarif,
                    surAppui = { tarifEdite = t },
                )
            }

            TitreSection(stringResource(R.string.evenements), icone = R.drawable.ic_evenement, action = {
                TextButton(onClick = {
                    evenementEdite = EvenementLocal(maisonId = maisonId, date = java.time.LocalDate.now().toString(), energie = "", libelle = "")
                }) { IconeTexte(R.drawable.ic_ajouter, stringResource(R.string.ajouter)) }
            })
            evenements.forEach { e ->
                LigneListe(titre = e.libelle, detail = jour(e.date), icone = R.drawable.ic_evenement, surAppui = { evenementEdite = e })
            }
        }
    }

    if (confirmer) {
        ConfirmerSuppression(
            stringResource(R.string.supprimer_maison_confirmation, nom),
            surConfirmation = { portee.launch { gestion.supprimerMaison(maisonId); confirmer = false; surRetour() } },
            surAbandon = { confirmer = false },
        )
    }
    tarifEdite?.let { t ->
        DialogueTarif(t, surFermeture = { tarifEdite = null }, surEnregistrement = {
            portee.launch { gestion.enregistrerTarif(it); tarifEdite = null; version++; portee.launch { messages.showSnackbar(enregistre) } }
        }, surSuppression = if (t.id != 0L) ({ portee.launch { gestion.supprimerTarif(t.id); tarifEdite = null; version++ } }) else null)
    }
    evenementEdite?.let { e ->
        DialogueEvenement(e, surFermeture = { evenementEdite = null }, surEnregistrement = {
            portee.launch { gestion.enregistrerEvenement(it); evenementEdite = null; version++; portee.launch { messages.showSnackbar(enregistre) } }
        }, surSuppression = if (e.id != 0L) ({ portee.launch { gestion.supprimerEvenement(e.id); evenementEdite = null; version++ } }) else null)
    }
}

@Composable
fun DialogueTarif(
    tarif: TarifLocal,
    surFermeture: () -> Unit,
    surEnregistrement: (TarifLocal) -> Unit,
    surSuppression: (() -> Unit)?,
) {
    var energie by remember { mutableStateOf(tarif.energie) }
    var debut by remember { mutableStateOf<String?>(tarif.debut) }
    var fin by remember { mutableStateOf(tarif.fin) }
    var prix by remember { mutableStateOf(ecrireNombre(tarif.prix)) }
    var abonnement by remember { mutableStateOf(ecrireNombre(tarif.abonnement)) }
    var fournisseur by remember { mutableStateOf(tarif.fournisseur) }
    AlertDialog(
        onDismissRequest = surFermeture,
        title = { Text(stringResource(R.string.tarif)) },
        text = {
            androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState())) {
                ChampChoix(stringResource(R.string.energie), energies(), energie) { energie = it }
                ChampDate(stringResource(R.string.debut), debut, { debut = it })
                ChampDate(stringResource(R.string.fin), fin, { fin = it }, facultatif = true)
                val symbole = Monnaie.symbole(LocalContext.current)
                ChampTexte(stringResource(R.string.prix_unitaire, symbole), prix, { prix = it }, numerique = true)
                ChampTexte(stringResource(R.string.abonnement_mensuel, symbole), abonnement, { abonnement = it }, numerique = true)
                ChampTexte(stringResource(R.string.fournisseur), fournisseur, { fournisseur = it })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                surEnregistrement(
                    tarif.copy(
                        energie = energie, debut = debut ?: tarif.debut, fin = fin,
                        prix = lireNombre(prix) ?: 0.0, abonnement = lireNombre(abonnement) ?: 0.0,
                        fournisseur = fournisseur.trim(),
                    )
                )
            }) { Text(stringResource(R.string.enregistrer)) }
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
fun DialogueEvenement(
    evenement: EvenementLocal,
    surFermeture: () -> Unit,
    surEnregistrement: (EvenementLocal) -> Unit,
    surSuppression: (() -> Unit)?,
) {
    var date by remember { mutableStateOf<String?>(evenement.date) }
    var energie by remember { mutableStateOf(evenement.energie) }
    var libelle by remember { mutableStateOf(evenement.libelle) }
    var description by remember { mutableStateOf(evenement.description) }
    AlertDialog(
        onDismissRequest = surFermeture,
        title = { Text(stringResource(R.string.evenement)) },
        text = {
            androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState())) {
                ChampDate(stringResource(R.string.date), date, { date = it })
                ChampChoix(
                    stringResource(R.string.energie),
                    listOf("" to stringResource(R.string.toutes_energies)) + energies(),
                    energie,
                ) { energie = it }
                ChampTexte(stringResource(R.string.libelle), libelle, { libelle = it })
                ChampTexte(stringResource(R.string.description), description, { description = it }, lignes = 3)
            }
        },
        confirmButton = {
            TextButton(
                enabled = libelle.isNotBlank(),
                onClick = {
                    surEnregistrement(
                        evenement.copy(date = date ?: evenement.date, energie = energie, libelle = libelle, description = description.trim())
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

/** Coordonnée à quatre décimales (une dizaine de mètres), point décimal. */
private fun textes4(x: Double): String = "%.4f".format(java.util.Locale.ROOT, x)
