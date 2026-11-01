package be.suivicompteurs.app.gestion

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import be.suivicompteurs.app.R

/**
 * Gestion des données de l'appareil, en mode autonome : maisons, compteurs,
 * relevés, tarifs, événements. Pendant des pages d'administration du site.
 */
class GestionActivity : AppCompatActivity() {

    override fun onCreate(etat: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(etat)
        val gestion = Gestion(this)
        val relever = intent.getLongExtra(RELEVE_RADIATEURS, 0L)
        val releverSolde = intent.getLongExtra(RELEVE_SOLDE, 0L)
        val depuisAccueil = relever != 0L || releverSolde != 0L
        // Venu de l'accueil, on y retourne.
        fun retour(nav: NavHostController): () -> Unit = { if (depuisAccueil) finish() else nav.popBackStack() }
        setContent {
            Theme {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "maisons") {
                    composable("maisons") {
                        EcranMaisons(
                            gestion,
                            surRetour = { finish() },
                            surMaison = { nav.navigate("maison/$it") },
                        )
                    }
                    composable(
                        "maison/{id}",
                        arguments = listOf(navArgument("id") { type = NavType.LongType }),
                    ) { entree ->
                        EcranMaison(
                            gestion,
                            id = entree.arguments!!.getLong("id"),
                            surRetour = { nav.popBackStack() },
                            surCompteur = { compteur, maison -> nav.navigate("compteur/$compteur?maison=$maison") },
                        )
                    }
                    composable(
                        "compteur/{id}?maison={maison}",
                        arguments = listOf(
                            navArgument("id") { type = NavType.LongType },
                            navArgument("maison") { type = NavType.LongType; defaultValue = 0L },
                        ),
                    ) { entree ->
                        EcranCompteur(
                            gestion,
                            id = entree.arguments!!.getLong("id"),
                            maisonId = entree.arguments!!.getLong("maison"),
                            surRetour = { nav.popBackStack() },
                            surCompteur = { nav.navigate("compteur/$it") { popUpTo("compteur/{id}?maison={maison}") { inclusive = true } } },
                            surReleveRadiateurs = { compteur, date ->
                                nav.navigate("releve_radiateurs/$compteur" + date?.let { "?date=$it" }.orEmpty())
                            },
                            surReleveSolde = { compteur, date ->
                                nav.navigate("releve_solde/$compteur" + date?.let { "?date=$it" }.orEmpty())
                            },
                        )
                    }
                    composable(
                        "releve_radiateurs/{id}?date={date}",
                        arguments = listOf(
                            navArgument("id") { type = NavType.LongType },
                            navArgument("date") { type = NavType.StringType; nullable = true; defaultValue = null },
                        ),
                    ) { entree ->
                        val compteur = entree.arguments!!.getLong("id")
                        BackHandler(onBack = retour(nav))
                        EcranReleveRadiateurs(
                            gestion,
                            compteurId = compteur,
                            dateInitiale = entree.arguments!!.getString("date"),
                            surRetour = retour(nav),
                            surCompteur = { nav.navigate("compteur/$compteur") },
                        )
                    }
                    composable(
                        "releve_solde/{id}?date={date}",
                        arguments = listOf(
                            navArgument("id") { type = NavType.LongType },
                            navArgument("date") { type = NavType.StringType; nullable = true; defaultValue = null },
                        ),
                    ) { entree ->
                        BackHandler(onBack = retour(nav))
                        EcranReleveSolde(
                            gestion,
                            compteurId = entree.arguments!!.getLong("id"),
                            dateInitiale = entree.arguments!!.getString("date"),
                            surRetour = retour(nav),
                        )
                    }
                }
                // Premier lancement : droit au formulaire de la nouvelle maison,
                // la liste (vide) restant derrière lui.
                if (etat == null && intent.getBooleanExtra(NOUVELLE_MAISON, false)) {
                    androidx.compose.runtime.LaunchedEffect(Unit) { nav.navigate("maison/0") }
                }
                if (etat == null && depuisAccueil) {
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        nav.navigate(if (relever != 0L) "releve_radiateurs/$relever" else "releve_solde/$releverSolde")
                    }
                }
            }
        }
    }

    companion object {
        const val NOUVELLE_MAISON = "nouvelleMaison"

        /** Compteur de type répartiteur dont ouvrir le relevé, depuis l'accueil. */
        const val RELEVE_RADIATEURS = "releveRadiateurs"

        /** Compteur prépayé dont ouvrir le relevé, depuis l'accueil. */
        const val RELEVE_SOLDE = "releveSolde"
    }
}

/** Cadre commun : barre de titre avec retour, suppression facultative, bouton d'ajout. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Cadre(
    titre: String,
    surRetour: () -> Unit,
    messages: SnackbarHostState = remember { SnackbarHostState() },
    surSuppression: (() -> Unit)? = null,
    surAjout: (() -> Unit)? = null,
    defilant: Boolean = true,
    contenu: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titre) },
                navigationIcon = {
                    IconButton(onClick = surRetour) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.retour))
                    }
                },
                actions = {
                    if (surSuppression != null) {
                        IconButton(onClick = surSuppression) {
                            Icon(Icons.Filled.Delete, stringResource(R.string.supprimer))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Couleurs.barre,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
            )
        },
        floatingActionButton = {
            if (surAjout != null) {
                FloatingActionButton(onClick = surAjout) { Icon(Icons.Filled.Add, stringResource(R.string.ajouter)) }
            }
        },
        snackbarHost = { SnackbarHost(messages) },
    ) { marges ->
        // imePadding : le contenu défile au-dessus du clavier au lieu d'être caché dessous.
        val modificateur = Modifier.fillMaxSize().padding(marges).imePadding().padding(horizontal = 16.dp)
        Column(if (defilant) modificateur.verticalScroll(rememberScrollState()) else modificateur) {
            contenu()
        }
    }
}
