package be.suivicompteurs.app.donnees

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Compteur proposé à la saisie : encore posé, dans une maison occupée.
 *
 * Tiré de l'historique à chaque changement, il porte de quoi afficher la liste
 * de l'écran d'accueil et contrôler un index sans recalculer quoi que ce soit.
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
    /**
     * Débit journalier le plus fort jamais relevé sur ce compteur.
     *
     * Sert à borner ce qu'une lecture automatique peut proposer : multiplié
     * par les jours écoulés, il dit ce qu'une progression peut raisonnablement
     * valoir. Sans cette borne, le numéro de série imprimé sur la plaque passe
     * pour un index crédible.
     */
    val consoJournaliereMax: Double = 1.0,
)
