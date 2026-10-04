package be.suivicompteurs.app.analyse

import android.content.Context
import be.suivicompteurs.app.donnees.BaseLocale
import be.suivicompteurs.app.donnees.EvenementLocal
import be.suivicompteurs.app.donnees.MaisonLocale
import be.suivicompteurs.app.donnees.PointVerifie
import be.suivicompteurs.app.moteur.Anomalie
import be.suivicompteurs.app.moteur.ComparaisonGlissante
import be.suivicompteurs.app.moteur.Compteur
import be.suivicompteurs.app.moteur.DegresJours
import be.suivicompteurs.app.moteur.Energie
import be.suivicompteurs.app.moteur.Ligne
import be.suivicompteurs.app.moteur.Plage
import be.suivicompteurs.app.moteur.Prevision
import be.suivicompteurs.app.moteur.Releve
import be.suivicompteurs.app.moteur.Tarif
import be.suivicompteurs.app.moteur.comparerAAnneePrecedente
import be.suivicompteurs.app.moteur.detecterAnomalies
import be.suivicompteurs.app.moteur.lignes
import be.suivicompteurs.app.moteur.prevoir
import be.suivicompteurs.app.moteur.prevoirAuRelevePrecedent
import be.suivicompteurs.app.moteur.PrevisionPrecedente
import be.suivicompteurs.app.moteur.valoriser
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Ce que le tableau de bord affiche pour une énergie. */
data class ResumeLigne(
    val ligne: Ligne,
    val prevision: Prevision?,
    val comparaison: ComparaisonGlissante?,
    val anomalies: List<Anomalie>,
    /** Coût de la prévision au tarif du jour, s'il est connu. */
    val cout: Double?,
    /** La prévision au relevé précédent, pour la tendance. */
    val previsionPrecedente: PrevisionPrecedente? = null,
)

data class ResumeMaison(
    val id: Long,
    val nom: String,
    val actuelle: Boolean,
    val lignes: List<ResumeLigne>,
)

/** Une maison prête pour le moteur : compteurs, météo, tarifs et repères. */
class MaisonChargee(
    val maison: MaisonLocale,
    val compteurs: List<Compteur>,
    val djs: Map<LocalDate, Double>,
    val tarifs: List<Tarif>,
    val evenements: List<EvenementLocal>,
    /** Points à vérifier déjà traités. */
    val pointsVerifies: List<PointVerifie> = emptyList(),
) {
    val lignes: List<Ligne> by lazy { lignes(compteurs, djs).filter { it.serie.jours.isNotEmpty() } }

    fun normales(aujourdhui: LocalDate) = DegresJours.normales(djs, aujourdhui)

    fun ligne(energie: Energie, plage: Plage) = lignes.firstOrNull { it.energie == energie && it.plage == plage }
}

/** Fait tourner le moteur de calcul sur les données de l'appareil. */
class Analyse(contexte: Context) {

    private val base = BaseLocale.obtenir(contexte)

    suspend fun charger(): List<MaisonChargee> {
        val historique = base.historique()
        val maisons = historique.maisons()
        if (maisons.isEmpty()) return emptyList()

        val compteurs = historique.compteurs()
        val releves = historique.releves().groupBy { it.compteurId }
        val tarifs = historique.tarifs()
        val evenements = historique.evenements()
        val pointsVerifies = historique.pointsVerifies()
        val djsParStation = historique.stations().associate { station ->
            station.id to historique.degresJours(station.id)
                .associateTo(LinkedHashMap()) { LocalDate.parse(it.date) to it.dj }
        }

        return maisons.map { maison ->
            // Avant l'emménagement, la maison était vide ou habitée par d'autres :
            // ces relevés fausseraient le modèle et les profils saisonniers.
            val entree = maison.dateEntree
            MaisonChargee(
                maison = maison,
                compteurs = compteurs.filter { it.maisonId == maison.id }.map { c ->
                    Compteur(
                        id = c.id,
                        energie = Energie.depuisCode(c.energie),
                        plage = Plage.depuisCode(c.plage),
                        unite = c.unite,
                        datePose = c.datePose?.let(LocalDate::parse),
                        releves = releves[c.id].orEmpty()
                            .filter { entree == null || it.date >= entree }
                            .map { Releve(LocalDate.parse(it.date), it.index, it.annuel) },
                    )
                },
                djs = maison.stationId?.let { djsParStation[it] } ?: emptyMap(),
                tarifs = tarifs.filter { it.maisonId == null || it.maisonId == maison.id }.map {
                    Tarif(
                        energie = Energie.depuisCode(it.energie),
                        dateDebut = LocalDate.parse(it.debut),
                        dateFin = it.fin?.let(LocalDate::parse),
                        prixUnitaire = it.prix,
                        abonnementMensuel = it.abonnement,
                        propreALaMaison = it.maisonId != null,
                    )
                },
                evenements = evenements.filter { it.maisonId == maison.id },
                pointsVerifies = pointsVerifies.filter { it.maisonId == maison.id },
            )
        }
    }

    suspend fun calculer(aujourdhui: LocalDate = LocalDate.now()): List<ResumeMaison> {
        val maisons = charger()
        return withContext(Dispatchers.Default) {
            maisons.mapNotNull { chargee ->
                val normales = chargee.normales(aujourdhui)
                val resumes = chargee.lignes.map { ligne ->
                    val prevision = prevoir(ligne, aujourdhui, normales)
                    ResumeLigne(
                        ligne = ligne,
                        prevision = prevision,
                        comparaison = comparerAAnneePrecedente(ligne, aujourdhui),
                        // Seuls les points encore à vérifier : les autres sont traités.
                        anomalies = aVerifier(ligne.energie, ligne.plage, detecterAnomalies(ligne), chargee.pointsVerifies),
                        cout = prevision?.let { valoriser(ligne, chargee.tarifs, it.totalPrevu, aujourdhui) },
                        previsionPrecedente = prevision?.let { prevoirAuRelevePrecedent(ligne, chargee.djs, it) },
                    )
                }
                // Une maison sans prévision possible (quittée) reste consultable :
                // l'écran la range dans son historique.
                if (resumes.isEmpty()) null
                else ResumeMaison(chargee.maison.id, chargee.maison.nom, chargee.maison.actuelle, resumes)
            }
        }
    }
}
