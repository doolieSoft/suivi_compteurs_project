package be.suivicompteurs.app.gestion

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
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
        enableEdgeToEdge()
        super.onCreate(etat)
        val gestion = Gestion(this)
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
                        )
                    }
                }
            }
        }
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        floatingActionButton = {
            if (surAjout != null) {
                FloatingActionButton(onClick = surAjout) { Icon(Icons.Filled.Add, stringResource(R.string.ajouter)) }
            }
        },
        snackbarHost = { SnackbarHost(messages) },
    ) { marges ->
        val modificateur = Modifier.fillMaxSize().padding(marges).padding(horizontal = 16.dp)
        Column(if (defilant) modificateur.verticalScroll(rememberScrollState()) else modificateur) {
            contenu()
        }
    }
}
