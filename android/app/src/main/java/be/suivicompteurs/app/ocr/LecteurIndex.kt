package be.suivicompteurs.app.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Lit l'index d'un compteur sur une photo.
 *
 * Cette classe ne fait que deux choses : demander à ML Kit les lignes de texte
 * visibles, puis confier le choix à [SelecteurIndex]. Le modèle de
 * reconnaissance est embarqué dans l'APK, donc la lecture fonctionne sans
 * réseau — c'est ce qui permet de relever un compteur dans une cave.
 *
 * La proposition reste une proposition : elle est affichée pour correction, et
 * jamais enregistrée sans confirmation.
 */
class LecteurIndex {

    /** Écartement des tons de part et d'autre du gris moyen. */
    private val CONTRASTE = 1.8f

    private val reconnaissance =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun lire(
        image: Bitmap,
        dernierIndex: Double?,
        decimales: Int,
        incrementMax: Double? = null,
    ): Proposition? {
        // Deux passes. Les chiffres d'un compteur à tambours sont blancs sur
        // fond noir, alors que la reconnaissance de texte est entraînée sur du
        // sombre sur clair : sur la photo d'essai, elle lisait le numéro de
        // série imprimé en noir et ignorait complètement le cadran. Une copie
        // inversée et contrastée lui rend ces chiffres lisibles.
        val lignes = buildList {
            reconnaitre(image)?.let { addAll(it) }
            val renforcee = renforcer(image)
            reconnaitre(renforcee)?.let { addAll(it) }
            renforcee.recycle()
        }
        if (lignes.isEmpty()) return null
        return SelecteurIndex.choisir(lignes, dernierIndex, decimales, incrementMax)
            // Aucune suite exploitable : on renvoie tout de même ce qui a été
            // lu, sans quoi l'utilisateur reste devant un échec muet.
            ?: Proposition(
                valeur = 0.0,
                brut = "",
                confiance = 0.0,
                explication = "Aucun chiffre reconnu sur la photo.",
                suitesLues = lignes.take(12),
            )
    }

    /**
     * Inverse et contraste l'image, pour rendre lisibles des chiffres clairs
     * sur fond sombre.
     *
     * La transformation appliquée à chaque canal est `sortie = -c × entrée +
     * (127c + 128)` : le signe négatif inverse, le facteur `c` écarte les tons
     * de part et d'autre du gris moyen.
     */
    private fun renforcer(image: Bitmap): Bitmap {
        val sortie = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        val decalage = 127f * CONTRASTE + 128f
        val matrice = ColorMatrix().apply {
            setSaturation(0f)
            postConcat(
                ColorMatrix(
                    floatArrayOf(
                        -CONTRASTE, 0f, 0f, 0f, decalage,
                        0f, -CONTRASTE, 0f, 0f, decalage,
                        0f, 0f, -CONTRASTE, 0f, decalage,
                        0f, 0f, 0f, 1f, 0f,
                    )
                )
            )
        }
        Canvas(sortie).drawBitmap(
            image,
            0f,
            0f,
            Paint().apply { colorFilter = ColorMatrixColorFilter(matrice) },
        )
        return sortie
    }

    private suspend fun reconnaitre(image: Bitmap): List<String>? =
        suspendCoroutine { suite ->
            reconnaissance.process(InputImage.fromBitmap(image, 0))
                .addOnSuccessListener { resultat ->
                    val lignes = mutableListOf<String>()
                    resultat.textBlocks.forEach { bloc ->
                        bloc.lines.forEach { ligne -> lignes.add(ligne.text) }
                    }
                    suite.resume(lignes)
                }
                .addOnFailureListener { suite.resume(null) }
        }

    fun fermer() = reconnaissance.close()
}
