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
            vue.setPadding(marges.left, 0, marges.right, marges.bottom)
            vues.barre.setPadding(0, marges.top, 0, 0)
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
            // Les maisons relevées d'abord, avec leurs prévisions ; celles qu'on ne
            // relève plus (quittées) dans une partie « Historique », sans prévision.
            val (aPrevoir, historiques) = maisons.partition { m -> m.actuelle && m.lignes.any { it.prevision != null } }
            vues.barre.setTitle(if (aPrevoir.isEmpty()) R.string.titre_historique else R.string.titre_tableau_de_bord)
            for (maison in aPrevoir) afficherMaison(maison)
            if (historiques.isNotEmpty()) {
                if (aPrevoir.isNotEmpty()) ajouterTitre(getString(R.string.titre_historique))
                for (maison in historiques) afficherMaison(maison, historique = true)
            }
        }
    }

    private fun synchroniserPuisAfficher() {
        lifecycleScope.launch {
            Synchroniseur(this@TableauDeBordActivity).executer()
            vues.rafraichir.isRefreshing = false
            afficher()
        }
    }

    private fun ajouterTitre(texte: String) {
        val titre = layoutInflater.inflate(R.layout.item_titre_maison, vues.contenu, false) as TextView
        titre.text = texte
        vues.contenu.addView(titre)
    }

    private fun afficherMaison(maison: ResumeMaison, historique: Boolean = false) {
        // Toujours le nom de la maison : on sait à quoi correspond chaque compteur.
        ajouterTitre(maison.nom)
        // Ordre alphabétique des noms affichés, comme sur l'accueil.
        val ordre = java.text.Collator.getInstance()
        for (resume in maison.lignes.sortedWith(compareBy(ordre) { textes.libelle(it.ligne) })) {
            afficherLigne(resume, maison.id, historique)
        }
    }

    private fun afficherLigne(resume: ResumeLigne, maisonId: Long, historique: Boolean = false) {
        val tuile = ItemTuileBinding.inflate(layoutInflater, vues.contenu, false)
        val ligne = resume.ligne
        val unite = ligne.unite
        val (icone, couleur) = when (ligne.energie) {
            Energie.EAU -> R.drawable.ic_eau to R.color.eau
            Energie.GAZ -> R.drawable.ic_gaz to R.color.gaz
            Energie.ELECTRICITE -> R.drawable.ic_electricite to R.color.elec
            Energie.MAZOUT -> R.drawable.ic_mazout to R.color.mazout
        }
        tuile.pastille.setImageResource(icone)
        tuile.pastille.imageTintList = ContextCompat.getColorStateList(this, couleur)
        tuile.titre.text = textes.libelle(ligne)

        val p = if (historique) null else resume.prevision
        if (p == null) {
            tuile.valeur.text = getString(R.string.tiret)
            val dernier = ligne.compteurs.flatMap { c -> c.releves.map { it.date } }.maxOrNull()
            if (historique && dernier != null) {
                masquer(tuile.valeur)
                tuile.periode.text = getString(R.string.releves_jusqua, textes.date(dernier))
            } else {
                tuile.periode.setText(R.string.pas_de_releve_sur_la_periode)
            }
            masquer(tuile.detail, tuile.fourchette, tuile.evolution, tuile.cout, tuile.methode)
        } else {
            tuile.valeur.text = getString(R.string.valeur_unite, textes.nombre(p.totalPrevu), unite)
            resume.previsionPrecedente?.let { avant ->
                val ecart = p.totalPrevu - avant.prevision.totalPrevu
                val depuis = textes.date(avant.releve)
                // Moins de 1 % : la prévision n'a pas bougé.
                val stable = avant.prevision.totalPrevu <= 0 || kotlin.math.abs(ecart) < avant.prevision.totalPrevu * 0.01
                tuile.tendance.text = when {
                    stable -> getString(R.string.tendance_stable, depuis)
                    ecart > 0 -> getString(R.string.tendance_hausse, textes.nombre(ecart), unite, depuis)
                    else -> getString(R.string.tendance_baisse, textes.nombre(-ecart), unite, depuis)
                }
                tuile.tendance.setTextColor(
                    ContextCompat.getColor(
                        this,
                        when {
                            stable -> R.color.encre_2
                            ecart > 0 -> R.color.critique
                            else -> R.color.bien
                        },
                    )
                )
                tuile.tendance.visibility = View.VISIBLE
            }
            tuile.periode.text = if (p.surReleveAnnuel) {
                getString(R.string.periode_releve_annuel, textes.date(p.borneDepart), textes.date(p.fin))
            } else {
                getString(R.string.periode_annee_civile, p.annee)
            }
            tuile.detail.text = getString(
                R.string.detail_realise,
                textes.nombre(p.realise), unite, p.joursRealises,
                textes.nombre(p.estimeRestant), p.joursRestants,
            ) + if (p.joursEstimesAvant > 0) {
                // Suivi commencé en cours de période : le début est estimé.
                "\n" + getString(R.string.estime_avant_premier_releve, textes.nombre(p.estimeAvant), unite, p.joursEstimesAvant)
            } else {
                ""
            }
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
                tuile.cout.text = getString(R.string.cout_estime, Monnaie.formater(this, cout))
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
        menu.afficherIcones()
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
