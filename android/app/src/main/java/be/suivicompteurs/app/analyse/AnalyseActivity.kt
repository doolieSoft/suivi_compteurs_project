package be.suivicompteurs.app.analyse

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import be.suivicompteurs.app.gestion.Theme
import be.suivicompteurs.app.moteur.Energie
import be.suivicompteurs.app.moteur.Plage

/**
 * Écrans d'analyse calculés sur l'appareil : détail d'une énergie,
 * comparaison des années et climat, justesse des prévisions. Pendants des
 * pages du site, sans réseau.
 */
class AnalyseActivity : AppCompatActivity() {

    override fun onCreate(etat: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(etat)
        // La navigation démarre sur un modèle de route, jamais sur une route
        // concrète : les valeurs du détail arrivent donc par l'intention.
        val maisonDemandee = intent.getLongExtra(EXTRA_MAISON, 0L)
        val depart = if (maisonDemandee != 0L) ROUTE_LIGNE else intent.getStringExtra(EXTRA_ECRAN) ?: COMPARAISON
        setContent {
            Theme {
                val nav = rememberNavController()
                NavHost(nav, startDestination = depart) {
                    composable(
                        ROUTE_LIGNE,
                        arguments = listOf(
                            navArgument("maison") { type = NavType.LongType; defaultValue = maisonDemandee },
                            navArgument("energie") { type = NavType.StringType; defaultValue = intent.getStringExtra(EXTRA_ENERGIE) ?: "EAU" },
                            navArgument("plage") { type = NavType.StringType; defaultValue = intent.getStringExtra(EXTRA_PLAGE) ?: "UNIQUE" },
                        ),
                    ) { e ->
                        val a = e.arguments!!
                        EcranLigne(
                            maisonId = a.getLong("maison"),
                            energie = Energie.depuisCode(a.getString("energie")!!),
                            plage = Plage.depuisCode(a.getString("plage")!!),
                            surRetour = { if (nav.previousBackStackEntry == null) finish() else nav.popBackStack() },
                        )
                    }
                    composable(COMPARAISON) {
                        EcranComparaison(
                            surRetour = { if (nav.previousBackStackEntry == null) finish() else nav.popBackStack() },
                            surLigne = { m, en, pl -> nav.navigate(ligne(m, en, pl)) },
                        )
                    }
                    composable(JUSTESSE) { EcranJustesse(surRetour = { if (nav.previousBackStackEntry == null) finish() else nav.popBackStack() }) }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_ECRAN = "ecran"
        private const val EXTRA_MAISON = "maison"
        private const val EXTRA_ENERGIE = "energie"
        private const val EXTRA_PLAGE = "plage"
        private const val ROUTE_LIGNE = "ligne/{maison}/{energie}/{plage}"
        const val COMPARAISON = "comparaison"
        const val JUSTESSE = "justesse"

        fun ouvrirLigne(contexte: Context, maison: Long, energie: Energie, plage: Plage) {
            contexte.startActivity(
                Intent(contexte, AnalyseActivity::class.java)
                    .putExtra(EXTRA_MAISON, maison)
                    .putExtra(EXTRA_ENERGIE, energie.code)
                    .putExtra(EXTRA_PLAGE, plage.code)
            )
        }

        fun ligne(maison: Long, energie: Energie, plage: Plage) = "ligne/$maison/${energie.code}/${plage.code}"

        fun ouvrir(contexte: Context, ecran: String) {
            contexte.startActivity(Intent(contexte, AnalyseActivity::class.java).putExtra(EXTRA_ECRAN, ecran))
        }
    }
}
