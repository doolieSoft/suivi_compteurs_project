package be.suivicompteurs.app.analyse

import android.content.Context
import be.suivicompteurs.app.R
import be.suivicompteurs.app.moteur.Anomalie
import be.suivicompteurs.app.moteur.Energie
import be.suivicompteurs.app.moteur.Ligne
import be.suivicompteurs.app.moteur.Methode
import be.suivicompteurs.app.moteur.Plage
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.abs

/**
 * Met en mots ce que le moteur calcule, dans la langue de l'appareil.
 *
 * Le moteur ne rédige rien : toutes les phrases viennent des ressources, ce
 * qui suffit à traduire l'application en ajoutant un fichier strings.xml.
 */
class Textes(private val contexte: Context) {

    private val locale get() = contexte.resources.configuration.locales[0]
    private val dates get() = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)

    fun nombre(valeur: Double, decimales: Int = 0): String =
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = decimales
            maximumFractionDigits = decimales
        }.format(valeur)

    /** Pourcentage signé : « +4,2 % », « −3,0 % ». */
    fun pourcent(valeur: Double): String {
        val signe = when {
            valeur > 0.05 -> "+"
            valeur < -0.05 -> "−"
            else -> ""
        }
        return contexte.getString(R.string.pourcent, signe + nombre(abs(valeur), 1))
    }

    fun date(jour: LocalDate): String = dates.format(jour)

    fun energie(energie: Energie): String = contexte.getString(
        when (energie) {
            Energie.EAU -> R.string.energie_eau
            Energie.GAZ -> R.string.energie_gaz
            Energie.ELECTRICITE -> R.string.energie_electricite
            Energie.MAZOUT -> R.string.energie_mazout
        }
    )

    fun libelle(ligne: Ligne): String = when (ligne.plage) {
        Plage.UNIQUE -> energie(ligne.energie)
        Plage.HAUT -> contexte.getString(R.string.libelle_plage, energie(ligne.energie), contexte.getString(R.string.plage_haute))
        Plage.BAS -> contexte.getString(R.string.libelle_plage, energie(ligne.energie), contexte.getString(R.string.plage_basse))
    }

    fun methode(methode: Methode): String = when (methode) {
        Methode.Thermique -> contexte.getString(R.string.methode_thermique)
        is Methode.ProfilSaisonnier -> contexte.resources.getQuantityString(
            R.plurals.methode_profil, methode.nbAnnees, methode.nbAnnees,
        )
        is Methode.MoyennePassee -> contexte.resources.getQuantityString(
            R.plurals.methode_moyenne, methode.nbAnnees, methode.nbAnnees,
        )
        Methode.Prorata -> contexte.getString(R.string.methode_prorata)
        Methode.Aucune -> contexte.getString(R.string.methode_aucune)
    }

    fun anomalie(anomalie: Anomalie): String {
        val periode = contexte.getString(R.string.periode_du_au, date(anomalie.debut), date(anomalie.fin))
        val texte = when (anomalie) {
            is Anomalie.Lacune -> contexte.resources.getQuantityString(
                R.plurals.anomalie_lacune, anomalie.nbJours, anomalie.nbJours,
            )
            is Anomalie.IndexFige -> contexte.resources.getQuantityString(
                R.plurals.anomalie_index_fige, anomalie.nbJours, anomalie.nbJours,
            )
            is Anomalie.Surconsommation -> contexte.getString(
                if (anomalie.faceAuClimat) R.string.anomalie_surconso_climat else R.string.anomalie_surconso,
                nombre(anomalie.volume, 1),
                anomalie.unite,
                anomalie.nbJours,
                nombre(anomalie.rapport, 1),
            )
        }
        return contexte.getString(R.string.anomalie_avec_periode, periode, texte)
    }
}
