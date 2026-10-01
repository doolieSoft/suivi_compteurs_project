package be.suivicompteurs.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Affiche la photo et laisse délimiter au doigt la zone à lire.
 *
 * C'est le levier le plus efficace sur la reconnaissance : en ne soumettant
 * que le cadran, on écarte d'un geste le numéro de série, le code-barres, le
 * millésime et le numéro d'agrément — tous des suites de chiffres que rien ne
 * distingue formellement d'un index.
 *
 * La vue dessine elle-même l'image plutôt que d'hériter d'ImageView : elle
 * maîtrise ainsi la correspondance entre coordonnées à l'écran et pixels de la
 * photo, dont dépend entièrement la justesse du recadrage.
 */
class VueCadrage @JvmOverloads constructor(
    contexte: Context,
    attributs: AttributeSet? = null,
    styleParDefaut: Int = 0,
) : View(contexte, attributs, styleParDefaut) {

    private var image: Bitmap? = null

    /** Emplacement de l'image à l'écran, recalculé à chaque mise en page. */
    private val cadreImage = RectF()

    /** Sélection en cours, en coordonnées de la vue. */
    private var selection: RectF? = null
    private var departX = 0f
    private var departY = 0f

    /** Prévenu dès qu'une sélection exploitable existe, ou disparaît. */
    var auChangement: ((Boolean) -> Unit)? = null

    private val voile = Paint().apply { color = Color.argb(140, 0, 0, 0) }
    private val trait = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }
    private val traitInterieur = Paint().apply {
        color = Color.argb(255, 42, 120, 214)
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }

    fun definirImage(nouvelle: Bitmap) {
        image = nouvelle
        selection = null
        auChangement?.invoke(false)
        requestLayout()
        invalidate()
    }

    fun effacerSelection() {
        selection = null
        auChangement?.invoke(false)
        invalidate()
    }

    /**
     * Zone choisie, exprimée en pixels de la photo.
     *
     * `null` tant que rien n'est sélectionné, ou si la zone est trop petite
     * pour contenir des chiffres lisibles.
     */
    fun zoneSelectionnee(): Rect? {
        val photo = image ?: return null
        val zone = selection ?: return null
        if (cadreImage.width() <= 0f || cadreImage.height() <= 0f) return null

        val echelle = photo.width / cadreImage.width()
        val gauche = ((zone.left - cadreImage.left) * echelle).roundToInt()
        val haut = ((zone.top - cadreImage.top) * echelle).roundToInt()
        val droite = ((zone.right - cadreImage.left) * echelle).roundToInt()
        val bas = ((zone.bottom - cadreImage.top) * echelle).roundToInt()

        val recadre = Rect(
            gauche.coerceIn(0, photo.width),
            haut.coerceIn(0, photo.height),
            droite.coerceIn(0, photo.width),
            bas.coerceIn(0, photo.height),
        )
        if (recadre.width() < COTE_MINIMAL || recadre.height() < COTE_MINIMAL) return null
        return recadre
    }

    override fun onLayout(change: Boolean, g: Int, h: Int, d: Int, b: Int) {
        super.onLayout(change, g, h, d, b)
        calculerCadre()
    }

    /** Place l'image au centre, à la plus grande taille qui tienne entièrement. */
    private fun calculerCadre() {
        val photo = image ?: return
        if (width == 0 || height == 0) return
        val facteur = min(
            width.toFloat() / photo.width,
            height.toFloat() / photo.height,
        )
        val l = photo.width * facteur
        val ht = photo.height * facteur
        cadreImage.set(
            (width - l) / 2f,
            (height - ht) / 2f,
            (width + l) / 2f,
            (height + ht) / 2f,
        )
    }

    override fun onDraw(toile: Canvas) {
        super.onDraw(toile)
        val photo = image ?: return
        if (cadreImage.isEmpty) calculerCadre()
        toile.drawBitmap(photo, null, cadreImage, null)

        val zone = selection ?: return
        // Assombrir autour plutôt que surligner dedans : la zone utile garde
        // ses couleurs d'origine, donc son aspect reste jugeable.
        toile.drawRect(cadreImage.left, cadreImage.top, cadreImage.right, zone.top, voile)
        toile.drawRect(cadreImage.left, zone.bottom, cadreImage.right, cadreImage.bottom, voile)
        toile.drawRect(cadreImage.left, zone.top, zone.left, zone.bottom, voile)
        toile.drawRect(zone.right, zone.top, cadreImage.right, zone.bottom, voile)
        toile.drawRect(zone, trait)
        toile.drawRect(zone, traitInterieur)
    }

    override fun onTouchEvent(evenement: MotionEvent): Boolean {
        val photo = image ?: return false
        if (cadreImage.isEmpty) calculerCadre()

        val x = evenement.x.coerceIn(cadreImage.left, cadreImage.right)
        val y = evenement.y.coerceIn(cadreImage.top, cadreImage.bottom)

        when (evenement.action) {
            MotionEvent.ACTION_DOWN -> {
                departX = x
                departY = y
                selection = RectF(x, y, x, y)
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                selection = RectF(
                    min(departX, x), min(departY, y),
                    max(departX, x), max(departY, y),
                )
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                // Un simple appui n'est pas une sélection : on l'efface, sans
                // quoi l'utilisateur se retrouverait avec une zone invisible.
                if (abs(x - departX) < SEUIL_GESTE || abs(y - departY) < SEUIL_GESTE) {
                    selection = null
                }
                auChangement?.invoke(zoneSelectionnee() != null)
            }
        }
        invalidate()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private companion object {
        /** En deçà, le geste est un appui, pas un tracé. */
        const val SEUIL_GESTE = 24f

        /** Côté minimal d'une zone, en pixels de la photo. */
        const val COTE_MINIMAL = 24
    }
}
