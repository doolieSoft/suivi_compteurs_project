package be.suivicompteurs.app

import android.content.Context
import android.content.res.Resources
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * Devise des tarifs et des coûts, indépendante de la langue.
 *
 * La devise est celle choisie dans les réglages, sinon celle de la région :
 * de la langue de l'interface si elle en précise une (fr-BE), sinon de
 * l'appareil. L'écriture, elle, suit la langue : un Belge qui lit l'interface
 * en anglais voit « €1,492 », en français « 1 492 € ».
 */
object Monnaie {

    /** Devises proposées dans les réglages, en plus de celle de la région. */
    val PROPOSEES = listOf("EUR", "USD", "GBP", "CHF", "CAD", "AUD")

    private fun langue(contexte: Context): Locale = contexte.resources.configuration.locales[0]

    /** Devise de la région, faute de choix explicite. */
    fun deRegion(contexte: Context): Currency {
        val region = langue(contexte).country.ifEmpty { Resources.getSystem().configuration.locales[0].country }
        return runCatching { Currency.getInstance(Locale("", region)) }.getOrNull()
            ?: Currency.getInstance("EUR")
    }

    fun devise(contexte: Context): Currency =
        Reglages(contexte).devise.takeIf { it.isNotEmpty() }
            ?.let { runCatching { Currency.getInstance(it) }.getOrNull() }
            ?: deRegion(contexte)

    /** « 1 492 € », « €1,492 », « $1,492 »… ; [decimales] au plus, sans zéros superflus. */
    fun formater(contexte: Context, montant: Double, decimales: Int = 0): String =
        NumberFormat.getCurrencyInstance(langue(contexte)).apply {
            currency = devise(contexte)
            minimumFractionDigits = 0
            maximumFractionDigits = decimales
        }.format(montant)

    fun symbole(contexte: Context): String = devise(contexte).getSymbol(langue(contexte))

    /** « € — euro », pour les listes de choix. */
    fun libelle(contexte: Context, devise: Currency): String =
        "${devise.getSymbol(langue(contexte))} — ${devise.getDisplayName(langue(contexte))}"
}
