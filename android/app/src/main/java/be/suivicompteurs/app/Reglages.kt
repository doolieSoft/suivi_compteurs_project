package be.suivicompteurs.app

import android.content.Context
import android.content.SharedPreferences

/** Préférences de l'application : rappels et dernière mise à jour de la météo. */
class Reglages(contexte: Context) {

    private val prefs: SharedPreferences =
        contexte.applicationContext.getSharedPreferences("reglages", Context.MODE_PRIVATE)

    var rappelActif: Boolean
        get() = prefs.getBoolean(CLE_RAPPEL_ACTIF, false)
        set(valeur) = prefs.edit().putBoolean(CLE_RAPPEL_ACTIF, valeur).apply()

    /** Intervalle entre deux rappels de relevé, en jours. */
    var rappelJours: Int
        get() = prefs.getInt(CLE_RAPPEL_JOURS, 14)
        set(valeur) = prefs.edit().putInt(CLE_RAPPEL_JOURS, valeur.coerceIn(1, 365)).apply()

    /** Échéance du prochain rappel (millisecondes), 0 s'il n'y en a pas. */
    var rappelProchain: Long
        get() = prefs.getLong(CLE_RAPPEL_PROCHAIN, 0L)
        set(valeur) = prefs.edit().putLong(CLE_RAPPEL_PROCHAIN, valeur).apply()

    /** Intervalle en vigueur quand [rappelProchain] a été fixé. */
    var rappelJoursProgrammes: Int
        get() = prefs.getInt(CLE_RAPPEL_JOURS_PROGRAMMES, 0)
        set(valeur) = prefs.edit().putInt(CLE_RAPPEL_JOURS_PROGRAMMES, valeur).apply()

    /** Code ISO de la devise des tarifs ; vide : celle de la région, voir [Monnaie]. */
    var devise: String
        get() = prefs.getString(CLE_DEVISE, "") ?: ""
        set(valeur) = prefs.edit().putString(CLE_DEVISE, valeur).apply()

    /** Dernière mise à jour des degrés-jours depuis Open-Meteo. */
    var derniereSynchro: Long
        get() = prefs.getLong(CLE_DERNIERE_SYNCHRO, 0L)
        set(valeur) = prefs.edit().putLong(CLE_DERNIERE_SYNCHRO, valeur).apply()

    companion object {
        private const val CLE_RAPPEL_ACTIF = "rappel_actif"
        private const val CLE_RAPPEL_JOURS = "rappel_jours"
        private const val CLE_RAPPEL_PROCHAIN = "rappel_prochain"
        private const val CLE_RAPPEL_JOURS_PROGRAMMES = "rappel_jours_programmes"
        private const val CLE_DERNIERE_SYNCHRO = "derniere_synchro"
        private const val CLE_DEVISE = "devise"
    }
}
