package be.suivicompteurs.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import be.suivicompteurs.app.databinding.ActivityReglagesBinding
import be.suivicompteurs.app.rappel.Rappels
import be.suivicompteurs.app.reseau.Api
import be.suivicompteurs.app.reseau.Resultat
import be.suivicompteurs.app.sync.Synchroniseur
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/**
 * Adresse du serveur, jeton et rappels.
 *
 * L'adresse est modifiable parce que le PC la reçoit par DHCP : elle change au
 * gré de la box. Le bouton d'essai évite de découvrir une faute de frappe au
 * pied du compteur.
 */
class ReglagesActivity : AppCompatActivity() {

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
        vues.champAdresse.setText(reglages.adresseServeur)
        vues.champJeton.setText(reglages.jeton)
        vues.interrupteurRappel.isChecked = reglages.rappelActif
        vues.champJours.setText(reglages.rappelJours.toString())
        majVisibiliteRappel()

        vues.interrupteurRappel.setOnCheckedChangeListener { _, actif ->
            majVisibiliteRappel()
            if (actif && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                demanderNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        vues.boutonTester.setOnClickListener { tester() }
        vues.boutonEnregistrer.setOnClickListener { enregistrer() }
    }

    private fun majVisibiliteRappel() {
        vues.blocJours.visibility =
            if (vues.interrupteurRappel.isChecked) View.VISIBLE else View.GONE
    }

    /** Enregistre provisoirement pour que le test porte sur ce qui est affiché. */
    private fun appliquerSaisie() {
        reglages.adresseServeur = vues.champAdresse.text?.toString().orEmpty()
        reglages.jeton = vues.champJeton.text?.toString().orEmpty()
        reglages.rappelActif = vues.interrupteurRappel.isChecked
        vues.champJours.text?.toString()?.toIntOrNull()?.let { reglages.rappelJours = it }
        // L'adresse normalisée est réaffichée : l'utilisateur voit ce qui sera utilisé.
        vues.champAdresse.setText(reglages.adresseServeur)
    }

    private fun tester() {
        appliquerSaisie()
        if (!reglages.configure) {
            Snackbar.make(vues.root, R.string.reglages_incomplets, Snackbar.LENGTH_LONG).show()
            return
        }
        vues.boutonTester.isEnabled = false
        vues.etatTest.visibility = View.VISIBLE
        vues.etatTest.setText(R.string.test_en_cours)

        lifecycleScope.launch {
            val resultat = Api(reglages).tester()
            vues.boutonTester.isEnabled = true
            when (resultat) {
                is Resultat.Succes -> {
                    vues.etatTest.text = resources.getQuantityString(
                        R.plurals.test_reussi, resultat.valeur, resultat.valeur
                    )
                    vues.etatTest.setTextColor(getColor(R.color.bien))
                }
                is Resultat.Echec -> {
                    vues.etatTest.text = getString(R.string.test_echec, resultat.message)
                    vues.etatTest.setTextColor(getColor(R.color.critique))
                }
            }
        }
    }

    private fun enregistrer() {
        appliquerSaisie()
        Rappels.appliquer(this)
        if (reglages.configure) {
            lifecycleScope.launch {
                Synchroniseur(this@ReglagesActivity).executer()
                finish()
            }
        } else {
            finish()
        }
    }
}
