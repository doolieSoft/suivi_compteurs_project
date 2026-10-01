package be.suivicompteurs.app.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import be.suivicompteurs.app.Reglages
import be.suivicompteurs.app.donnees.BaseLocale
import be.suivicompteurs.app.donnees.InstantaneLocal
import be.suivicompteurs.app.reseau.Api
import be.suivicompteurs.app.reseau.Resultat
import java.io.File
import java.util.concurrent.TimeUnit

/** Issue d'une synchronisation, telle qu'on la présente à l'utilisateur. */
data class BilanSynchro(
    val envoyes: Int = 0,
    val refuses: Int = 0,
    val photos: Int = 0,
    val compteursMisAJour: Int = 0,
    val instantaneRafraichi: Boolean = false,
    val erreur: String? = null,
    val horsLigne: Boolean = false,
) {
    val reussie: Boolean get() = erreur == null
}

/**
 * Échange avec le serveur, dans cet ordre précis :
 *
 *  1. vider la file des relevés en attente — c'est ce qui compte le plus, et
 *     cela doit partir avant toute chose ;
 *  2. envoyer les photos, plus lourdes et moins critiques ;
 *  3. rapatrier la liste des compteurs puis l'instantané de l'analyse.
 *
 * Chaque étape est indépendante : l'échec de l'une n'annule pas les autres.
 */
class Synchroniseur(private val contexte: Context) {

    private val reglages = Reglages(contexte)
    private val base = BaseLocale.obtenir(contexte)
    private val api = Api(reglages)

    suspend fun executer(): BilanSynchro {
        if (!reglages.configure) {
            return BilanSynchro(erreur = "Adresse du serveur ou jeton non renseigné.")
        }

        var envoyes = 0
        var refuses = 0
        var photos = 0

        // --- 1. relevés en attente ---------------------------------------
        val attente = base.releves().enAttente().filter { !it.envoye }
        if (attente.isNotEmpty()) {
            when (val reponse = api.synchroniser(attente)) {
                is Resultat.Succes -> {
                    attente.forEach { releve ->
                        val erreur = reponse.valeur[releve.reference]
                        if (reponse.valeur.containsKey(releve.reference) && erreur == null) {
                            base.releves().modifier(releve.copy(envoye = true, erreur = null))
                            envoyes++
                        } else if (erreur != null) {
                            // Conservé sur l'appareil, avec son motif de refus :
                            // l'utilisateur doit pouvoir le corriger, pas le perdre.
                            base.releves().modifier(releve.copy(erreur = erreur))
                            refuses++
                        }
                    }
                }
                is Resultat.Echec -> return BilanSynchro(
                    erreur = reponse.message,
                    horsLigne = reponse.horsLigne,
                )
            }
        }

        // --- 2. photos ----------------------------------------------------
        base.releves().enAttente()
            .filter { it.envoye && it.cheminPhoto != null && !it.photoEnvoyee }
            .forEach { releve ->
                val fichier = File(releve.cheminPhoto!!)
                when (api.envoyerPhoto(releve, fichier)) {
                    is Resultat.Succes -> {
                        base.releves().modifier(releve.copy(photoEnvoyee = true))
                        // La copie locale a rempli son office ; le serveur est
                        // désormais dépositaire de la preuve.
                        fichier.delete()
                        photos++
                    }
                    is Resultat.Echec -> Unit // retenté à la prochaine occasion
                }
            }

        // --- 3. référentiel et analyse ------------------------------------
        var compteursMisAJour = 0
        when (val reponse = api.compteurs()) {
            is Resultat.Succes -> {
                base.compteurs().enregistrer(reponse.valeur)
                base.compteurs().supprimerAbsents(reponse.valeur.map { it.id })
                compteursMisAJour = reponse.valeur.size
            }
            is Resultat.Echec -> Unit
        }

        var instantaneRafraichi = false
        when (val reponse = api.instantane()) {
            is Resultat.Succes -> {
                val (json, genereLe) = reponse.valeur
                base.instantane().enregistrer(InstantaneLocal(json = json, genereLe = genereLe))
                instantaneRafraichi = true
            }
            is Resultat.Echec -> Unit
        }

        // Sans file d'attente à vider, l'échec des étapes 2 et 3 passerait
        // inaperçu et l'on daterait une synchronisation qui n'a jamais eu lieu.
        val contactEtabli = envoyes > 0 || refuses > 0 ||
            compteursMisAJour > 0 || instantaneRafraichi
        if (!contactEtabli) {
            return BilanSynchro(
                erreur = "Serveur injoignable.",
                horsLigne = true,
            )
        }

        reglages.derniereSynchro = System.currentTimeMillis()
        // Les relevés transmis depuis plus d'un mois n'ont plus d'utilité ici.
        base.releves().purger(System.currentTimeMillis() - 30L * 24 * 3600 * 1000)

        return BilanSynchro(
            envoyes = envoyes,
            refuses = refuses,
            photos = photos,
            compteursMisAJour = compteursMisAJour,
            instantaneRafraichi = instantaneRafraichi,
        )
    }
}

/**
 * Tâche de fond confiée au système.
 *
 * WorkManager garantit l'exécution même si l'application est fermée, et attend
 * de lui-même qu'un réseau soit disponible : le relevé pris dans la cave part
 * tout seul au retour du Wi-Fi.
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
        private const val IMMEDIAT = "synchro-immediate"

        private val contraintes = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** Programme une tentative régulière, en plus des envois à la demande. */
        fun programmer(contexte: Context) {
            val tache = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(contraintes)
                .build()
            WorkManager.getInstance(contexte).enqueueUniquePeriodicWork(
                PERIODIQUE,
                ExistingPeriodicWorkPolicy.KEEP,
                tache,
            )
        }

        /** Tente un envoi dès que le réseau le permet. */
        fun declencher(contexte: Context) {
            val tache = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(contraintes)
                .build()
            WorkManager.getInstance(contexte).enqueue(tache)
        }
    }
}
