package be.suivicompteurs.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
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
    private var derniereDate: Long? = null
    private var photo: File? = null
    private var proposition: Proposition? = null
    private var dateChoisie: Date = Date()

    private val lecteur = LecteurIndex()
    private var captureur: ImageCapture? = null
    private var camera: Camera? = null
    private var lampeAllumee = false
    private var imageAnalysee: Bitmap? = null

    /**
     * Choix d'une photo déjà prise.
     *
     * Utile quand on a photographié le compteur sans ouvrir l'application —
     * et c'est aussi la seule façon de rejouer une lecture qui s'est trompée,
     * sur l'image même qui a posé problème.
     */
    private val choisirPhoto = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) importer(uri)
    }

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
        vues.boutonLampe.setOnClickListener { appliquerLampe(!lampeAllumee) }
        vues.boutonLireZone.setOnClickListener { relireLaZone() }
        vues.apercuPhoto.auChangement = { active -> vues.boutonLireZone.isEnabled = active }
        vues.boutonGalerie.setOnClickListener {
            choisirPhoto.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
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

            val dateLocale = base.releves().dernierReleveLocal(compteurId)?.date
            derniereDate = listOfNotNull(dateLocale, trouve.dernierReleve)
                .mapNotNull { runCatching { formatIso.parse(it)?.time }.getOrNull() }
                .maxOrNull()

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
                camera = fournisseur.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, apercu, captureur
                )
                // Les compteurs vivent en cave : sans lampe, la photo est trop
                // sombre pour que les chiffres du cadran ressortent.
                vues.boutonLampe.isEnabled = camera?.cameraInfo?.hasFlashUnit() == true
                if (vues.boutonLampe.isEnabled) {
                    appliquerLampe(lampeAllumee)
                } else {
                    vues.boutonLampe.setText(R.string.lampe_indisponible)
                }
            } catch (e: Exception) {
                afficherRefusCamera()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun appliquerLampe(allumer: Boolean) {
        val commande = camera?.cameraControl ?: return
        lampeAllumee = allumer
        commande.enableTorch(allumer)
        vues.boutonLampe.setText(
            if (allumer) R.string.lampe_eteindre else R.string.lampe_allumer
        )
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

    /** Recopie la photo choisie dans l'application, puis l'analyse. */
    private fun importer(uri: Uri) {
        val dossier = File(filesDir, "photos").apply { mkdirs() }
        val fichier = File(dossier, "${UUID.randomUUID()}.jpg")
        try {
            contentResolver.openInputStream(uri).use { entree ->
                if (entree == null) throw IllegalStateException("image illisible")
                fichier.outputStream().use { sortie -> entree.copyTo(sortie) }
            }
        } catch (e: Exception) {
            Snackbar.make(vues.root, R.string.photo_echouee, Snackbar.LENGTH_LONG).show()
            return
        }
        photo = fichier
        analyser(fichier)
    }

    private fun analyser(fichier: File) {
        vues.zoneCamera.visibility = View.GONE
        vues.zoneFormulaire.visibility = View.VISIBLE
        vues.messageOcr.visibility = View.VISIBLE
        vues.messageOcr.setText(R.string.ocr_en_cours)

        lifecycleScope.launch {
            val image = withContext(Dispatchers.IO) { chargerRedresse(fichier) }
            imageAnalysee = image
            if (image != null) {
                vues.apercuPhoto.definirImage(image)
                vues.apercuPhoto.visibility = View.VISIBLE
                vues.consigneCadrage.visibility = View.VISIBLE
                vues.boutonLireZone.visibility = View.VISIBLE
            }
            // La lampe n'a plus d'objet une fois la photo prise.
            if (lampeAllumee) appliquerLampe(false)

            val lecture = image?.let {
                lecteur.lire(
                    it,
                    dernierIndexConnu,
                    compteur?.decimales ?: 3,
                    progressionPlausibleMax(),
                )
            }
            proposition = lecture
            vues.boutonDeclencher.isEnabled = true
            afficherLecture(lecture)
        }
    }

    /**
     * Reporte à l'écran ce que la reconnaissance a donné.
     *
     * Appelée après la première lecture comme après une relecture sur zone
     * entourée : la façon de présenter un résultat douteux ne doit pas
     * dépendre du chemin par lequel on y est arrivé.
     */
    private fun afficherLecture(lecture: Proposition?) {
        proposition = lecture
        vues.boutonDeclencher.isEnabled = true

        if (lecture == null || lecture.confiance <= 0.0) {
            vues.messageOcr.text = buildString {
                append(getString(R.string.ocr_echec))
                lecture?.suitesLues?.takeIf { it.isNotEmpty() }?.let {
                    appendLine()
                    appendLine()
                    append("Lu sur la photo : ")
                    append(it.joinToString(" · "))
                }
            }
            vues.messageOcr.setTextColor(
                ContextCompat.getColor(this@SaisieActivity, R.color.critique)
            )
            vues.champIndex.requestFocus()
        } else if (lecture.confiance <= SEUIL_PRE_REMPLISSAGE) {
            // Une lecture que l'on sait fausse ne doit pas atterrir dans le
            // champ : il suffirait d'un appui sur « Enregistrer » pour
            // qu'un numéro de série entre dans douze ans d'historique. Le
            // message explique, le champ reste vide et prend le curseur.
            vues.champIndex.setText("")
            vues.messageOcr.text = buildString {
                append(lecture.explication)
                appendLine()
                appendLine()
                append("Lu sur la photo : ")
                append(lecture.suitesLues.take(8).joinToString(" · "))
            }
            vues.messageOcr.setTextColor(
                ContextCompat.getColor(this@SaisieActivity, R.color.critique)
            )
            vues.champIndex.requestFocus()
        } else {
            vues.champIndex.setText(formater(lecture.valeur))
            vues.messageOcr.text = buildString {
                append(getString(R.string.ocr_resultat, lecture.brut, lecture.explication))
                // Quand la proposition est douteuse, montrer tout ce qui a
                // été lu : c'est la seule façon de comprendre pourquoi elle
                // l'est, et de savoir s'il faut recadrer ou simplement taper.
                if (lecture.confiance < 0.5 && lecture.suitesLues.size > 1) {
                    appendLine()
                    appendLine()
                    append("Autres suites lues : ")
                    append(
                        lecture.suitesLues
                            .filter { it != lecture.brut }
                            .take(8)
                            .joinToString(" · ")
                    )
                }
            }
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

    /**
     * Relance la reconnaissance sur la seule zone entourée.
     *
     * C'est le levier le plus efficace : une plaque de compteur porte un
     * numéro de série, un code-barres, un millésime et un numéro d'agrément,
     * tous indiscernables d'un index pour un lecteur de texte. Les écarter du
     * champ de vision vaut mieux que tout départage a posteriori.
     */
    private fun relireLaZone() {
        val complete = imageAnalysee ?: return
        val zone = vues.apercuPhoto.zoneSelectionnee() ?: return

        val recadre = Bitmap.createBitmap(
            complete, zone.left, zone.top, zone.width(), zone.height()
        )
        vues.messageOcr.visibility = View.VISIBLE
        vues.messageOcr.setText(R.string.ocr_en_cours)
        vues.boutonLireZone.isEnabled = false

        lifecycleScope.launch {
            val lecture = lecteur.lire(
                recadre,
                dernierIndexConnu,
                compteur?.decimales ?: 3,
                progressionPlausibleMax(),
            )
            recadre.recycle()
            vues.boutonLireZone.isEnabled = true
            afficherLecture(lecture)
        }
    }

    /**
     * Progression que l'index peut raisonnablement avoir faite depuis le
     * dernier relevé.
     *
     * C'est ce qui distingue l'index du numéro de série imprimé sur la plaque :
     * les deux se lisent comme des nombres crédibles, mais l'un seul respecte
     * le rythme du compteur. La marge est large — un coup de froid peut
     * dépasser le record observé — car cette borne ne sert qu'à nuancer la
     * confiance affichée, jamais à écarter une lecture d'office.
     */
    private fun progressionPlausibleMax(): Double? {
        val compteur = compteur ?: return null
        val debit = compteur.consoJournaliereMax
        if (debit <= 0) return null

        val depuis = derniereDate ?: return null
        val jours = ((System.currentTimeMillis() - depuis) / 86_400_000L)
            .coerceAtLeast(1L)
        return jours * debit * MARGE_PROGRESSION + TOLERANCE_ABSOLUE
    }

    /**
     * Charge la photo en corrigeant son orientation.
     *
     * Les appareils photo enregistrent l'orientation dans les métadonnées EXIF
     * plutôt que de pivoter les pixels ; sans correction, la reconnaissance
     * travaillerait sur une image couchée et ne verrait aucun chiffre.
     */
    private fun chargerRedresse(fichier: File): Bitmap? {
        // Mesure d'abord, décode ensuite. La version précédente divisait
        // systématiquement la taille par deux : sur une photo où les chiffres
        // du cadran ne font déjà qu'une trentaine de pixels de haut, cela
        // suffisait à les rendre illisibles. On ne réduit donc que ce qui
        // dépasse vraiment, et jamais en deçà du seuil utile.
        val mesure = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(fichier.absolutePath, mesure)
        val cote = maxOf(mesure.outWidth, mesure.outHeight)

        var reduction = 1
        while (cote / (reduction * 2) >= COTE_UTILE_MIN) {
            reduction *= 2
        }

        val options = BitmapFactory.Options().apply { inSampleSize = reduction }
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
                    annuel = vues.caseAnnuel.isChecked,
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

        /** Un hiver rigoureux peut dépasser le record déjà observé. */
        private const val MARGE_PROGRESSION = 2.0

        /** Évite de trouver suspecte une progression normale relevée le lendemain. */
        private const val TOLERANCE_ABSOLUE = 5.0

        /**
         * En deçà, la lecture n'est pas reportée dans le champ. Au-dessus, elle
         * l'est mais reste sélectionnée, prête à être corrigée d'un geste.
         */
        private const val SEUIL_PRE_REMPLISSAGE = 0.25

        /**
         * Côté le plus long en deçà duquel on ne réduit plus l'image.
         *
         * Au-delà, réduire allège le traitement sans perte utile ; en deçà, on
         * rognerait sur la seule chose qui compte, la finesse des chiffres.
         */
        private const val COTE_UTILE_MIN = 2200
        private val formatIso = SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE)
        private val formatAffichage = SimpleDateFormat("d MMMM yyyy", Locale.FRANCE)
    }
}
