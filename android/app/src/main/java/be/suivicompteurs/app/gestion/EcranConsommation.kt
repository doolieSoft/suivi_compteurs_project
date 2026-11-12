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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import be.suivicompteurs.app.R
import be.suivicompteurs.app.analyse.Analyse
import be.suivicompteurs.app.analyse.Textes
import be.suivicompteurs.app.donnees.ReleveHistorique
import be.suivicompteurs.app.moteur.ConflitConsommation
import be.suivicompteurs.app.moteur.consommationConnue
import be.suivicompteurs.app.moteur.periodeDeLAnnee
import be.suivicompteurs.app.moteur.periodeDuMois
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
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
                // Le relevé qui ouvre la période est daté de la veille de son
                // premier jour : une période court du lendemain d'un relevé au suivant.
                debut = if (precedent == null) debut?.let { LocalDate.parse(it).minusDays(1).toString() } else null,
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

/**
 * Consommations passées d'un coup : les douze mois d'une année civile, ou
 * l'année entière, comme les donne un service de relevé. Les cases reprennent
 * ce qui est déjà connu pour ces périodes exactes ; une case vide est ignorée.
 */
@Composable
fun EcranConsommationsPassees(
    gestion: Gestion,
    compteurId: Long,
    surRetour: () -> Unit,
) {
    val portee = rememberCoroutineScope()
    val messages = remember { SnackbarHostState() }
    val enregistre = stringResource(R.string.enregistre)
    val focus = LocalFocusManager.current
    val langue = LocalConfiguration.current.locales[0]
    val aujourdhui = remember { LocalDate.now() }
    var unite by remember { mutableStateOf("") }
    var existants by remember { mutableStateOf<Map<LocalDate, Double?>?>(null) }
    var annee by remember { mutableIntStateOf(aujourdhui.year - 1) }
    var parMois by remember { mutableStateOf(true) }
    val mois = remember { mutableStateListOf(*Array(12) { "" }) }
    var total by remember { mutableStateOf("") }
    var conflit by remember { mutableStateOf<ConflitConsommation?>(null) }
    var version by remember { mutableIntStateOf(0) }

    LaunchedEffect(compteurId, version) {
        unite = gestion.compteur(compteurId)?.unite.orEmpty()
        existants = gestion.consommations(compteurId)
    }
    LaunchedEffect(annee, existants) {
        val connus = existants ?: return@LaunchedEffect
        for (m in 1..12) {
            mois[m - 1] = consommationConnue(connus, periodeDuMois(YearMonth.of(annee, m), 0.0))?.let(::ecrireNombre).orEmpty()
        }
        total = consommationConnue(connus, periodeDeLAnnee(annee, 0.0))?.let(::ecrireNombre).orEmpty()
        conflit = null
    }

    // Seules les périodes déjà écoulées.
    val moisEcoules = (1..12).filter { YearMonth.of(annee, it).atEndOfMonth() <= aujourdhui }
    val anneeFinie = LocalDate.of(annee, 12, 31) <= aujourdhui
    val saisies = if (parMois) {
        moisEcoules.mapNotNull { m -> lireNombre(mois[m - 1])?.takeIf { it >= 0 }?.let { periodeDuMois(YearMonth.of(annee, m), it) } }
    } else {
        listOfNotNull(lireNombre(total)?.takeIf { it >= 0 && anneeFinie }?.let { periodeDeLAnnee(annee, it) })
    }

    fun enregistrer() = portee.launch {
        try {
            gestion.enregistrerConsommationsPassees(compteurId, saisies)
            // Le clavier se ferme : la confirmation et les cases se voient.
            focus.clearFocus()
            version++
            messages.showSnackbar(enregistre)
        } catch (e: ConflitConsommation) {
            conflit = e
        }
    }

    Cadre(titre = stringResource(R.string.consommations_passees), surRetour = surRetour, messages = messages) {
        if (existants == null) return@Cadre
        Text(
            stringResource(R.string.consommations_passees_explication),
            style = MaterialTheme.typography.bodySmall, color = Couleurs.encre2,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        ChampChoix(
            stringResource(R.string.annee),
            (aujourdhui.year downTo aujourdhui.year - 10).map { it.toString() to it.toString() },
            annee.toString(),
        ) { annee = it.toInt() }
        ChampChoix(
            stringResource(R.string.consommations_decoupage),
            listOf(
                "M" to stringResource(R.string.consommations_par_mois),
                "A" to stringResource(R.string.consommations_par_annee),
            ),
            if (parMois) "M" else "A",
        ) { parMois = it == "M"; conflit = null }
        if (parMois) {
            for (m in moisEcoules) {
                val nom = YearMonth.of(annee, m).month.getDisplayName(TextStyle.FULL_STANDALONE, langue)
                    .replaceFirstChar { it.titlecase(langue) }
                ChampTexte("$nom ($unite)", mois[m - 1], { mois[m - 1] = it; conflit = null }, numerique = true)
            }
        } else if (anneeFinie) {
            ChampTexte(stringResource(R.string.consommation_valeur, unite), total, { total = it; conflit = null }, numerique = true)
        } else {
            Text(stringResource(R.string.consommations_annee_en_cours), style = MaterialTheme.typography.bodySmall)
        }
        conflit?.let {
            Text(
                stringResource(
                    if (it.chevauchement) R.string.consommations_chevauchement else R.string.consommations_trou,
                    jour(it.debut.toString()), jour(it.fin.toString()),
                ),
                color = Couleurs.critique, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        Button(
            enabled = saisies.isNotEmpty(),
            onClick = { enregistrer() },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) { IconeTexte(R.drawable.ic_valider, stringResource(R.string.enregistrer)) }
    }
}
