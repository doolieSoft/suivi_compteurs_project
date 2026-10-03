package be.suivicompteurs.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import be.suivicompteurs.app.databinding.ActivityMainBinding
import be.suivicompteurs.app.databinding.ItemCompteurBinding
import be.suivicompteurs.app.donnees.BaseLocale
import be.suivicompteurs.app.donnees.CompteurLocal
import be.suivicompteurs.app.reseau.Api
import be.suivicompteurs.app.reseau.Resultat
import be.suivicompteurs.app.sync.Synchroniseur
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var vues: ActivityMainBinding
    private lateinit var reglages: Reglages
    private val adaptateur = CompteurAdapter { compteur -> ouvrirSaisie(compteur.id) }

    private val demanderNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /**
     * « Enregistrer sous » d'Android : Google Drive y figure comme un dossier
     * parmi d'autres, ce qui évite toute connexion à un compte Google ici.
     */
    private val choisirDestinationExport =
        registerForActivityResult(ActivityResultContracts.CreateDocument(TYPE_XLSX)) { uri ->
            if (uri != null) exporterVers(uri)
        }

    override fun onCreate(etat: Bundle?) {
        super.onCreate(etat)
        vues = ActivityMainBinding.inflate(layoutInflater)
        setContentView(vues.root)
        setSupportActionBar(vues.barre)
        appliquerMargesSysteme()

        reglages = Reglages(this)

        vues.liste.layoutManager = LinearLayoutManager(this)
        vues.liste.adapter = adaptateur

        vues.rafraichir.setOnRefreshListener { synchroniser(manuel = true) }
        vues.boutonReglages.setOnClickListener { ouvrirReglages() }

        observerDonnees()
        demanderNotificationsSiNecessaire()
    }

    /**
     * Android 15 et au-delà dessinent sous les barres système : sans cette
     * compensation, le titre passerait sous l'heure et la liste sous les
     * boutons de navigation.
     */
    private fun appliquerMargesSysteme() {
        ViewCompat.setOnApplyWindowInsetsListener(vues.root) { vue, fenetre ->
            val marges = fenetre.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            vue.setPadding(marges.left, marges.top, marges.right, marges.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun demanderNotificationsSiNecessaire() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && reglages.rappelActif) {
            demanderNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun observerDonnees() {
        val base = BaseLocale.obtenir(this)
        lifecycleScope.launch {
            combine(
                base.compteurs().suivre(),
                base.releves().nombreEnAttente(),
            ) { compteurs, attente -> compteurs to attente }
                .collect { (compteurs, attente) -> afficher(compteurs, attente) }
        }
    }

    private fun afficher(compteurs: List<CompteurLocal>, enAttente: Int) {
        adaptateur.remplacer(compteurs)

        val configure = reglages.configure
        vues.listeVide.visibility = if (compteurs.isEmpty()) View.VISIBLE else View.GONE
        vues.liste.visibility = if (compteurs.isEmpty()) View.GONE else View.VISIBLE
        vues.messageVide.text = when {
            !configure -> getString(R.string.accueil_non_configure)
            else -> getString(R.string.accueil_aucun_compteur)
        }
        vues.boutonReglages.visibility = if (configure) View.GONE else View.VISIBLE

        vues.etat.text = when {
            enAttente > 0 -> resources.getQuantityString(
                R.plurals.releves_en_attente, enAttente, enAttente
            )
            reglages.derniereSynchro > 0 -> getString(
                R.string.derniere_synchro,
                SimpleDateFormat("d MMMM 'à' HH:mm", Locale.FRANCE)
                    .format(Date(reglages.derniereSynchro)),
            )
            else -> getString(R.string.jamais_synchronise)
        }
        vues.etat.setBackgroundResource(
            if (enAttente > 0) R.color.bandeau_attente else R.color.bandeau_calme
        )
    }

    private fun synchroniser(manuel: Boolean) {
        if (!reglages.configure) {
            vues.rafraichir.isRefreshing = false
            Snackbar.make(vues.root, R.string.accueil_non_configure, Snackbar.LENGTH_LONG)
                .setAction(R.string.reglages) { ouvrirReglages() }
                .show()
            return
        }
        lifecycleScope.launch {
            vues.rafraichir.isRefreshing = true
            val bilan = Synchroniseur(this@MainActivity).executer()
            vues.rafraichir.isRefreshing = false

            val message = when {
                !bilan.reussie && bilan.horsLigne ->
                    getString(R.string.synchro_hors_ligne)
                !bilan.reussie -> bilan.erreur ?: getString(R.string.synchro_echec)
                bilan.envoyes == 0 && bilan.refuses == 0 ->
                    getString(R.string.synchro_a_jour)
                bilan.refuses > 0 -> resources.getQuantityString(
                    R.plurals.synchro_refuses, bilan.refuses, bilan.envoyes, bilan.refuses
                )
                else -> resources.getQuantityString(
                    R.plurals.synchro_envoyes, bilan.envoyes, bilan.envoyes
                )
            }
            if (manuel || !bilan.reussie) {
                Snackbar.make(vues.root, message, Snackbar.LENGTH_LONG).show()
            }
            afficher(adaptateur.elements, BaseLocale.obtenir(this@MainActivity)
                .releves().enAttente().count { !it.envoye })
        }
    }

    private fun exporter() {
        if (!reglages.configure) {
            Snackbar.make(vues.root, R.string.accueil_non_configure, Snackbar.LENGTH_LONG)
                .setAction(R.string.reglages) { ouvrirReglages() }
                .show()
            return
        }
        val jour = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
        choisirDestinationExport.launch("suivi-compteurs-$jour.xlsx")
    }

    private fun exporterVers(destination: Uri) {
        lifecycleScope.launch {
            val attente = Snackbar.make(vues.root, R.string.export_en_cours, Snackbar.LENGTH_INDEFINITE)
            attente.show()

            // Téléchargé d'abord à part : un échec réseau ne doit pas laisser
            // un fichier vide ou tronqué dans le dossier Drive choisi.
            val temporaire = File(cacheDir, "export.xlsx")
            val message = when (val resultat = Api(reglages).exporter(temporaire)) {
                is Resultat.Succes -> {
                    val copie = withContext(Dispatchers.IO) {
                        runCatching {
                            val sortie = contentResolver.openOutputStream(destination, "wt")
                                ?: error("destination inaccessible")
                            sortie.use { s -> temporaire.inputStream().use { it.copyTo(s) } }
                        }
                    }
                    if (copie.isSuccess) getString(R.string.export_reussi)
                    else getString(R.string.export_ecriture_impossible)
                }
                is Resultat.Echec -> {
                    withContext(Dispatchers.IO) {
                        runCatching { DocumentsContract.deleteDocument(contentResolver, destination) }
                    }
                    if (resultat.horsLigne) getString(R.string.export_hors_ligne)
                    else getString(R.string.export_echec, resultat.message)
                }
            }
            withContext(Dispatchers.IO) { temporaire.delete() }

            attente.dismiss()
            Snackbar.make(vues.root, message, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun ouvrirSaisie(compteurId: Int) {
        startActivity(
            Intent(this, SaisieActivity::class.java)
                .putExtra(SaisieActivity.EXTRA_COMPTEUR, compteurId)
        )
    }

    private fun ouvrirReglages() {
        startActivity(Intent(this, ReglagesActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        if (reglages.configure) synchroniser(manuel = false)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.principal, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_consulter -> {
            startActivity(Intent(this, ConsultationActivity::class.java)); true
        }
        R.id.action_synchroniser -> { synchroniser(manuel = true); true }
        R.id.action_exporter -> { exporter(); true }
        R.id.action_reglages -> { ouvrirReglages(); true }
        else -> super.onOptionsItemSelected(item)
    }

    companion object {
        private const val TYPE_XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    }
}

/** Liste des compteurs : un appui ouvre directement la saisie. */
class CompteurAdapter(
    private val auClic: (CompteurLocal) -> Unit,
) : RecyclerView.Adapter<CompteurAdapter.Cellule>() {

    var elements: List<CompteurLocal> = emptyList()
        private set

    fun remplacer(nouveaux: List<CompteurLocal>) {
        elements = nouveaux
        notifyDataSetChanged()
    }

    class Cellule(val vues: ItemCompteurBinding) : RecyclerView.ViewHolder(vues.root)

    override fun onCreateViewHolder(parent: ViewGroup, type: Int) = Cellule(
        ItemCompteurBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun getItemCount() = elements.size

    override fun onBindViewHolder(cellule: Cellule, position: Int) {
        val compteur = elements[position]
        val contexte = cellule.vues.root.context

        cellule.vues.libelle.text = compteur.libelle
        cellule.vues.index.text = compteur.dernierIndex?.let {
            contexte.getString(R.string.index_avec_unite, formaterIndex(it), compteur.unite)
        } ?: contexte.getString(R.string.aucun_index)
        cellule.vues.date.text = compteur.dernierReleve?.let {
            contexte.getString(R.string.releve_du, formaterDate(it))
        } ?: ""
        cellule.vues.pastille.setBackgroundResource(
            when (compteur.energie) {
                "EAU" -> R.color.eau
                "GAZ" -> R.color.gaz
                else -> R.color.elec
            }
        )
        cellule.vues.root.setOnClickListener { auClic(compteur) }
    }

    private fun formaterIndex(valeur: Double): String =
        String.format(Locale.FRANCE, "%,.3f", valeur).trimEnd('0').trimEnd(',')

    private fun formaterDate(iso: String): String = runCatching {
        val source = SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE).parse(iso)!!
        SimpleDateFormat("d MMMM yyyy", Locale.FRANCE).format(source)
    }.getOrDefault(iso)
}
