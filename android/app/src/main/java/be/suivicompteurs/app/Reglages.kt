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

    /** Dernière mise à jour des degrés-jours depuis Open-Meteo. */
    var derniereSynchro: Long
        get() = prefs.getLong(CLE_DERNIERE_SYNCHRO, 0L)
        set(valeur) = prefs.edit().putLong(CLE_DERNIERE_SYNCHRO, valeur).apply()

    companion object {
        private const val CLE_RAPPEL_ACTIF = "rappel_actif"
        private const val CLE_RAPPEL_JOURS = "rappel_jours"
        private const val CLE_DERNIERE_SYNCHRO = "derniere_synchro"
    }
}
