package be.suivicompteurs.app.reseau

/** Résultat d'un appel réseau : soit une valeur, soit un motif d'échec lisible. */
sealed interface Resultat<out T> {
    data class Succes<T>(val valeur: T) : Resultat<T>
    data class Echec(val message: String, val horsLigne: Boolean = false) : Resultat<Nothing>
}

class ApiException(message: String) : Exception(message)
