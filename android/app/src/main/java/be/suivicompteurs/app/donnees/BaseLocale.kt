package be.suivicompteurs.app.donnees

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface CompteurDao {
    @Query("SELECT * FROM compteurs ORDER BY energie, libelle")
    fun suivre(): Flow<List<CompteurLocal>>

    @Query("SELECT * FROM compteurs ORDER BY energie, libelle")
    suspend fun tous(): List<CompteurLocal>

    @Query("SELECT * FROM compteurs WHERE id = :id")
    suspend fun parId(id: Int): CompteurLocal?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enregistrer(compteurs: List<CompteurLocal>)

    @Query("DELETE FROM compteurs WHERE id NOT IN (:idsConserves)")
    suspend fun supprimerAbsents(idsConserves: List<Int>)
}

@Database(
    entities = [
        CompteurLocal::class,
        MaisonLocale::class,
        StationLocale::class,
        CompteurHistorique::class,
        ReleveHistorique::class,
        DegreJourLocal::class,
        TarifLocal::class,
        EvenementLocal::class,
    ],
    version = 7,
    exportSchema = true,
)
abstract class BaseLocale : RoomDatabase() {
    abstract fun compteurs(): CompteurDao
    abstract fun historique(): HistoriqueDao

    companion object {
        /**
         * Ajout du débit journalier maximal.
         *
         * Une migration plutôt qu'une reconstruction : la table des relevés
         * contient la file d'attente, et une saisie faite au fond d'une cave
         * ne doit pas disparaître parce que le schéma a évolué.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE compteurs ADD COLUMN consoJournaliereMax " +
                        "REAL NOT NULL DEFAULT 1.0"
                )
            }
        }

        /** Ajout de la marque « relevé annuel ». */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE releves ADD COLUMN annuel INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * Copie locale de l'historique, pour le moteur de calcul embarqué.
         * Les tables arrivent vides : la synchronisation suivante les remplit.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                CREATION_HISTORIQUE.forEach(db::execSQL)
            }
        }

        // Recopié du schéma généré par Room (schemas/…/4.json) : Room vérifie
        // à l'ouverture que les tables correspondent exactement aux entités.
        private val CREATION_HISTORIQUE = listOf(
            "CREATE TABLE IF NOT EXISTS `maisons` (`id` INTEGER NOT NULL, `nom` TEXT NOT NULL, " +
                "`actuelle` INTEGER NOT NULL, `stationId` INTEGER, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `stations` (`id` INTEGER NOT NULL, `nom` TEXT NOT NULL, " +
                "`latitude` REAL NOT NULL, `longitude` REAL NOT NULL, `base` REAL NOT NULL, " +
                "PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `historique_compteurs` (`id` INTEGER NOT NULL, " +
                "`maisonId` INTEGER NOT NULL, `energie` TEXT NOT NULL, `plage` TEXT NOT NULL, " +
                "`unite` TEXT NOT NULL, `libelle` TEXT NOT NULL, `datePose` TEXT, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `historique_releves` (`compteurId` INTEGER NOT NULL, " +
                "`date` TEXT NOT NULL, `index` REAL NOT NULL, `annuel` INTEGER NOT NULL, " +
                "PRIMARY KEY(`compteurId`, `date`))",
            "CREATE TABLE IF NOT EXISTS `degres_jours` (`stationId` INTEGER NOT NULL, " +
                "`date` TEXT NOT NULL, `dj` REAL NOT NULL, PRIMARY KEY(`stationId`, `date`))",
            "CREATE TABLE IF NOT EXISTS `tarifs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`maisonId` INTEGER, `energie` TEXT NOT NULL, `debut` TEXT NOT NULL, " +
                "`fin` TEXT, `prix` REAL NOT NULL, `abonnement` REAL NOT NULL)",
        )

        /**
         * Tout ce que le site gère et que l'appareil ignorait encore : en mode
         * autonome, c'est lui qui fait référence et l'export doit tout rendre.
         * Copié du schéma généré par Room (schemas/…/5.json).
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf(
                    "ALTER TABLE `maisons` ADD COLUMN `adresse` TEXT NOT NULL DEFAULT ''",
                    "ALTER TABLE `maisons` ADD COLUMN `nbFacades` INTEGER",
                    "ALTER TABLE `maisons` ADD COLUMN `surface` INTEGER",
                    "ALTER TABLE `maisons` ADD COLUMN `dateEntree` TEXT",
                    "ALTER TABLE `maisons` ADD COLUMN `dateSortie` TEXT",
                    "ALTER TABLE `maisons` ADD COLUMN `notes` TEXT NOT NULL DEFAULT ''",
                    "ALTER TABLE `historique_compteurs` ADD COLUMN `numero` TEXT NOT NULL DEFAULT ''",
                    "ALTER TABLE `historique_compteurs` ADD COLUMN `coefKwh` REAL NOT NULL DEFAULT 1.0",
                    "ALTER TABLE `historique_compteurs` ADD COLUMN `dateDepose` TEXT",
                    "ALTER TABLE `historique_compteurs` ADD COLUMN `remplaceId` INTEGER",
                    "ALTER TABLE `historique_releves` ADD COLUMN `source` TEXT NOT NULL DEFAULT 'MANUEL'",
                    "ALTER TABLE `historique_releves` ADD COLUMN `commentaire` TEXT NOT NULL DEFAULT ''",
                    "ALTER TABLE `tarifs` ADD COLUMN `fournisseur` TEXT NOT NULL DEFAULT ''",
                    "ALTER TABLE `tarifs` ADD COLUMN `notes` TEXT NOT NULL DEFAULT ''",
                    "CREATE TABLE IF NOT EXISTS `evenements` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`maisonId` INTEGER NOT NULL, `date` TEXT NOT NULL, `energie` TEXT NOT NULL, " +
                        "`libelle` TEXT NOT NULL, `description` TEXT NOT NULL)",
                ).forEach(db::execSQL)
            }
        }

        /**
         * L'application devient indépendante du site : plus de file d'envoi
         * ni d'instantané du serveur. Un relevé pas encore transmis rejoint
         * l'historique, avec sa photo : rien de ce qui a été noté ne se perd.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `historique_releves` ADD COLUMN `photo` TEXT")
                db.execSQL(
                    "INSERT OR IGNORE INTO `historique_releves` " +
                        "(`compteurId`, `date`, `index`, `annuel`, `source`, `commentaire`, `photo`) " +
                        "SELECT `compteurId`, `date`, `index`, `annuel`, 'MANUEL', `commentaire`, `cheminPhoto` " +
                        "FROM `releves` WHERE `envoye` = 0 AND `erreur` IS NULL"
                )
                // Les photos de relevés déjà transmis restent rattachées.
                db.execSQL(
                    "UPDATE `historique_releves` SET `photo` = (" +
                        "SELECT `cheminPhoto` FROM `releves` r WHERE r.`compteurId` = `historique_releves`.`compteurId` " +
                        "AND r.`date` = `historique_releves`.`date` AND r.`cheminPhoto` IS NOT NULL LIMIT 1) " +
                        "WHERE `photo` IS NULL"
                )
                db.execSQL("DROP TABLE IF EXISTS `releves`")
                db.execSQL("DROP TABLE IF EXISTS `instantane`")
            }
        }

        /**
         * L'énergie et la plage du compteur, pour composer son nom dans la
         * langue de l'interface. La synchronisation suivante les remplit.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `compteurs` ADD COLUMN `plage` TEXT NOT NULL DEFAULT 'UNIQUE'")
                db.execSQL("ALTER TABLE `compteurs` ADD COLUMN `nomCompteur` TEXT NOT NULL DEFAULT ''")
            }
        }

        @Volatile
        private var instance: BaseLocale? = null

        fun obtenir(contexte: Context): BaseLocale =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    contexte.applicationContext,
                    BaseLocale::class.java,
                    "suivi-compteurs.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7).build().also { instance = it }
            }
    }
}
