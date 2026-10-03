package be.suivicompteurs.app.ocr

import android.graphics.Bitmap

/**
 * Variante libre : pas de lecture automatique de l'index.
 *
 * La reconnaissance de texte de Google (ML Kit) est propriétaire, ce que
 * F-Droid exclut. L'index se tape donc à la main ; l'écran de saisie s'ouvre
 * directement sur le formulaire.
 */
class LecteurIndex {

    @Suppress("UNUSED_PARAMETER", "RedundantSuspendModifier")
    suspend fun lire(
        image: Bitmap,
        dernierIndex: Double?,
        decimales: Int,
        incrementMax: Double? = null,
    ): Proposition? = null

    fun fermer() = Unit

    companion object {
        const val DISPONIBLE = false
    }
}
