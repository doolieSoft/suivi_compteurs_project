package be.suivicompteurs.app.analyse

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import be.suivicompteurs.app.gestion.Couleurs
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/*
 * Graphiques dessinés directement en Compose : courbes, nuages de points et
 * barres groupées. Juste ce qu'exigent les écrans d'analyse — axes gradués,
 * légende, valeurs lues d'un appui — sans bibliothèque externe.
 */

enum class Trace { LIGNE, POINTS }

data class Serie(
    val nom: String,
    val couleur: Color,
    val points: List<Pair<Double, Double>>,
    val trace: Trace = Trace.LIGNE,
    val epaisseur: Float = 2.5f,
)

data class GroupeBarres(val nom: String, val couleur: Color, val valeurs: List<Double?>)

private val styleAxe = TextStyle(color = Couleurs.encre3, fontSize = 10.sp)
private val couleurGrille = Color(0xFFE6E5E0)

/** Graduations « rondes » couvrant [min, max] : 0, 250, 500… */
internal fun graduations(min: Double, max: Double, cible: Int = 5): List<Double> {
    if (max <= min) return listOf(min)
    val brut = (max - min) / cible
    val puissance = 10.0.pow(floor(log10(brut)))
    val pas = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * puissance }.first { it >= brut }
    val debut = floor(min / pas) * pas
    val fin = ceil(max / pas) * pas
    return generateSequence(debut) { it + pas }.takeWhile { it <= fin + pas / 2 }.toList()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Legende(elements: List<Pair<String, Color>>) {
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        elements.forEach { (nom, couleur) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(couleur, CircleShape))
                Text(" $nom", style = MaterialTheme.typography.bodySmall, color = Couleurs.encre2)
            }
        }
    }
}

/** Repère commun aux deux sortes de graphiques : marges, grille horizontale. */
private class Repere(
    val gauche: Float,
    val haut: Float,
    val largeur: Float,
    val hauteur: Float,
    val yMin: Double,
    val yMax: Double,
) {
    fun y(valeur: Double) = haut + hauteur - ((valeur - yMin) / (yMax - yMin)).toFloat() * hauteur
}

private fun DrawScope.repere(
    mesure: TextMeasurer,
    graduationsY: List<Double>,
    etiquetteY: (Double) -> String,
    basReserve: Float,
): Repere {
    val largeurEtiquettes = graduationsY.maxOf { mesure.measure(etiquetteY(it), styleAxe).size.width }.toFloat()
    val r = Repere(
        gauche = largeurEtiquettes + 8f,
        haut = 8f,
        largeur = size.width - largeurEtiquettes - 12f,
        hauteur = size.height - basReserve - 8f,
        yMin = graduationsY.first(),
        yMax = graduationsY.last(),
    )
    for (g in graduationsY) {
        val y = r.y(g)
        drawLine(couleurGrille, Offset(r.gauche, y), Offset(r.gauche + r.largeur, y), 1f)
        val texte = mesure.measure(etiquetteY(g), styleAxe)
        drawText(texte, topLeft = Offset(r.gauche - texte.size.width - 6f, y - texte.size.height / 2f))
    }
    return r
}

/**
 * Courbes ou nuages de points sur des axes numériques. Un appui affiche,
 * pour chaque série, la valeur la plus proche de l'endroit touché.
 */
@Composable
fun GraphiqueXY(
    series: List<Serie>,
    etiquetteX: (Double) -> String,
    etiquetteY: (Double) -> String,
    modifier: Modifier = Modifier,
    hauteur: Dp = 220.dp,
    xMin: Double? = null,
    xMax: Double? = null,
    graduationsX: List<Double>? = null,
    depuisZero: Boolean = true,
    legende: Boolean = true,
) {
    val tous = series.flatMap { it.points }
    if (tous.isEmpty()) return
    val mesure = rememberTextMeasurer()
    var touche by remember { mutableStateOf<Float?>(null) }
    val x0 = xMin ?: tous.minOf { it.first }
    val x1 = (xMax ?: tous.maxOf { it.first }).let { if (it <= x0) x0 + 1 else it }
    val gy = graduations(if (depuisZero) min(0.0, tous.minOf { it.second }) else tous.minOf { it.second }, tous.maxOf { it.second })
    val gx = graduationsX ?: graduations(x0, x1).filter { it in x0..x1 }

    Column(modifier) {
        Canvas(
            Modifier.fillMaxWidth().height(hauteur).pointerInput(series) {
                detectTapGestures { position -> touche = if (touche == null) position.x else null }
            }
        ) {
            val r = repere(mesure, gy, etiquetteY, basReserve = 20f)
            fun x(valeur: Double) = r.gauche + ((valeur - x0) / (x1 - x0)).toFloat() * r.largeur

            for (g in gx) {
                val texte = mesure.measure(etiquetteX(g), styleAxe)
                drawText(texte, topLeft = Offset((x(g) - texte.size.width / 2f).coerceIn(0f, size.width - texte.size.width), r.haut + r.hauteur + 4f))
            }
            for (s in series) {
                val pts = s.points.sortedBy { it.first }.map { Offset(x(it.first), r.y(it.second)) }
                when (s.trace) {
                    Trace.LIGNE -> pts.zipWithNext().forEach { (a, b) -> drawLine(s.couleur, a, b, s.epaisseur) }
                    Trace.POINTS -> pts.forEach { drawCircle(s.couleur, 5f, it) }
                }
            }

            touche?.let { tx ->
                val vx = x0 + ((tx - r.gauche) / r.largeur).coerceIn(0f, 1f) * (x1 - x0)
                drawLine(Couleurs.encre3, Offset(tx, r.haut), Offset(tx, r.haut + r.hauteur), 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                val lignes = series.mapNotNull { s ->
                    s.points.minByOrNull { abs(it.first - vx) }?.let { p ->
                        "${s.nom} · ${etiquetteX(p.first)} : ${etiquetteY(p.second)}"
                    }
                }
                bulle(mesure, lignes, tx, r.haut)
            }
        }
        if (legende && series.size > 1) Legende(series.map { it.nom to it.couleur })
    }
}

/** Barres groupées par catégorie, avec une ligne de référence facultative. */
@Composable
fun GraphiqueBarres(
    categories: List<String>,
    groupes: List<GroupeBarres>,
    etiquetteY: (Double) -> String,
    modifier: Modifier = Modifier,
    hauteur: Dp = 220.dp,
    reference: Pair<String, Double>? = null,
) {
    val valeurs = groupes.flatMap { it.valeurs }.filterNotNull() + listOfNotNull(reference?.second)
    if (categories.isEmpty() || valeurs.isEmpty()) return
    val mesure = rememberTextMeasurer()
    var choisie by remember { mutableStateOf<Int?>(null) }
    // Marge gauche réellement dessinée, relue par la détection d'appui.
    val gaucheDessinee = remember { floatArrayOf(40f) }
    val gy = graduations(min(0.0, valeurs.min()), valeurs.max())

    Column(modifier) {
        Canvas(
            Modifier.fillMaxWidth().height(hauteur).pointerInput(categories) {
                detectTapGestures { position ->
                    val gauche = gaucheDessinee[0]
                    val i = ((position.x - gauche) / ((size.width - gauche) / categories.size)).toInt()
                    choisie = if (choisie == i) null else i.coerceIn(0, categories.size - 1)
                }
            }
        ) {
            val r = repere(mesure, gy, etiquetteY, basReserve = 20f)
            gaucheDessinee[0] = r.gauche
            val largeurCategorie = r.largeur / categories.size
            val largeurBarre = largeurCategorie * 0.8f / groupes.size
            // Une étiquette sur deux, trois… quand elles se chevaucheraient.
            val largeurEtiquette = categories.maxOf { mesure.measure(it, styleAxe).size.width }
            val saut = max(1, ceil((largeurEtiquette + 6f) / largeurCategorie).toInt())

            categories.forEachIndexed { i, nom ->
                val debut = r.gauche + i * largeurCategorie + largeurCategorie * 0.1f
                groupes.forEachIndexed { j, g ->
                    val v = g.valeurs.getOrNull(i) ?: return@forEachIndexed
                    val haut = r.y(max(v, 0.0))
                    val bas = r.y(min(v, 0.0))
                    drawRect(
                        if (choisie == null || choisie == i) g.couleur else g.couleur.copy(alpha = 0.35f),
                        topLeft = Offset(debut + j * largeurBarre, haut),
                        size = Size(largeurBarre * 0.92f, max(bas - haut, 1f)),
                    )
                }
                if (i % saut == 0) {
                    val texte = mesure.measure(nom, styleAxe)
                    drawText(texte, topLeft = Offset(r.gauche + i * largeurCategorie + (largeurCategorie - texte.size.width) / 2f, r.haut + r.hauteur + 4f))
                }
            }
            reference?.let { (_, v) ->
                val y = r.y(v)
                drawLine(Couleurs.encre2, Offset(r.gauche, y), Offset(r.gauche + r.largeur, y), 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            }
            choisie?.let { i ->
                val lignes = listOf(categories[i]) + groupes.mapNotNull { g ->
                    g.valeurs.getOrNull(i)?.let { "${g.nom} : ${etiquetteY(it)}" }
                } + listOfNotNull(reference?.let { "${it.first} : ${etiquetteY(it.second)}" })
                bulle(mesure, lignes, r.gauche + (i + 0.5f) * largeurCategorie, r.haut)
            }
        }
        val elements = groupes.map { it.nom to it.couleur } + listOfNotNull(reference?.let { it.first to Couleurs.encre2 })
        if (elements.size > 1) Legende(elements)
    }
}

/** Petit cartouche de valeurs, posé près du point touché sans sortir du cadre. */
private fun DrawScope.bulle(mesure: TextMeasurer, lignes: List<String>, x: Float, haut: Float) {
    if (lignes.isEmpty()) return
    val textes = lignes.map { mesure.measure(it, TextStyle(color = Couleurs.encre, fontSize = 11.sp)) }
    val largeur = textes.maxOf { it.size.width } + 16f
    val hauteurTotale = textes.sumOf { it.size.height } + 12f
    val gauche = (x + 8f).let { if (it + largeur > size.width) x - largeur - 8f else it }.coerceAtLeast(0f)
    drawRect(Color(0xF2FFFFFF), Offset(gauche, haut), Size(largeur, hauteurTotale.toFloat()))
    drawRect(couleurGrille, Offset(gauche, haut), Size(largeur, hauteurTotale.toFloat()), style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
    var y = haut + 6f
    for (t in textes) {
        drawText(t, topLeft = Offset(gauche + 8f, y))
        y += t.size.height
    }
}
