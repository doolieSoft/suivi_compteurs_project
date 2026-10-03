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
import be.suivicompteurs.app.classeur.Classeur
import be.suivicompteurs.app.classeur.ClasseurIllisible
import be.suivicompteurs.app.classeur.ExportClasseur
import be.suivicompteurs.app.classeur.ImportClasseur
import be.suivicompteurs.app.databinding.ActivityMainBinding
import be.suivicompteurs.app.databinding.ItemCompteurBinding
import be.suivicompteurs.app.donnees.BaseLocale
import be.suivicompteurs.app.donnees.CompteurLocal
import be.suivicompteurs.app.sync.Synchroniseur
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
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
    private val choisirClasseur =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) confirmerImport(uri)
        }

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
        vues.boutonImporter.setOnClickListener { importer() }
        vues.boutonCreer.setOnClickListener { ouvrirGestion() }

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
        lifecycleScope.launch {
            BaseLocale.obtenir(this@MainActivity).compteurs().suivre().collect { afficher(it) }
        }
    }

    private fun afficher(compteurs: List<CompteurLocal>) {
        adaptateur.remplacer(compteurs)
        vues.listeVide.visibility = if (compteurs.isEmpty()) View.VISIBLE else View.GONE
        vues.liste.visibility = if (compteurs.isEmpty()) View.GONE else View.VISIBLE
        vues.messageVide.setText(R.string.accueil_vide)
        vues.etat.text = if (reglages.derniereSynchro > 0) {
            getString(R.string.meteo_a_jour, momentSynchro())
        } else {
            getString(R.string.donnees_sur_appareil)
        }
    }

    /** « 3 oct. 2026, 11:28 », dans la langue de l'appareil. */
    private fun momentSynchro(): String =
        java.time.Instant.ofEpochMilli(reglages.derniereSynchro)
            .atZone(java.time.ZoneId.systemDefault())
            .format(
                java.time.format.DateTimeFormatter
                    .ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM, java.time.format.FormatStyle.SHORT)
            )

    /** Met à jour la météo ; [manuel] quand l'utilisateur l'a demandé. */
    private fun synchroniser(manuel: Boolean) {
        lifecycleScope.launch {
            vues.rafraichir.isRefreshing = manuel
            val bilan = Synchroniseur(this@MainActivity).executer()
            vues.rafraichir.isRefreshing = false
            if (manuel) {
                val message = when {
                    bilan.horsLigne -> getString(R.string.meteo_hors_ligne)
                    !bilan.reussie -> getString(R.string.meteo_echec, bilan.erreur)
                    else -> getString(R.string.meteo_mise_a_jour)
                }
                Snackbar.make(vues.root, message, Snackbar.LENGTH_LONG).show()
            }
            afficher(adaptateur.elements)
        }
    }

    private fun exporter() {
        val jour = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
        choisirDestinationExport.launch("suivi-compteurs-$jour.xlsx")
    }

    private fun exporterVers(destination: Uri) {
        lifecycleScope.launch {
            val attente = Snackbar.make(vues.root, R.string.export_en_cours, Snackbar.LENGTH_INDEFINITE)
            attente.show()
            val historique = BaseLocale.obtenir(this@MainActivity).historique().tout()
            val ecrit = withContext(Dispatchers.IO) {
                runCatching {
                    val sortie = contentResolver.openOutputStream(destination, "wt")
                        ?: error("destination inaccessible")
                    sortie.use { ExportClasseur.ecrire(historique, it) }
                }
            }
            if (ecrit.isFailure) {
                // Pas de fichier vide ou tronqué dans le dossier choisi.
                withContext(Dispatchers.IO) {
                    runCatching { DocumentsContract.deleteDocument(contentResolver, destination) }
                }
            }
            attente.dismiss()
            Snackbar.make(
                vues.root,
                if (ecrit.isSuccess) R.string.export_reussi else R.string.export_ecriture_impossible,
                Snackbar.LENGTH_LONG,
            ).show()
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

    private fun ouvrirGestion() {
        startActivity(Intent(this, be.suivicompteurs.app.gestion.GestionActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        // La météo se complète d'elle-même ; rien à faire sans maison.
        synchroniser(manuel = false)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.principal, menu)
        return true
    }

    // --- import d'un classeur ------------------------------------------------

    private fun importer() {
        choisirClasseur.launch(arrayOf(TYPE_XLSX, "application/octet-stream"))
    }

    private fun confirmerImport(source: Uri) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_titre)
            .setMessage(R.string.import_confirmation)
            .setNegativeButton(R.string.annuler, null)
            .setPositiveButton(R.string.importer_confirmer) { _, _ -> importerDepuis(source) }
            .show()
    }

    private fun importerDepuis(source: Uri) {
        lifecycleScope.launch {
            val attente = Snackbar.make(vues.root, R.string.import_en_cours, Snackbar.LENGTH_INDEFINITE)
            attente.show()
            val resultat = withContext(Dispatchers.IO) {
                runCatching {
                    val flux = contentResolver.openInputStream(source) ?: error("fichier inaccessible")
                    val classeur = flux.use { Classeur.lire(it) }
                    ImportClasseur.importer(classeur, version = "import-${System.currentTimeMillis()}")
                }
            }
            attente.dismiss()

            resultat.onSuccess { brut ->
                val base = BaseLocale.obtenir(this@MainActivity)
                // Les relevés de l'appareil que le classeur ignore sont repris :
                // rien de ce qui a été noté ici ne se perd.
                val enService = base.compteurs().tous()
                val importe = ImportClasseur.reprendreSaisies(
                    brut,
                    enService,
                    ImportClasseur.relevesDeLAppareil(base.historique().tout(), enService),
                )
                base.historique().remplacer(importe.historique)
                base.compteurs().enregistrer(importe.compteursASaisir)
                base.compteurs().supprimerAbsents(importe.compteursASaisir.map { it.id })
                Snackbar.make(
                    vues.root,
                    resources.getQuantityString(R.plurals.import_reussi, importe.nbReleves, importe.nbReleves),
                    Snackbar.LENGTH_LONG,
                ).show()
                // Complète aussitôt les degrés-jours depuis la date de l'export.
                synchroniser(manuel = false)
            }.onFailure { erreur ->
                val motif = (erreur as? ClasseurIllisible)?.message ?: getString(R.string.import_illisible)
                Snackbar.make(vues.root, motif, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_consulter -> {
            startActivity(Intent(this, TableauDeBordActivity::class.java)); true
        }
        R.id.action_synchroniser -> { synchroniser(manuel = true); true }
        R.id.action_exporter -> { exporter(); true }
        R.id.action_gerer -> { ouvrirGestion(); true }
        R.id.action_importer -> { importer(); true }
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
                "MAZ" -> R.color.mazout
                else -> R.color.elec
            }
        )
        cellule.vues.root.setOnClickListener { auClic(compteur) }
    }

    private fun formaterIndex(valeur: Double): String =
        String.format(Locale.getDefault(), "%,.3f", valeur).trimEnd('0').trimEnd(',')

    private fun formaterDate(iso: String): String = runCatching {
        val source = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(iso)!!
        SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(source)
    }.getOrDefault(iso)
}
