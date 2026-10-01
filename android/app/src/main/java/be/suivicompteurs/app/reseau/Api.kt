package be.suivicompteurs.app.reseau

import be.suivicompteurs.app.Reglages
import be.suivicompteurs.app.donnees.CompteurLocal
import be.suivicompteurs.app.donnees.ReleveLocal
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Résultat d'un appel : soit une valeur, soit un motif d'échec lisible. */
sealed interface Resultat<out T> {
    data class Succes<T>(val valeur: T) : Resultat<T>
    data class Echec(val message: String, val horsLigne: Boolean = false) : Resultat<Nothing>
}

class ApiException(message: String) : Exception(message)

/**
 * Client de l'API Django.
 *
 * Toutes les méthodes sont suspendues et basculent sur [Dispatchers.IO] : aucun
 * appel réseau ne doit jamais s'exécuter sur le fil principal.
 */
class Api(private val reglages: Reglages) {

    private val client = OkHttpClient.Builder()
        // Court à dessein : quand le PC est éteint, mieux vaut échouer vite et
        // basculer en mode hors ligne que de laisser l'utilisateur attendre.
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun requete(chemin: String): Request.Builder =
        Request.Builder()
            .url(reglages.url(chemin))
            .header("X-Jeton", reglages.jeton)

    private fun <T> executer(builder: Request.Builder, lire: (String) -> T): Resultat<T> =
        try {
            client.newCall(builder.build()).execute().use { reponse ->
                val corps = reponse.body?.string().orEmpty()
                if (reponse.isSuccessful) {
                    Resultat.Succes(lire(corps))
                } else {
                    Resultat.Echec(messageErreur(reponse.code, corps))
                }
            }
        } catch (e: Exception) {
            Resultat.Echec(
                e.message ?: "Serveur injoignable",
                horsLigne = true,
            )
        }

    private fun messageErreur(code: Int, corps: String): String {
        val detail = runCatching { JSONObject(corps).optString("erreur") }.getOrNull()
        if (!detail.isNullOrBlank()) return detail
        return when (code) {
            401 -> "Jeton refusé : vérifiez-le dans les réglages."
            404 -> "Adresse introuvable sur le serveur."
            else -> "Le serveur a répondu $code."
        }
    }

    /** Liste des compteurs à relever, avec leur dernier index connu du serveur. */
    suspend fun compteurs(): Resultat<List<CompteurLocal>> = withContext(Dispatchers.IO) {
        executer(requete("/api/etat/").get()) { corps ->
            val tableau = JSONObject(corps).getJSONArray("compteurs")
            (0 until tableau.length()).map { i ->
                val o = tableau.getJSONObject(i)
                CompteurLocal(
                    id = o.getInt("id"),
                    maison = o.getString("maison"),
                    libelle = o.getString("libelle"),
                    energie = o.getString("energie"),
                    unite = o.getString("unite"),
                    decimales = o.optInt("decimales", 3),
                    dernierIndex = if (o.isNull("dernier_index")) null
                    else o.getDouble("dernier_index"),
                    dernierReleve = if (o.isNull("dernier_releve")) null
                    else o.getString("dernier_releve"),
                    consoJournaliereMax = o.optDouble("conso_journaliere_max", 1.0),
                )
            }
        }
    }

    /** Analyse complète, à conserver pour consultation hors ligne. */
    suspend fun instantane(): Resultat<Pair<String, String>> = withContext(Dispatchers.IO) {
        executer(requete("/api/instantane/").get()) { corps ->
            corps to JSONObject(corps).optString("genere_le")
        }
    }

    /**
     * Envoie la file d'attente en un seul appel.
     *
     * Renvoie, pour chaque référence locale, soit `null` si le serveur l'a
     * acceptée, soit le motif du refus.
     */
    suspend fun synchroniser(releves: List<ReleveLocal>): Resultat<Map<String, String?>> =
        withContext(Dispatchers.IO) {
            val tableau = JSONArray()
            releves.forEach { releve ->
                tableau.put(
                    JSONObject().apply {
                        put("reference", releve.reference)
                        put("compteur", releve.compteurId)
                        put("date", releve.date)
                        put("index", releve.index)
                        releve.indexOcr?.let { put("index_ocr", it) }
                        put("commentaire", releve.commentaire)
                        put("a_photo", releve.cheminPhoto != null)
                    }
                )
            }
            val corpsRequete = JSONObject().put("releves", tableau).toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            executer(requete("/api/synchroniser/").post(corpsRequete)) { corps ->
                val resultats = JSONObject(corps).getJSONArray("resultats")
                (0 until resultats.length()).associate { i ->
                    val o = resultats.getJSONObject(i)
                    o.optString("reference") to
                        if (o.optBoolean("accepte")) null else o.optString("erreur")
                }
            }
        }

    /**
     * Envoie la photo d'un relevé déjà enregistré.
     *
     * Séparé de la synchronisation groupée : une photo pèse quelques centaines
     * de kilo-octets, et un échec d'envoi ne doit pas remettre en cause l'index,
     * qui est la donnée réellement importante.
     */
    suspend fun envoyerPhoto(releve: ReleveLocal, photo: File): Resultat<Unit> =
        withContext(Dispatchers.IO) {
            if (!photo.exists()) {
                return@withContext Resultat.Echec("Photo introuvable sur l'appareil.")
            }
            val corps = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("compteur", releve.compteurId.toString())
                .addFormDataPart("date", releve.date)
                .addFormDataPart("index", releve.index.toString())
                .addFormDataPart("commentaire", releve.commentaire)
                .apply { releve.indexOcr?.let { addFormDataPart("index_ocr", it.toString()) } }
                .addFormDataPart(
                    "photo",
                    photo.name,
                    photo.asRequestBody("image/jpeg".toMediaType()),
                )
                .build()
            executer(requete("/api/releves/").post(corps)) { }
        }

    /** Vérifie adresse et jeton. Renvoie le nombre de compteurs trouvés. */
    suspend fun tester(): Resultat<Int> = when (val r = compteurs()) {
        is Resultat.Succes -> Resultat.Succes(r.valeur.size)
        is Resultat.Echec -> r
    }
}
