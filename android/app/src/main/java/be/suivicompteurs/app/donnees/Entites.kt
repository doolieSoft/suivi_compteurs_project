package be.suivicompteurs.app.donnees

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Copie locale d'un compteur du serveur.
 *
 * Elle existe pour une seule raison : pouvoir proposer la liste des compteurs
 * et contrôler la cohérence d'un index alors que le PC est éteint.
 */
@Entity(tableName = "compteurs")
data class CompteurLocal(
    @PrimaryKey val id: Int,
    val maison: String,
    val libelle: String,
    val energie: String,
    val unite: String,
    /** Nombre de décimales affichées par le compteur, pour placer la virgule. */
    val decimales: Int,
    val dernierIndex: Double?,
    val dernierReleve: String?,
)

/**
 * Relevé saisi sur le téléphone.
 *
 * Il est écrit ici *avant* toute tentative d'envoi : un relevé pris au fond
 * d'une cave ne doit jamais dépendre de la présence du réseau.
 */
@Entity(tableName = "releves")
data class ReleveLocal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Identifiant tiré au sort localement, que le serveur renvoie tel quel. */
    val reference: String,
    val compteurId: Int,
    val compteurLibelle: String,
    val unite: String,
    /** Date au format AAAA-MM-JJ. */
    val date: String,
    val index: Double,
    /** Valeur lue par l'OCR avant correction, conservée pour juger sa fiabilité. */
    val indexOcr: Double? = null,
    val commentaire: String = "",
    val cheminPhoto: String? = null,
    val envoye: Boolean = false,
    val photoEnvoyee: Boolean = false,
    /** Motif du refus par le serveur, le cas échéant. */
    val erreur: String? = null,
    val creeLe: Long = System.currentTimeMillis(),
) {
    /** Vrai tant que quelque chose reste à transmettre. */
    val enAttente: Boolean
        get() = !envoye || (cheminPhoto != null && !photoEnvoyee)
}

/**
 * Dernier instantané de l'analyse reçu du serveur.
 *
 * Stocké tel quel en JSON : son contenu n'est qu'affiché, jamais interrogé,
 * donc le découper en tables n'apporterait rien.
 */
@Entity(tableName = "instantane")
data class InstantaneLocal(
    @PrimaryKey val id: Int = 1,
    val json: String,
    val genereLe: String,
    val recuLe: Long = System.currentTimeMillis(),
)
