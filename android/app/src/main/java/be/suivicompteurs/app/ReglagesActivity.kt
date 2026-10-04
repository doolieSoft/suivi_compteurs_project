package be.suivicompteurs.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import be.suivicompteurs.app.databinding.ActivityReglagesBinding
import be.suivicompteurs.app.rappel.Rappels
import com.google.android.material.snackbar.Snackbar

/** Rappels de relevé et langue de l'interface. */
class ReglagesActivity : AppCompatActivity() {

    /**
     * Langues proposées, chacune dans sa propre langue : quelqu'un qui ne lit
     * pas le français doit pouvoir retrouver la sienne.
     */
    private val langues = listOf("" to null, "fr" to "Français", "en" to "English", "nl" to "Nederlands")

    /**
     * Lien de dons, présent dans la seule version publiée sur GitHub : le Play
     * Store n'admet pas de paiement hors de son propre système.
     */
    private fun configurerDons() {
        val lien = BuildConfig.LIEN_DONS
        if (lien.isBlank()) return
        vues.boutonDons.visibility = View.VISIBLE
        vues.boutonDons.setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(lien))) }
        }
    }

    /** Devise des tarifs : celle de la région, ou une autre au choix. */
    private fun configurerDevise() {
        fun afficher() {
            vues.boutonDevise.text = getString(
                R.string.devise_actuelle,
                Monnaie.libelle(this, Monnaie.devise(this)),
            )
        }
        afficher()
        vues.boutonDevise.setOnClickListener {
            val codes = listOf("") + Monnaie.PROPOSEES
            val noms = codes.map { code ->
                if (code.isEmpty()) getString(R.string.devise_region, Monnaie.libelle(this, Monnaie.deRegion(this)))
                else Monnaie.libelle(this, java.util.Currency.getInstance(code))
            }.toTypedArray()
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.devise)
                .setSingleChoiceItems(noms, codes.indexOf(reglages.devise).coerceAtLeast(0)) { dialogue, i ->
                    reglages.devise = codes[i]
                    afficher()
                    dialogue.dismiss()
                }
                .show()
        }
    }

    private fun configurerLangue() {
        val actuelle = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-')
        fun libelle(code: String) = langues.first { it.first == code }.second ?: getString(R.string.langue_appareil)
        vues.boutonLangue.text = getString(R.string.langue_actuelle, libelle(actuelle.takeIf { c -> langues.any { it.first == c } } ?: ""))
        vues.boutonLangue.setOnClickListener {
            val noms = langues.map { libelle(it.first) }.toTypedArray()
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.langue)
                .setSingleChoiceItems(noms, langues.indexOfFirst { it.first == actuelle }.coerceAtLeast(0)) { dialogue, i ->
                    dialogue.dismiss()
                    // Vide : la langue de l'appareil. L'écran se recrée de lui-même.
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(langues[i].first))
                }
                .show()
        }
    }

    private lateinit var vues: ActivityReglagesBinding
    private lateinit var reglages: Reglages

    private val demanderNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { accorde ->
            if (!accorde) {
                vues.interrupteurRappel.isChecked = false
                Snackbar.make(vues.root, R.string.notifications_refusees, Snackbar.LENGTH_LONG)
                    .show()
            }
        }

    override fun onCreate(etat: Bundle?) {
        super.onCreate(etat)
        vues = ActivityReglagesBinding.inflate(layoutInflater)
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

        reglages = Reglages(this)
        configurerLangue()
        configurerDevise()
        configurerDons()
        vues.texteVersion.text = getString(R.string.version_application, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        vues.interrupteurRappel.isChecked = reglages.rappelActif
        vues.champJours.setText(reglages.rappelJours.toString())
        majVisibiliteRappel()
        afficherProchainRappel()

        vues.interrupteurRappel.setOnCheckedChangeListener { _, actif ->
            majVisibiliteRappel()
            if (actif && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                demanderNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        vues.boutonEnregistrer.setOnClickListener { enregistrer() }
    }

    /** « Prochain rappel : le 8 octobre 2026 », d'après l'échéance mémorisée. */
    private fun afficherProchainRappel() {
        val prochain = reglages.rappelProchain
        vues.texteProchainRappel.text = if (reglages.rappelActif && prochain > 0) {
            getString(R.string.prochain_rappel, dateRappel(prochain))
        } else {
            getString(R.string.prochain_rappel_apres_enregistrement)
        }
    }

    private fun dateRappel(millis: Long): String =
        java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            .format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.LONG))

    private fun majVisibiliteRappel() {
        vues.blocJours.visibility =
            if (vues.interrupteurRappel.isChecked) View.VISIBLE else View.GONE
    }

    private fun enregistrer() {
        reglages.rappelActif = vues.interrupteurRappel.isChecked
        vues.champJours.text?.toString()?.toIntOrNull()?.let { reglages.rappelJours = it }
        Rappels.appliquer(this)
        // Le prochain rappel dans la confirmation : l'écran se ferme aussitôt.
        val message = if (reglages.rappelActif && reglages.rappelProchain > 0) {
            getString(R.string.reglages_enregistres_rappel, dateRappel(reglages.rappelProchain))
        } else {
            getString(R.string.reglages_enregistres)
        }
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
        finish()
    }
}
