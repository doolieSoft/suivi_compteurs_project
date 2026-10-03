package be.suivicompteurs.app.donnees

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

/*
 * Données de l'appareil : maisons, compteurs, relevés, tarifs, événements,
 * degrés-jours.
 *
 * Elles nourrissent le moteur de calcul embarqué. En mode serveur, elles sont
 * une copie remplacée en bloc à chaque changement de version ; en mode
 * autonome, elles font référence et se gèrent depuis l'application.
 *
 * Les colonnes ajoutées en version 5 portent une valeur par défaut : Room
 * compare le schéma déclaré à celui de la base, valeurs par défaut comprises,
 * et ALTER TABLE n'ajoute une colonne obligatoire qu'avec l'une d'elles.
 */

@Entity(tableName = "maisons")
data class MaisonLocale(
    @PrimaryKey val id: Long,
    val nom: String,
    val actuelle: Boolean,
    val stationId: Long?,
    @ColumnInfo(defaultValue = "") val adresse: String = "",
    val nbFacades: Int? = null,
    /** Surface chauffée, en m². */
    val surface: Int? = null,
    /** AAAA-MM-JJ. */
    val dateEntree: String? = null,
    /** Absente tant que la maison est occupée. */
    val dateSortie: String? = null,
    @ColumnInfo(defaultValue = "") val notes: String = "",
    /** « Liège, Wallonie, Belgique » : le lieu choisi, d'où viennent les coordonnées. */
    @ColumnInfo(defaultValue = "") val ville: String = "",
)

@Entity(tableName = "stations")
data class StationLocale(
    @PrimaryKey val id: Long,
    val nom: String,
    val latitude: Double,
    val longitude: Double,
    /** Base des degrés-jours, 16,5 °C en Belgique. */
    val base: Double,
)

@Entity(tableName = "historique_compteurs")
data class CompteurHistorique(
    @PrimaryKey val id: Long,
    val maisonId: Long,
    val energie: String,
    val plage: String,
    val unite: String,
    val libelle: String,
    val datePose: String?,
    @ColumnInfo(defaultValue = "") val numero: String = "",
    /** 11 pour un compteur gaz en m³, 1 pour l'électricité. */
    @ColumnInfo(defaultValue = "1.0") val coefKwh: Double = 1.0,
    val dateDepose: String? = null,
    /** Compteur auquel celui-ci a succédé, l'index repartant de zéro. */
    val remplaceId: Long? = null,
)

@Entity(tableName = "historique_releves", primaryKeys = ["compteurId", "date"])
data class ReleveHistorique(
    val compteurId: Long,
    /** AAAA-MM-JJ. */
    val date: String,
    val index: Double,
    val annuel: Boolean,
    /** MANUEL, FOURNISSEUR, ESTIME ou IMPORT, comme sur le serveur. */
    @ColumnInfo(defaultValue = "MANUEL") val source: String = "MANUEL",
    @ColumnInfo(defaultValue = "") val commentaire: String = "",
    /** Chemin de la photo du compteur, conservée sur l'appareil comme preuve. */
    val photo: String? = null,
)

/**
 * Fait marquant expliquant une rupture de consommation : isolation du toit,
 * changement de douche, remplacement de compteur…
 */
@Entity(tableName = "evenements")
data class EvenementLocal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val maisonId: Long,
    val date: String,
    /** Vide si l'événement concerne toutes les énergies. */
    val energie: String,
    val libelle: String,
    val description: String = "",
)

@Entity(tableName = "degres_jours", primaryKeys = ["stationId", "date"])
data class DegreJourLocal(
    val stationId: Long,
    val date: String,
    val dj: Double,
)

@Entity(tableName = "tarifs")
data class TarifLocal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Absente pour un tarif valable dans toutes les maisons. */
    val maisonId: Long?,
    val energie: String,
    val debut: String,
    val fin: String?,
    val prix: Double,
    val abonnement: Double,
    @ColumnInfo(defaultValue = "") val fournisseur: String = "",
    @ColumnInfo(defaultValue = "") val notes: String = "",
)

/** Toutes les données d'un bloc, telles que le serveur ou un classeur les livre. */
data class Historique(
    val version: String,
    val maisons: List<MaisonLocale>,
    val stations: List<StationLocale>,
    val compteurs: List<CompteurHistorique>,
    val releves: List<ReleveHistorique>,
    val degresJours: List<DegreJourLocal>,
    val tarifs: List<TarifLocal>,
    val evenements: List<EvenementLocal> = emptyList(),
)

@Dao
abstract class HistoriqueDao {
    @Query("SELECT * FROM maisons ORDER BY actuelle DESC, nom")
    abstract suspend fun maisons(): List<MaisonLocale>

    @Query("SELECT * FROM stations")
    abstract suspend fun stations(): List<StationLocale>

    @Query("SELECT * FROM historique_compteurs ORDER BY id")
    abstract suspend fun compteurs(): List<CompteurHistorique>

    @Query("SELECT * FROM historique_releves ORDER BY compteurId, date")
    abstract suspend fun releves(): List<ReleveHistorique>

    @Query("SELECT * FROM degres_jours WHERE stationId = :stationId ORDER BY date")
    abstract suspend fun degresJours(stationId: Long): List<DegreJourLocal>

    @Query("SELECT * FROM tarifs ORDER BY debut")
    abstract suspend fun tarifs(): List<TarifLocal>

    @Query("SELECT * FROM evenements ORDER BY date")
    abstract suspend fun evenements(): List<EvenementLocal>

    @Query("SELECT COUNT(*) FROM historique_releves")
    abstract suspend fun nombreReleves(): Int

    @Query("SELECT MAX(date) FROM degres_jours WHERE stationId = :stationId")
    abstract suspend fun dernierDegreJour(stationId: Long): String?

    /** Mode autonome : un relevé saisi sur l'appareil rejoint directement l'historique. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun enregistrerReleves(liste: List<ReleveHistorique>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun enregistrerDegresJours(liste: List<DegreJourLocal>)

    // --- gestion en mode autonome -------------------------------------------

    @Query("SELECT * FROM maisons WHERE id = :id")
    abstract suspend fun maison(id: Long): MaisonLocale?

    @Query("SELECT * FROM historique_compteurs WHERE id = :id")
    abstract suspend fun compteur(id: Long): CompteurHistorique?

    @Query("SELECT * FROM historique_compteurs WHERE maisonId = :maisonId ORDER BY energie, plage, datePose")
    abstract suspend fun compteursDe(maisonId: Long): List<CompteurHistorique>

    @Query("SELECT * FROM historique_releves WHERE compteurId = :compteurId ORDER BY date")
    abstract suspend fun relevesDe(compteurId: Long): List<ReleveHistorique>

    @Query("SELECT * FROM tarifs WHERE maisonId = :maisonId OR maisonId IS NULL ORDER BY energie, debut")
    abstract suspend fun tarifsDe(maisonId: Long): List<TarifLocal>

    @Query("SELECT * FROM evenements WHERE maisonId = :maisonId ORDER BY date")
    abstract suspend fun evenementsDe(maisonId: Long): List<EvenementLocal>

    @Query("SELECT COALESCE(MAX(id), 0) + 1 FROM maisons") abstract suspend fun prochaineMaison(): Long
    @Query("SELECT COALESCE(MAX(id), 0) + 1 FROM stations") abstract suspend fun prochaineStation(): Long
    @Query("SELECT COALESCE(MAX(id), 0) + 1 FROM historique_compteurs") abstract suspend fun prochainCompteur(): Long

    @Upsert abstract suspend fun enregistrerMaison(maison: MaisonLocale)
    @Upsert abstract suspend fun enregistrerStation(station: StationLocale)
    @Upsert abstract suspend fun enregistrerCompteur(compteur: CompteurHistorique)
    @Upsert abstract suspend fun enregistrerReleve(releve: ReleveHistorique)
    @Upsert abstract suspend fun enregistrerTarif(tarif: TarifLocal)
    @Upsert abstract suspend fun enregistrerEvenement(evenement: EvenementLocal)

    @Query("DELETE FROM historique_releves WHERE compteurId = :compteurId AND date = :date")
    abstract suspend fun supprimerReleve(compteurId: Long, date: String)

    @Query("DELETE FROM tarifs WHERE id = :id") abstract suspend fun supprimerTarif(id: Long)
    @Query("DELETE FROM evenements WHERE id = :id") abstract suspend fun supprimerEvenement(id: Long)

    /** Un compteur part avec ses relevés. */
    @Transaction
    open suspend fun supprimerCompteur(id: Long) {
        supprimerRelevesDe(id)
        supprimerCompteurSeul(id)
    }

    /** Une maison part avec ses compteurs, relevés, tarifs propres et événements. */
    @Transaction
    open suspend fun supprimerMaison(id: Long) {
        compteursDe(id).forEach { supprimerCompteur(it.id) }
        supprimerTarifsDe(id)
        supprimerEvenementsDe(id)
        supprimerMaisonSeule(id)
    }

    @Query("DELETE FROM historique_releves WHERE compteurId = :id") protected abstract suspend fun supprimerRelevesDe(id: Long)
    @Query("DELETE FROM historique_compteurs WHERE id = :id") protected abstract suspend fun supprimerCompteurSeul(id: Long)
    @Query("DELETE FROM tarifs WHERE maisonId = :id") protected abstract suspend fun supprimerTarifsDe(id: Long)
    @Query("DELETE FROM evenements WHERE maisonId = :id") protected abstract suspend fun supprimerEvenementsDe(id: Long)
    @Query("DELETE FROM maisons WHERE id = :id") protected abstract suspend fun supprimerMaisonSeule(id: Long)

    /** Toutes les données de l'appareil, pour l'export. */
    @Transaction
    open suspend fun tout(version: String = ""): Historique = Historique(
        version = version,
        maisons = maisons(),
        stations = stations(),
        compteurs = compteurs(),
        releves = releves(),
        degresJours = tousDegresJours(),
        tarifs = tarifs(),
        evenements = evenements(),
    )

    @Query("SELECT * FROM degres_jours ORDER BY stationId, date")
    protected abstract suspend fun tousDegresJours(): List<DegreJourLocal>

    /**
     * Remplace tout l'historique d'un coup : jamais de mélange entre deux
     * versions, même si l'application est fermée en plein enregistrement.
     */
    @Transaction
    open suspend fun remplacer(historique: Historique) {
        viderMaisons()
        viderStations()
        viderCompteurs()
        viderReleves()
        viderDegresJours()
        viderTarifs()
        viderEvenements()
        insererMaisons(historique.maisons)
        insererStations(historique.stations)
        insererCompteurs(historique.compteurs)
        insererReleves(historique.releves)
        insererDegresJours(historique.degresJours)
        insererTarifs(historique.tarifs)
        insererEvenements(historique.evenements)
    }

    @Query("DELETE FROM maisons") protected abstract suspend fun viderMaisons()
    @Query("DELETE FROM stations") protected abstract suspend fun viderStations()
    @Query("DELETE FROM historique_compteurs") protected abstract suspend fun viderCompteurs()
    @Query("DELETE FROM historique_releves") protected abstract suspend fun viderReleves()
    @Query("DELETE FROM degres_jours") protected abstract suspend fun viderDegresJours()
    @Query("DELETE FROM tarifs") protected abstract suspend fun viderTarifs()
    @Query("DELETE FROM evenements") protected abstract suspend fun viderEvenements()

    @Insert protected abstract suspend fun insererMaisons(liste: List<MaisonLocale>)
    @Insert protected abstract suspend fun insererStations(liste: List<StationLocale>)
    @Insert protected abstract suspend fun insererCompteurs(liste: List<CompteurHistorique>)
    @Insert protected abstract suspend fun insererReleves(liste: List<ReleveHistorique>)
    @Insert protected abstract suspend fun insererDegresJours(liste: List<DegreJourLocal>)
    @Insert protected abstract suspend fun insererTarifs(liste: List<TarifLocal>)
    @Insert protected abstract suspend fun insererEvenements(liste: List<EvenementLocal>)
}
