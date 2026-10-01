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
import androidx.room.Update
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

@Dao
interface ReleveDao {
    @Query("SELECT * FROM releves ORDER BY date DESC, creeLe DESC")
    fun suivre(): Flow<List<ReleveLocal>>

    /** Relevés qu'il reste à transmettre, les plus anciens d'abord. */
    @Query(
        "SELECT * FROM releves WHERE envoye = 0 " +
            "OR (cheminPhoto IS NOT NULL AND photoEnvoyee = 0) ORDER BY creeLe ASC"
    )
    suspend fun enAttente(): List<ReleveLocal>

    @Query("SELECT COUNT(*) FROM releves WHERE envoye = 0")
    fun nombreEnAttente(): Flow<Int>

    /**
     * Dernier index connu pour un compteur, en tenant compte des relevés pas
     * encore transmis : sans cela, deux saisies successives hors ligne
     * compareraient toutes deux à la même valeur périmée.
     */
    @Query(
        "SELECT `index` FROM releves WHERE compteurId = :compteurId " +
            "ORDER BY date DESC, creeLe DESC LIMIT 1"
    )
    suspend fun dernierIndexLocal(compteurId: Int): Double?

    @Query(
        "SELECT * FROM releves WHERE compteurId = :compteurId " +
            "ORDER BY date DESC, creeLe DESC LIMIT 1"
    )
    suspend fun dernierReleveLocal(compteurId: Int): ReleveLocal?

    @Insert
    suspend fun ajouter(releve: ReleveLocal): Long

    @Update
    suspend fun modifier(releve: ReleveLocal)

    @Query("DELETE FROM releves WHERE id = :id")
    suspend fun supprimer(id: Long)

    /** Purge les relevés transmis depuis longtemps : le serveur en est l'archive. */
    @Query(
        "DELETE FROM releves WHERE envoye = 1 " +
            "AND (cheminPhoto IS NULL OR photoEnvoyee = 1) AND creeLe < :avant"
    )
    suspend fun purger(avant: Long)
}

@Dao
interface InstantaneDao {
    @Query("SELECT * FROM instantane WHERE id = 1")
    fun suivre(): Flow<InstantaneLocal?>

    @Query("SELECT * FROM instantane WHERE id = 1")
    suspend fun actuel(): InstantaneLocal?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enregistrer(instantane: InstantaneLocal)
}

@Database(
    entities = [CompteurLocal::class, ReleveLocal::class, InstantaneLocal::class],
    version = 2,
    exportSchema = true,
)
abstract class BaseLocale : RoomDatabase() {
    abstract fun compteurs(): CompteurDao
    abstract fun releves(): ReleveDao
    abstract fun instantane(): InstantaneDao

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

        @Volatile
        private var instance: BaseLocale? = null

        fun obtenir(contexte: Context): BaseLocale =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    contexte.applicationContext,
                    BaseLocale::class.java,
                    "suivi-compteurs.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
