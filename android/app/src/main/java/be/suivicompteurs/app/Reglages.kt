package be.suivicompteurs.app

import android.content.Context
import android.content.SharedPreferences

/**
 * Préférences de l'application.
 *
 * L'adresse du serveur n'est pas codée en dur : le PC reçoit son adresse par
 * DHCP et peut en changer. Elle se corrige donc depuis l'écran des réglages,
 * sans reconstruire l'application.
 */
class Reglages(contexte: Context) {

    private val prefs: SharedPreferences =
        contexte.applicationContext.getSharedPreferences("reglages", Context.MODE_PRIVATE)

    var adresseServeur: String
        get() = prefs.getString(CLE_ADRESSE, "") ?: ""
        set(valeur) = prefs.edit().putString(CLE_ADRESSE, normaliser(valeur)).apply()

    var jeton: String
        get() = prefs.getString(CLE_JETON, "") ?: ""
        set(valeur) = prefs.edit().putString(CLE_JETON, valeur.trim()).apply()

    var rappelActif: Boolean
        get() = prefs.getBoolean(CLE_RAPPEL_ACTIF, false)
        set(valeur) = prefs.edit().putBoolean(CLE_RAPPEL_ACTIF, valeur).apply()

    /** Intervalle entre deux rappels de relevé, en jours. */
    var rappelJours: Int
        get() = prefs.getInt(CLE_RAPPEL_JOURS, 14)
        set(valeur) = prefs.edit().putInt(CLE_RAPPEL_JOURS, valeur.coerceIn(1, 365)).apply()

    var derniereSynchro: Long
        get() = prefs.getLong(CLE_DERNIERE_SYNCHRO, 0L)
        set(valeur) = prefs.edit().putLong(CLE_DERNIERE_SYNCHRO, valeur).apply()

    val configure: Boolean
        get() = adresseServeur.isNotEmpty() && jeton.isNotEmpty()

    fun url(chemin: String): String = adresseServeur.trimEnd('/') + chemin

    companion object {
        private const val CLE_ADRESSE = "adresse_serveur"
        private const val CLE_JETON = "jeton"
        private const val CLE_RAPPEL_ACTIF = "rappel_actif"
        private const val CLE_RAPPEL_JOURS = "rappel_jours"
        private const val CLE_DERNIERE_SYNCHRO = "derniere_synchro"

        /** Adresse purement numérique, donc une machine du réseau local. */
        private val ADRESSE_IP = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

        /**
         * Rend utilisable ce que l'utilisateur a tapé.
         *
         * Saisir un schéma et un port au clavier d'un téléphone est une corvée,
         * on complète donc — mais jamais au point de contredire l'utilisateur :
         *
         * * un schéma explicite est respecté tel quel, sans ajout de port ;
         * * une adresse IP nue désigne le PC du réseau domestique : `http` et
         *   le port 8000 du serveur de développement ;
         * * un nom de domaine nu désigne un hébergeur : `https`, port implicite.
         *
         * Sans cette dernière distinction, `monnom.pythonanywhere.com`
         * deviendrait `http://monnom.pythonanywhere.com:8000`, qui ne répond pas.
         */
        fun normaliser(saisie: String): String {
            val valeur = saisie.trim().trimEnd('/')
            if (valeur.isEmpty()) return ""

            if (valeur.startsWith("http://") || valeur.startsWith("https://")) {
                return valeur
            }

            val hote = valeur.substringBefore('/').substringBefore(':')
            val portPrecise = valeur.substringBefore('/').contains(':')

            return when {
                portPrecise -> "http://$valeur"
                ADRESSE_IP.matches(hote) -> "http://$valeur:8000"
                hote.endsWith(".local") -> "http://$valeur:8000"
                else -> "https://$valeur"
            }
        }
    }
}
