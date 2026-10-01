package be.suivicompteurs.app.ocr

import android.graphics.Bitmap
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

    private val reconnaissance =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun lire(
        image: Bitmap,
        dernierIndex: Double?,
        decimales: Int,
        incrementMax: Double? = null,
    ): Proposition? {
        val lignes = reconnaitre(image) ?: return null
        return SelecteurIndex.choisir(lignes, dernierIndex, decimales, incrementMax)
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
