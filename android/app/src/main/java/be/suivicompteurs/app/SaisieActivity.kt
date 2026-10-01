package be.suivicompteurs.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import be.suivicompteurs.app.databinding.ActivitySaisieBinding
import be.suivicompteurs.app.donnees.BaseLocale
import be.suivicompteurs.app.donnees.CompteurLocal
import be.suivicompteurs.app.donnees.ReleveLocal
import be.suivicompteurs.app.ocr.LecteurIndex
import be.suivicompteurs.app.ocr.Proposition
import be.suivicompteurs.app.sync.SyncWorker
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Saisie d'un relevé, conçue pour fonctionner sans réseau.
 *
 * Rien n'est envoyé ici : le relevé est écrit dans la base locale, et la
 * synchronisation s'en occupe plus tard. C'est ce qui permet de relever un
 * compteur au fond d'une cave, le PC éteint.
 */
class SaisieActivity : AppCompatActivity() {

    private lateinit var vues: ActivitySaisieBinding
    private var compteur: CompteurLocal? = null
    private var dernierIndexConnu: Double? = null
    private var photo: File? = null
    private var proposition: Proposition? = null
    private var dateChoisie: Date = Date()

    private val lecteur = LecteurIndex()
    private var captureur: ImageCapture? = null

    private val demanderCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { accorde ->
            if (accorde) demarrerCamera() else afficherRefusCamera()
        }

    override fun onCreate(etat: Bundle?) {
        super.onCreate(etat)
        vues = ActivitySaisieBinding.inflate(layoutInflater)
        setContentView(vues.root)
        setSupportActionBar(vues.barre)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        vues.barre.setNavigationOnClickListener { finish() }

        ViewCompat.setOnApplyWindowInsetsListener(vues.root) { vue, fenetre ->
            val marges = fenetre.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            vue.setPadding(marges.left, marges.top, marges.right, marges.bottom)
            WindowInsetsCompat.CONSUMED
        }

        vues.champDate.setText(formatAffichage.format(dateChoisie))
        vues.boutonDeclencher.setOnClickListener { capturer() }
        vues.boutonReprendre.setOnClickListener { reprendrePhoto() }
        vues.boutonSansPhoto.setOnClickListener { basculerSaisieManuelle() }
        vues.boutonEnregistrer.setOnClickListener { enregistrer() }

        charger(intent.getIntExtra(EXTRA_COMPTEUR, -1))
    }

    private fun charger(compteurId: Int) {
        lifecycleScope.launch {
            val base = BaseLocale.obtenir(this@SaisieActivity)
            val trouve = base.compteurs().parId(compteurId)
            if (trouve == null) {
                Snackbar.make(vues.root, R.string.compteur_introuvable, Snackbar.LENGTH_LONG).show()
                finish()
                return@launch
            }
            compteur = trouve

            // Le dernier index local prime sur celui du serveur : il tient
            // compte des relevés déjà saisis mais pas encore transmis.
            val local = base.releves().dernierIndexLocal(compteurId)
            dernierIndexConnu = listOfNotNull(local, trouve.dernierIndex).maxOrNull()

            vues.titre.text = trouve.libelle
            vues.rappelIndex.text = dernierIndexConnu?.let {
                getString(R.string.dernier_index, formater(it), trouve.unite)
            } ?: getString(R.string.aucun_index)
            vues.uniteChamp.text = trouve.unite

            verifierPermissionCamera()
        }
    }

    // --- appareil photo ----------------------------------------------------

    private fun verifierPermissionCamera() {
        val accorde = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (accorde) demarrerCamera() else demanderCamera.launch(Manifest.permission.CAMERA)
    }

    private fun afficherRefusCamera() {
        vues.zoneCamera.visibility = View.GONE
        vues.zoneFormulaire.visibility = View.VISIBLE
        vues.messageOcr.visibility = View.VISIBLE
        vues.messageOcr.setText(R.string.camera_refusee)
    }

    private fun demarrerCamera() {
        val futur = ProcessCameraProvider.getInstance(this)
        futur.addListener({
            val fournisseur = futur.get()
            val apercu = Preview.Builder().build().also {
                it.setSurfaceProvider(vues.apercu.surfaceProvider)
            }
            captureur = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            try {
                fournisseur.unbindAll()
                fournisseur.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, apercu, captureur
                )
            } catch (e: Exception) {
                afficherRefusCamera()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun capturer() {
        val captureur = captureur ?: return
        val dossier = File(filesDir, "photos").apply { mkdirs() }
        val fichier = File(dossier, "${UUID.randomUUID()}.jpg")
        vues.boutonDeclencher.isEnabled = false

        captureur.takePicture(
            ImageCapture.OutputFileOptions.Builder(fichier).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(resultat: ImageCapture.OutputFileResults) {
                    photo = fichier
                    analyser(fichier)
                }

                override fun onError(exception: ImageCaptureException) {
                    vues.boutonDeclencher.isEnabled = true
                    Snackbar.make(vues.root, R.string.photo_echouee, Snackbar.LENGTH_LONG).show()
                }
            },
        )
    }

    private fun analyser(fichier: File) {
        vues.zoneCamera.visibility = View.GONE
        vues.zoneFormulaire.visibility = View.VISIBLE
        vues.messageOcr.visibility = View.VISIBLE
        vues.messageOcr.setText(R.string.ocr_en_cours)

        lifecycleScope.launch {
            val image = withContext(Dispatchers.IO) { chargerRedresse(fichier) }
            vues.apercuPhoto.setImageBitmap(image)
            vues.apercuPhoto.visibility = View.VISIBLE

            val lecture = image?.let {
                lecteur.lire(it, dernierIndexConnu, compteur?.decimales ?: 3)
            }
            proposition = lecture
            vues.boutonDeclencher.isEnabled = true

            if (lecture == null) {
                vues.messageOcr.setText(R.string.ocr_echec)
                vues.champIndex.requestFocus()
            } else {
                vues.champIndex.setText(formater(lecture.valeur))
                vues.messageOcr.text = getString(
                    R.string.ocr_resultat, lecture.brut, lecture.explication
                )
                vues.messageOcr.setTextColor(
                    ContextCompat.getColor(
                        this@SaisieActivity,
                        if (lecture.confiance >= 0.8) R.color.bien else R.color.attention_texte,
                    )
                )
                // Le champ est pré-sélectionné : une correction se fait d'un geste.
                vues.champIndex.selectAll()
            }
        }
    }

    /**
     * Charge la photo en corrigeant son orientation.
     *
     * Les appareils photo enregistrent l'orientation dans les métadonnées EXIF
     * plutôt que de pivoter les pixels ; sans correction, la reconnaissance
     * travaillerait sur une image couchée et ne verrait aucun chiffre.
     */
    private fun chargerRedresse(fichier: File): Bitmap? {
        val options = BitmapFactory.Options().apply { inSampleSize = 2 }
        val brut = BitmapFactory.decodeFile(fichier.absolutePath, options) ?: return null
        val rotation = when (
            ExifInterface(fichier.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        ) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rotation == 0f) return brut
        val matrice = Matrix().apply { postRotate(rotation) }
        return Bitmap.createBitmap(brut, 0, 0, brut.width, brut.height, matrice, true)
    }

    private fun reprendrePhoto() {
        photo?.delete()
        photo = null
        proposition = null
        vues.apercuPhoto.visibility = View.GONE
        vues.zoneFormulaire.visibility = View.GONE
        vues.zoneCamera.visibility = View.VISIBLE
        verifierPermissionCamera()
    }

    private fun basculerSaisieManuelle() {
        vues.zoneCamera.visibility = View.GONE
        vues.zoneFormulaire.visibility = View.VISIBLE
        vues.messageOcr.visibility = View.GONE
        vues.champIndex.requestFocus()
    }

    // --- enregistrement ----------------------------------------------------

    private fun enregistrer() {
        val compteur = compteur ?: return
        val saisi = vues.champIndex.text?.toString()?.replace(',', '.')?.trim()
        val valeur = saisi?.toDoubleOrNull()

        if (valeur == null) {
            vues.champIndexConteneur.error = getString(R.string.index_obligatoire)
            return
        }
        val plancher = dernierIndexConnu
        if (plancher != null && valeur < plancher) {
            // Même contrôle que côté serveur : autant le dire tout de suite,
            // plutôt que de laisser partir un relevé qui sera refusé.
            vues.champIndexConteneur.error =
                getString(R.string.index_en_recul, formater(plancher))
            return
        }
        vues.champIndexConteneur.error = null

        lifecycleScope.launch {
            val base = BaseLocale.obtenir(this@SaisieActivity)
            base.releves().ajouter(
                ReleveLocal(
                    reference = UUID.randomUUID().toString(),
                    compteurId = compteur.id,
                    compteurLibelle = compteur.libelle,
                    unite = compteur.unite,
                    date = formatIso.format(dateChoisie),
                    index = valeur,
                    indexOcr = proposition?.valeur,
                    commentaire = vues.champCommentaire.text?.toString()?.trim().orEmpty(),
                    cheminPhoto = photo?.absolutePath,
                )
            )
            // Le système l'enverra dès qu'un réseau sera disponible.
            SyncWorker.declencher(this@SaisieActivity)

            val progression = plancher?.let { valeur - it }
            val message = if (progression != null) {
                getString(R.string.releve_enregistre_avec_ecart, formater(progression), compteur.unite)
            } else {
                getString(R.string.releve_enregistre)
            }
            setResult(RESULT_OK)
            Snackbar.make(vues.root, message, Snackbar.LENGTH_SHORT).show()
            vues.root.postDelayed({ finish() }, 900)
        }
    }

    private fun formater(valeur: Double): String =
        String.format(Locale.FRANCE, "%.3f", valeur).trimEnd('0').trimEnd(',', '.')

    override fun onDestroy() {
        super.onDestroy()
        lecteur.fermer()
    }

    companion object {
        const val EXTRA_COMPTEUR = "compteur"
        private val formatIso = SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE)
        private val formatAffichage = SimpleDateFormat("d MMMM yyyy", Locale.FRANCE)
    }
}
