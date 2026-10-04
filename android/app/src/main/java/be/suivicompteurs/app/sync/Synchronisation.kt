package be.suivicompteurs.app.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import be.suivicompteurs.app.Reglages
import be.suivicompteurs.app.classeur.ImportClasseur
import be.suivicompteurs.app.donnees.BaseLocale
import be.suivicompteurs.app.donnees.DegreJourLocal
import be.suivicompteurs.app.moteur.DegresJours
import be.suivicompteurs.app.reseau.OpenMeteo
import be.suivicompteurs.app.reseau.Resultat
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Issue d'une mise à jour, telle qu'on la présente à l'utilisateur. */
data class BilanSynchro(
    /** Jours de météo ajoutés. */
    val joursMeteo: Int = 0,
    val erreur: String? = null,
    val horsLigne: Boolean = false,
) {
    val reussie: Boolean get() = erreur == null
}

/**
 * Mise à jour des données qui ne viennent pas de l'utilisateur.
 *
 * Toutes les données vivent sur l'appareil ; seule la météo vient d'ailleurs.
 * Cette mise à jour demande donc à Open-Meteo les degrés-jours manquants de
 * chaque station, puis rafraîchit la liste des compteurs à relever.
 */
class Synchroniseur(contexte: Context) {

    private val reglages = Reglages(contexte)
    private val base = BaseLocale.obtenir(contexte)

    suspend fun executer(): BilanSynchro {
        val historique = base.historique()

        var joursMeteo = 0
        var echec: Resultat.Echec? = null
        val hier = LocalDate.now().minusDays(1)
        for (station in historique.stations()) {
            // La météo doit couvrir tous les relevés des maisons de la station (le
            // modèle thermique écarte une période sans météo) et les dix ans de la
            // normale climatique. Une station neuve, ou créée en changeant la
            // position d'une maison, est donc complétée vers le passé aussi.
            val premierReleve = historique.premierReleveDeStation(station.id)?.let(LocalDate::parse)
            val necessaire = listOfNotNull(premierReleve, hier.minusYears(DegresJours.ANNEES_NORMALE.toLong())).min()
            val premier = historique.premierDegreJour(station.id)?.let(LocalDate::parse)
            val dernier = historique.dernierDegreJour(station.id)?.let(LocalDate::parse)
            val manquants = if (premier == null || dernier == null) {
                listOf(necessaire to hier)
            } else {
                listOfNotNull(
                    (necessaire to premier.minusDays(1)).takeIf { necessaire < premier },
                    (dernier.plusDays(1) to hier).takeIf { dernier < hier },
                )
            }
            for ((debut, fin) in manquants) {
                when (val reponse = OpenMeteo().temperatures(station.latitude, station.longitude, debut, fin)) {
                    is Resultat.Succes -> {
                        historique.enregistrerDegresJours(
                            reponse.valeur.map { (jour, t) ->
                                DegreJourLocal(station.id, jour.toString(), DegresJours.calculer(t, station.base))
                            }
                        )
                        joursMeteo += reponse.valeur.size
                    }
                    is Resultat.Echec -> echec = reponse
                }
            }
        }

        // La liste de l'écran d'accueil suit l'historique : compteurs posés ou
        // déposés, derniers index.
        val aSaisir = ImportClasseur.compteursASaisir(historique.tout())
        base.compteurs().enregistrer(aSaisir)
        base.compteurs().supprimerAbsents(aSaisir.map { it.id })

        val manque = echec
        if (manque != null && joursMeteo == 0) {
            return BilanSynchro(erreur = manque.message, horsLigne = manque.horsLigne)
        }
        reglages.derniereSynchro = System.currentTimeMillis()
        return BilanSynchro(joursMeteo = joursMeteo)
    }
}

/**
 * Tâche de fond confiée au système : la météo se met à jour d'elle-même, et
 * WorkManager attend qu'un réseau soit disponible pour la lancer.
 */
class SyncWorker(contexte: Context, parametres: WorkerParameters) :
    CoroutineWorker(contexte, parametres) {

    override suspend fun doWork(): Result {
        val bilan = Synchroniseur(applicationContext).executer()
        return when {
            bilan.reussie -> Result.success()
            bilan.horsLigne -> Result.retry()
            else -> Result.failure()
        }
    }

    companion object {
        private const val PERIODIQUE = "synchro-periodique"

        private val contraintes = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** Une mise à jour par jour suffit : la météo n'est publiée qu'une fois par jour. */
        fun programmer(contexte: Context) {
            val tache = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.DAYS)
                .setConstraints(contraintes)
                .build()
            WorkManager.getInstance(contexte).enqueueUniquePeriodicWork(
                PERIODIQUE,
                // Remplace la synchronisation toutes les six heures des
                // versions qui parlaient au serveur.
                ExistingPeriodicWorkPolicy.UPDATE,
                tache,
            )
        }
    }
}
