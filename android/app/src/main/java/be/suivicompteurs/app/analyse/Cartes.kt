package be.suivicompteurs.app.analyse

import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import be.suivicompteurs.app.gestion.Couleurs

/** Carte blanche titrée, comme les « cartes » du site. */
@Composable
fun Carte(titre: String? = null, icone: Int? = null, couleurIcone: Color? = null, contenu: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = Couleurs.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            titre?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (icone != null) {
                        Icon(painterResource(icone), null, Modifier.padding(end = 8.dp).size(22.dp), tint = couleurIcone ?: Couleurs.encre2)
                    }
                    Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Box(Modifier.padding(top = 8.dp))
            }
            contenu()
        }
    }
}

/** Grand chiffre et ses lignes de détail. */
@Composable
fun Tuile(titre: String, valeur: String, details: List<String>, couleurValeur: Color = Couleurs.encre) {
    Carte {
        Text(titre, style = MaterialTheme.typography.labelLarge, color = Couleurs.encre2)
        Text(valeur, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = couleurValeur)
        details.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = Couleurs.encre3) }
    }
}

/** Tableau simple : en-tête puis lignes, colonnes numériques alignées à droite. */
@Composable
fun Tableau(entetes: List<String>, lignes: List<List<String>>, poids: List<Float>? = null) {
    val p = poids ?: entetes.map { 1f }
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            entetes.forEachIndexed { i, e ->
                Text(
                    e, Modifier.weight(p[i]), style = MaterialTheme.typography.labelSmall, color = Couleurs.encre3,
                    textAlign = if (i == 0) TextAlign.Start else TextAlign.End,
                )
            }
        }
        HorizontalDivider()
        lignes.forEach { l ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                l.forEachIndexed { i, v ->
                    Text(
                        v, Modifier.weight(p[i]), style = MaterialTheme.typography.bodySmall,
                        textAlign = if (i == 0) TextAlign.Start else TextAlign.End,
                    )
                }
            }
        }
    }
}

@Composable
fun Chargement() {
    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}
