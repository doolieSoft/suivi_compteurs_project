package be.suivicompteurs.app.moteur

import java.time.LocalDate

/*
 * Moteur de calcul : portage en Kotlin des services Python du serveur
 * (consommation, prévisions, degrés-jours, coûts).
 *
 * Il ne dépend ni d'Android ni du réseau : on lui donne des relevés et des
 * degrés-jours, il rend des consommations et des prévisions. C'est ce qui
 * permettra à l'application de se passer du serveur, et ce qui permet de le
 * tester sur le PC, résultat contre résultat, face à la version Python.
 */

enum class Energie(
    val code: String,
    val libelle: String,
    /**
     * Seules ces énergies sont normalisées par les degrés-jours. La
     * consommation d'eau suit l'occupation du logement, pas la météo : une
     * corrélation apparente y serait fortuite, et la « corriger » fausserait tout.
     */
    val thermosensible: Boolean,
    internal val ordre: Int,
) {
    EAU("EAU", "Eau", false, 0),
    GAZ("GAZ", "Gaz", true, 1),
    ELECTRICITE("ELEC", "Électricité", true, 2),

    /** Relevé en litres le plus souvent ; l'unité reste libre sur le compteur. */
    MAZOUT("MAZ", "Mazout", true, 3);

    companion object {
        fun depuisCode(code: String): Energie = entries.first { it.code == code }
    }
}

/** Plage tarifaire, pour les compteurs électriques bi-horaires. */
enum class Plage(val code: String, val libelle: String) {
    UNIQUE("UNIQUE", "Mono-horaire"),
    HAUT("HAUT", "Heures pleines"),
    BAS("BAS", "Heures creuses");

    companion object {
        fun depuisCode(code: String): Plage = entries.first { it.code == code }
    }
}

data class Releve(
    val date: LocalDate,
    val index: Double,
    /** Relevé qui clôt l'année : il devient la borne des prévisions. */
    val annuel: Boolean = false,
)

/**
 * Appareil physique. Quand un compteur est remplacé, l'index repart de zéro :
 * c'est un *nouveau* compteur, et les écarts ne sont jamais calculés de l'un
 * à l'autre.
 */
data class Compteur(
    val id: Long,
    val energie: Energie,
    val plage: Plage = Plage.UNIQUE,
    val unite: String = "m³",
    val datePose: LocalDate? = null,
    val releves: List<Releve>,
)

/**
 * Prix applicable à une énergie sur une période.
 *
 * [prixUnitaire] est exprimé par unité du compteur (€/m³, €/kWh) et
 * [abonnementMensuel] couvre la redevance fixe.
 */
data class Tarif(
    val energie: Energie,
    val dateDebut: LocalDate,
    val dateFin: LocalDate? = null,
    val prixUnitaire: Double = 0.0,
    val abonnementMensuel: Double = 0.0,
    /** Propre à la maison ; sinon tarif générique, valable pour toutes. */
    val propreALaMaison: Boolean = true,
) {
    fun couvre(jour: LocalDate): Boolean =
        !jour.isBefore(dateDebut) && (dateFin == null || !jour.isAfter(dateFin))
}
