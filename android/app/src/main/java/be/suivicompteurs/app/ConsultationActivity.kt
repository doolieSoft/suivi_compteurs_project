package be.suivicompteurs.app

import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import be.suivicompteurs.app.databinding.ActivityConsultationBinding
import be.suivicompteurs.app.donnees.BaseLocale
import java.util.Locale
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Consultation des graphiques et prévisions.
 *
 * Affiche l'application Django dans une WebView quand le serveur répond. Dans
 * le cas contraire, on ne laisse pas l'utilisateur devant une page d'erreur :
 * on présente le dernier instantané reçu, qui contient déjà les chiffres
 * importants — prévision de l'année, évolution, anomalies.
 */
class ConsultationActivity : AppCompatActivity() {

    private lateinit var vues: ActivityConsultationBinding
    private lateinit var reglages: Reglages

    override fun onCreate(etat: Bundle?) {
        super.onCreate(etat)
        vues = ActivityConsultationBinding.inflate(layoutInflater)
        setContentView(vues.root)
        setSupportActionBar(vues.barre)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        vues.barre.setNavigationOnClickListener { finish() }

        ViewCompat.setOnApplyWindowInsetsListener(vues.root) { vue, fenetre ->
            val marges = fenetre.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            vue.setPadding(marges.left, marges.top, marges.right, marges.bottom)
            WindowInsetsCompat.CONSUMED
        }

        reglages = Reglages(this)
        configurerWebView()

        if (reglages.configure) {
            vues.web.loadUrl(reglages.url("/"))
        } else {
            afficherInstantane(getString(R.string.accueil_non_configure))
        }

        // Le bouton « retour » navigue d'abord dans l'historique de la WebView.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (vues.web.canGoBack()) vues.web.goBack() else finish()
            }
        })
    }

    private fun configurerWebView() {
        vues.web.settings.apply {
            javaScriptEnabled = true // les graphiques en dépendent
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
        }
        vues.web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(vue: WebView?, url: String?, favicon: Bitmap?) {
                vues.progression.visibility = View.VISIBLE
            }

            override fun onPageFinished(vue: WebView?, url: String?) {
                vues.progression.visibility = View.GONE
            }

            override fun onReceivedError(
                vue: WebView,
                requete: WebResourceRequest,
                erreur: WebResourceError,
            ) {
                // Seul l'échec de la page principale compte : une ressource
                // secondaire manquante ne justifie pas de tout masquer.
                if (requete.isForMainFrame) {
                    afficherInstantane(getString(R.string.serveur_injoignable))
                }
            }
        }
    }

    /** Replie sur le dernier instantané connu, mis en forme lisiblement. */
    private fun afficherInstantane(motif: String) {
        vues.web.visibility = View.GONE
        vues.progression.visibility = View.GONE
        vues.horsLigne.visibility = View.VISIBLE
        vues.motif.text = motif

        lifecycleScope.launch {
            val instantane = BaseLocale.obtenir(this@ConsultationActivity)
                .instantane().actuel()
            if (instantane == null) {
                vues.resume.setText(R.string.aucun_instantane)
                return@launch
            }
            vues.resume.text = resumer(instantane.json, instantane.genereLe)
        }
    }

    private fun resumer(json: String, genereLe: String): CharSequence = runCatching {
        val lignes = JSONObject(json).getJSONArray("lignes")
        val texte = StringBuilder()
        texte.append(getString(R.string.instantane_du, genereLe.replace('T', ' ')))
        texte.append("\n\n")

        for (i in 0 until lignes.length()) {
            val ligne = lignes.getJSONObject(i)
            if (!ligne.optBoolean("maison_actuelle", true)) continue
            val prevision = ligne.optJSONObject("prevision") ?: continue

            texte.append("▸ ").append(ligne.optString("libelle")).append('\n')
            texte.append("   ")
                .append(getString(R.string.instantane_prevu))
                .append(' ')
                .append(nombre(prevision.optDouble("total_prevu")))
                .append(' ')
                .append(ligne.optString("unite"))
                .append('\n')

            if (!prevision.isNull("evolution_pct")) {
                val evolution = prevision.optDouble("evolution_pct")
                val signe = if (evolution > 0) "+" else ""
                texte.append("   ")
                    .append(signe)
                    .append(String.format(Locale.FRANCE, "%.1f", evolution))
                    .append(" % ")
                    .append(getString(R.string.instantane_vs, prevision.optInt("reference_annee")))
                if (!prevision.isNull("evolution_normalisee_pct")) {
                    val corrigee = prevision.optDouble("evolution_normalisee_pct")
                    val signeCorrige = if (corrigee > 0) "+" else ""
                    texte.append(" · ")
                        .append(signeCorrige)
                        .append(String.format(Locale.FRANCE, "%.1f", corrigee))
                        .append(' ')
                        .append(getString(R.string.instantane_climat_egal))
                }
                texte.append('\n')
            }
            texte.append('\n')
        }
        texte.toString().trimEnd()
    }.getOrElse { getString(R.string.instantane_illisible) }

    /** Mise en forme à la française : virgule décimale, espace des milliers. */
    private fun nombre(valeur: Double): String =
        String.format(Locale.FRANCE, "%,.1f", valeur)
}
