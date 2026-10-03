package be.suivicompteurs.app.donnees

import be.suivicompteurs.app.R
import android.content.Context
import androidx.room.ColumnInfo
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
    /**
     * Nom complet en français (« Ma maison – Gaz – (compteur 2) »). Sert de clé
     * stable pour rattacher des relevés d'un import à l'autre ; l'affichage le
     * recompose dans la langue de l'interface, voir [nomAffiche].
     */
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
    /** UNIQUE, HAUT ou BAS. */
    @ColumnInfo(defaultValue = "UNIQUE") val plage: String = "UNIQUE",
    /** Libellé propre au compteur (« compteur 2 »), vide le plus souvent. */
    @ColumnInfo(defaultValue = "") val nomCompteur: String = "",
)

/** « Ma maison – Gaz – (compteur 2) », l'énergie et la plage dans la langue de l'interface. */
fun CompteurLocal.nomAffiche(contexte: Context): String = buildList {
    add(maison)
    add(
        contexte.getString(
            when (energie) {
                "EAU" -> R.string.energie_eau
                "GAZ" -> R.string.energie_gaz
                "MAZ" -> R.string.energie_mazout
                else -> R.string.energie_electricite
            }
        )
    )
    when (plage) {
        "HAUT" -> add(contexte.getString(R.string.plage_haute))
        "BAS" -> add(contexte.getString(R.string.plage_basse))
    }
    if (nomCompteur.isNotEmpty()) add("($nomCompteur)")
}.joinToString(" – ")
