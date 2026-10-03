package be.suivicompteurs.app.reseau

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Températures moyennes journalières, directement depuis Open-Meteo.
 *
 * Portage de `suivi/services/degres_jours.py`, pour le mode autonome :
 *
 * - `archive-api.open-meteo.com` sert l'historique (réanalyse ERA5) mais
 *   accuse environ 5 jours de retard ;
 * - `api.open-meteo.com` complète les jours récents via `past_days`.
 *
 * Gratuit et sans clé, pour un usage non commercial.
 */
class OpenMeteo {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun temperatures(
        latitude: Double,
        longitude: Double,
        debut: LocalDate,
        fin: LocalDate,
        aujourdhui: LocalDate = LocalDate.now(),
    ): Resultat<Map<LocalDate, Double>> = withContext(Dispatchers.IO) {
        if (debut > fin) return@withContext Resultat.Succes(emptyMap())
        try {
            val parJour = LinkedHashMap<LocalDate, Double>()
            val limiteArchive = aujourdhui.minusDays(DELAI_ARCHIVE_JOURS)

            val finArchive = minOf(fin, limiteArchive)
            if (debut <= finArchive) {
                val url = ARCHIVE.toHttpUrl().newBuilder()
                    .communs(latitude, longitude)
                    .addQueryParameter("start_date", debut.toString())
                    .addQueryParameter("end_date", finArchive.toString())
                    .build()
                lire(appel(url.toString())).forEach { (jour, t) -> parJour[jour] = t }
            }

            // Jours récents que l'archive ne couvre pas encore.
            val debutRecent = maxOf(debut, limiteArchive.plusDays(1))
            if (debutRecent <= fin) {
                val joursPasses = minOf(ChronoUnit.DAYS.between(debutRecent, aujourdhui) + 1, MAX_PAST_DAYS)
                if (joursPasses > 0) {
                    val url = PREVISION.toHttpUrl().newBuilder()
                        .communs(latitude, longitude)
                        .addQueryParameter("past_days", joursPasses.toString())
                        .addQueryParameter("forecast_days", "1")
                        .build()
                    lire(appel(url.toString())).forEach { (jour, t) ->
                        if (jour in debut..fin) parJour.putIfAbsent(jour, t)
                    }
                }
            }
            Resultat.Succes(parJour)
        } catch (e: Exception) {
            Resultat.Echec(e.message ?: "Open-Meteo injoignable", horsLigne = true)
        }
    }

    private fun okhttp3.HttpUrl.Builder.communs(latitude: Double, longitude: Double) = apply {
        addQueryParameter("latitude", "%.5f".format(java.util.Locale.ROOT, latitude))
        addQueryParameter("longitude", "%.5f".format(java.util.Locale.ROOT, longitude))
        addQueryParameter("daily", "temperature_2m_mean")
        addQueryParameter("timezone", "Europe/Brussels")
    }

    private fun appel(url: String): String =
        client.newCall(Request.Builder().url(url).get().build()).execute().use { reponse ->
            if (!reponse.isSuccessful) throw ApiException("Open-Meteo a répondu ${reponse.code}.")
            reponse.body?.string().orEmpty()
        }

    private fun lire(corps: String): List<Pair<LocalDate, Double>> {
        val quotidien = JSONObject(corps).optJSONObject("daily") ?: return emptyList()
        val jours = quotidien.optJSONArray("time") ?: return emptyList()
        val temperatures = quotidien.optJSONArray("temperature_2m_mean") ?: return emptyList()
        return (0 until minOf(jours.length(), temperatures.length()))
            .filter { !temperatures.isNull(it) }
            .map { LocalDate.parse(jours.getString(it)) to temperatures.getDouble(it) }
    }

    companion object {
        private const val ARCHIVE = "https://archive-api.open-meteo.com/v1/archive"
        private const val PREVISION = "https://api.open-meteo.com/v1/forecast"

        /** Retard de publication de la réanalyse ERA5. */
        private const val DELAI_ARCHIVE_JOURS = 6L

        /** Profondeur maximale d'historique acceptée par l'adresse « forecast ». */
        private const val MAX_PAST_DAYS = 92L
    }
}
