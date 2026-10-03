package be.suivicompteurs.app.gestion

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import be.suivicompteurs.app.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Mêmes teintes que le reste de l'application et que le site. */
object Couleurs {
    val eau = Color(0xFF2A78D6)
    val gaz = Color(0xFFEB6834)
    val elec = Color(0xFFEDA100)
    val mazout = Color(0xFF8A5A2B)
    val primaire = Color(0xFF2A78D6)
    val encre = Color(0xFF0B0B0B)
    val encre2 = Color(0xFF52514E)
    val encre3 = Color(0xFF898781)
    val plan = Color(0xFFF9F9F7)
    val surface = Color(0xFFFCFCFB)
    val critique = Color(0xFFD03B3B)
    val bien = Color(0xFF006300)

    fun energie(code: String) = when (code) {
        "EAU" -> eau
        "GAZ" -> gaz
        "MAZ" -> mazout
        else -> elec
    }
}

@Composable
fun Theme(contenu: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Couleurs.primaire,
            primaryContainer = Color(0xFFDCE8F8),
            onPrimaryContainer = Couleurs.primaire,
            secondaryContainer = Color(0xFFDCE8F8),
            background = Couleurs.plan,
            surface = Couleurs.surface,
            onBackground = Couleurs.encre,
            onSurface = Couleurs.encre,
            error = Couleurs.critique,
        ),
        content = contenu,
    )
}

private val formatDate = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

fun jour(iso: String?): String = iso?.let { LocalDate.parse(it).format(formatDate) } ?: ""

/** Nombre saisi au clavier : la virgule décimale est acceptée. */
fun lireNombre(texte: String): Double? = texte.trim().replace(',', '.').replace(" ", "").toDoubleOrNull()

/** Nombre affiché à la manière de la langue de l'appareil : « 24 992,4 ». */
fun afficherNombre(valeur: Double, decimalesMax: Int = 3): String =
    java.text.NumberFormat.getNumberInstance().apply { maximumFractionDigits = decimalesMax }.format(valeur)

fun ecrireNombre(valeur: Double?): String = when {
    valeur == null -> ""
    valeur % 1.0 == 0.0 -> valeur.toLong().toString()
    else -> valeur.toString()
}

@Composable
fun ChampTexte(
    libelle: String,
    valeur: String,
    surChangement: (String) -> Unit,
    modifier: Modifier = Modifier,
    numerique: Boolean = false,
    erreur: String? = null,
    lignes: Int = 1,
) {
    OutlinedTextField(
        value = valeur,
        onValueChange = surChangement,
        label = { Text(libelle) },
        singleLine = lignes == 1,
        minLines = lignes,
        isError = erreur != null,
        supportingText = erreur?.let { { Text(it) } },
        keyboardOptions = if (numerique) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

/** Champ de date : un appui ouvre le calendrier. [facultatif] permet de l'effacer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChampDate(
    libelle: String,
    valeur: String?,
    surChangement: (String?) -> Unit,
    facultatif: Boolean = false,
) {
    var ouvert by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = jour(valeur),
            onValueChange = {},
            readOnly = true,
            label = { Text(libelle) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
        // Couvre le champ : un appui n'importe où ouvre le calendrier.
        Box(Modifier.matchParentSize().clickable { ouvert = true })
    }
    if (ouvert) {
        val initial = valeur?.let { LocalDate.parse(it) } ?: LocalDate.now()
        val etat = rememberDatePickerState(
            initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { ouvert = false },
            confirmButton = {
                TextButton(onClick = {
                    etat.selectedDateMillis?.let {
                        surChangement(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString())
                    }
                    ouvert = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                Row {
                    if (facultatif) {
                        TextButton(onClick = { surChangement(null); ouvert = false }) {
                            Text(stringResource(R.string.effacer))
                        }
                    }
                    TextButton(onClick = { ouvert = false }) { Text(stringResource(R.string.annuler)) }
                }
            },
        ) { DatePicker(state = etat) }
    }
}

/** Liste déroulante de choix (code, libellé). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChampChoix(
    libelle: String,
    choix: List<Pair<String, String>>,
    valeur: String,
    surChangement: (String) -> Unit,
) {
    var ouvert by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = ouvert, onExpandedChange = { ouvert = it }) {
        OutlinedTextField(
            value = choix.firstOrNull { it.first == valeur }?.second.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text(libelle) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = ouvert) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth().padding(vertical = 4.dp),
        )
        ExposedDropdownMenu(expanded = ouvert, onDismissRequest = { ouvert = false }) {
            choix.forEach { (code, texte) ->
                DropdownMenuItem(text = { Text(texte) }, onClick = { surChangement(code); ouvert = false })
            }
        }
    }
}

@Composable
fun TitreSection(texte: String, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            texte,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        action?.invoke()
    }
    HorizontalDivider()
}

/** Ligne d'une liste : un repère de couleur facultatif, un titre, un détail. */
@Composable
fun LigneListe(
    titre: String,
    detail: String? = null,
    couleur: Color? = null,
    surAppui: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = surAppui).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (couleur != null) {
            Box(Modifier.width(4.dp).height(36.dp).background(couleur))
        }
        Column(Modifier.weight(1f)) {
            Text(titre, style = MaterialTheme.typography.bodyLarge, color = Couleurs.encre)
            if (!detail.isNullOrEmpty()) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = Couleurs.encre3)
            }
        }
        Text("›", color = Couleurs.encre3, style = MaterialTheme.typography.titleLarge)
    }
    HorizontalDivider(color = Couleurs.plan)
}

@Composable
fun ConfirmerSuppression(message: String, surConfirmation: () -> Unit, surAbandon: () -> Unit) {
    AlertDialog(
        onDismissRequest = surAbandon,
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = surConfirmation) {
                Text(stringResource(R.string.supprimer), color = Couleurs.critique)
            }
        },
        dismissButton = { TextButton(onClick = surAbandon) { Text(stringResource(R.string.annuler)) } },
    )
}

@Composable
fun Espace() = Spacer(Modifier.padding(4.dp))
