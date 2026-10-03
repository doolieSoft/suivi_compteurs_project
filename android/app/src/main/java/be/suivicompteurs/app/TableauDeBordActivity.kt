package be.suivicompteurs.app

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import be.suivicompteurs.app.analyse.Analyse
import be.suivicompteurs.app.analyse.ResumeLigne
import be.suivicompteurs.app.analyse.ResumeMaison
import be.suivicompteurs.app.analyse.Textes
import be.suivicompteurs.app.databinding.ActivityTableauDeBordBinding
import be.suivicompteurs.app.databinding.ItemTuileBinding
import be.suivicompteurs.app.moteur.Energie
import be.suivicompteurs.app.moteur.Niveau
import be.suivicompteurs.app.sync.Synchroniseur
import kotlinx.coroutines.launch

/**
 * Tableau de bord calculé sur l'appareil.
 *
 * Les chiffres viennent du moteur embarqué, à partir de la copie locale des
 * données : l'écran s'affiche identique avec ou sans réseau. Les graphiques
 * détaillés restent, pour l'instant, ceux du site.
 */
class TableauDeBordActivity : AppCompatActivity() {

    private lateinit var vues: ActivityTableauDeBordBinding
    private lateinit var textes: Textes

    override fun onCreate(etat: Bundle?) {
        super.onCreate(etat)
        vues = ActivityTableauDeBordBinding.inflate(layoutInflater)
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

        textes = Textes(this)
        vues.rafraichir.setOnRefreshListener { synchroniserPuisAfficher() }
        afficher()
    }

    private fun afficher() {
        lifecycleScope.launch {
            val maisons = Analyse(this@TableauDeBordActivity).calculer()
            vues.contenu.removeAllViews()
            if (maisons.isEmpty()) {
                vues.vide.visibility = View.VISIBLE
                return@launch
            }
            vues.vide.visibility = View.GONE
            for (maison in maisons) afficherMaison(maison, plusieurs = maisons.size > 1)
        }
    }

    private fun synchroniserPuisAfficher() {
        lifecycleScope.launch {
            Synchroniseur(this@TableauDeBordActivity).executer()
            vues.rafraichir.isRefreshing = false
            afficher()
        }
    }

    private fun afficherMaison(maison: ResumeMaison, plusieurs: Boolean) {
        if (plusieurs) {
            val titre = layoutInflater.inflate(R.layout.item_titre_maison, vues.contenu, false) as TextView
            titre.text = maison.nom
            vues.contenu.addView(titre)
        }
        for (resume in maison.lignes) afficherLigne(resume, maison.id)
    }

    private fun afficherLigne(resume: ResumeLigne, maisonId: Long) {
        val tuile = ItemTuileBinding.inflate(layoutInflater, vues.contenu, false)
        val ligne = resume.ligne
        val unite = ligne.unite
        tuile.pastille.setBackgroundColor(
            ContextCompat.getColor(
                this,
                when (ligne.energie) {
                    Energie.EAU -> R.color.eau
                    Energie.GAZ -> R.color.gaz
                    Energie.ELECTRICITE -> R.color.elec
                    Energie.MAZOUT -> R.color.mazout
                },
            )
        )
        tuile.titre.text = textes.libelle(ligne)

        val p = resume.prevision
        if (p == null) {
            tuile.valeur.text = getString(R.string.tiret)
            tuile.periode.setText(R.string.pas_de_releve_sur_la_periode)
            masquer(tuile.detail, tuile.fourchette, tuile.evolution, tuile.cout, tuile.methode)
        } else {
            tuile.valeur.text = getString(R.string.valeur_unite, textes.nombre(p.totalPrevu), unite)
            tuile.periode.text = if (p.surReleveAnnuel) {
                getString(R.string.periode_releve_annuel, textes.date(p.borneDepart), textes.date(p.fin))
            } else {
                getString(R.string.periode_annee_civile, p.annee)
            }
            tuile.detail.text = getString(
                R.string.detail_realise,
                textes.nombre(p.realise), unite, p.joursRealises,
                textes.nombre(p.estimeRestant), p.joursRestants,
            )
            val basse = p.borneBasse
            val haute = p.borneHaute
            if (basse != null && haute != null) {
                tuile.fourchette.text = getString(R.string.fourchette, textes.nombre(basse), textes.nombre(haute))
            } else {
                masquer(tuile.fourchette)
            }

            val evolution = p.evolutionPct
            if (evolution != null && p.referenceAnnee != null) {
                var texte = getString(
                    R.string.evolution_vs,
                    textes.pourcent(evolution), p.referenceAnnee,
                    textes.nombre(p.reference ?: 0.0), unite,
                )
                p.evolutionNormaliseePct?.let {
                    texte += "\n" + getString(R.string.evolution_climat_egal, textes.pourcent(it))
                }
                tuile.evolution.text = texte
                tuile.evolution.setTextColor(
                    ContextCompat.getColor(
                        this,
                        when {
                            evolution >= 1.0 -> R.color.critique
                            evolution <= -1.0 -> R.color.bien
                            else -> R.color.encre_2
                        },
                    )
                )
            } else {
                masquer(tuile.evolution)
            }

            val cout = resume.cout
            if (cout != null && cout > 0) {
                tuile.cout.text = getString(R.string.cout_estime, textes.nombre(cout))
            } else {
                masquer(tuile.cout)
            }
            tuile.methode.text = getString(R.string.methode_employee, textes.methode(p.methode))
        }

        val anomalies = resume.anomalies
        if (anomalies.isEmpty()) {
            masquer(tuile.anomalies)
        } else {
            val alertes = anomalies.count { it.niveau == Niveau.ALERTE }
            tuile.anomalies.text = buildString {
                append(resources.getQuantityString(R.plurals.points_a_verifier, anomalies.size, anomalies.size))
                // Les plus récentes d'abord : ce sont celles sur lesquelles on peut agir.
                for (a in anomalies.sortedByDescending { it.debut }.take(3)) {
                    append("\n• ").append(textes.anomalie(a))
                }
            }
            tuile.anomalies.setTextColor(
                ContextCompat.getColor(this, if (alertes > 0) R.color.attention_texte else R.color.encre_2)
            )
        }

        // Un appui sur la carte ouvre le détail de l'énergie.
        tuile.root.setOnClickListener {
            be.suivicompteurs.app.analyse.AnalyseActivity.ouvrirLigne(this, maisonId, ligne.energie, ligne.plage)
        }
        vues.contenu.addView(tuile.root)
    }

    private fun masquer(vararg vuesAMasquer: View) {
        vuesAMasquer.forEach { it.visibility = View.GONE }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.tableau_de_bord, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_comparaison -> {
            be.suivicompteurs.app.analyse.AnalyseActivity.ouvrir(this, be.suivicompteurs.app.analyse.AnalyseActivity.COMPARAISON); true
        }
        R.id.action_justesse -> {
            be.suivicompteurs.app.analyse.AnalyseActivity.ouvrir(this, be.suivicompteurs.app.analyse.AnalyseActivity.JUSTESSE); true
        }
        else -> super.onOptionsItemSelected(item)
    }
}
