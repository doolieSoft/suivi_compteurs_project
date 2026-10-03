package be.suivicompteurs.app.classeur

import be.suivicompteurs.app.donnees.CompteurHistorique
import be.suivicompteurs.app.donnees.CompteurLocal
import be.suivicompteurs.app.donnees.DegreJourLocal
import be.suivicompteurs.app.donnees.EvenementLocal
import be.suivicompteurs.app.donnees.Historique
import be.suivicompteurs.app.donnees.MaisonLocale
import be.suivicompteurs.app.donnees.ReleveHistorique
import be.suivicompteurs.app.donnees.StationLocale
import be.suivicompteurs.app.donnees.TarifLocal
import be.suivicompteurs.app.moteur.Compteur
import be.suivicompteurs.app.moteur.DegresJours
import be.suivicompteurs.app.moteur.Energie
import be.suivicompteurs.app.moteur.Plage
import be.suivicompteurs.app.moteur.Releve
import be.suivicompteurs.app.moteur.periodes
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.round

/** Ce que l'import produit : l'historique complet et la liste des compteurs à relever. */
data class ResultatImport(
    val historique: Historique,
    val compteursASaisir: List<CompteurLocal>,
    val nbReleves: Int,
)

/**
 * Relit un classeur produit par l'export de l'application.
 *
 * Portage de `_importer_export` (commande `import_excel` du serveur) : maisons
 * et compteurs portent un code, chaque bloc de relevés commence par celui de
 * son compteur, et les colonnes des tableaux sont retrouvées par leur titre.
 * Rien n'est deviné ; les colonnes calculées sont ignorées.
 */
object ImportClasseur {

    private const val FEUILLE_MAISONS = "Maisons"
    private const val FEUILLE_COMPTEURS = "Compteurs"
    private const val FEUILLE_TARIFS = "Tarifs"
    private const val FEUILLE_DJ = "Degrés-jours"
    private const val FEUILLE_EVENEMENTS = "Événements"
    private val FEUILLES_TABLEAUX = setOf(
        FEUILLE_MAISONS, FEUILLE_COMPTEURS, FEUILLE_TARIFS, FEUILLE_DJ, FEUILLE_EVENEMENTS, "Par année",
    )

    /** Libellés des sources de relevé, tels que le serveur les écrit. */
    private val SOURCES = mapOf(
        "Relevé manuel" to "MANUEL",
        "Index fournisseur" to "FOURNISSEUR",
        "Estimation" to "ESTIME",
        "Import Excel" to "IMPORT",
    )

    fun importer(classeur: Classeur, version: String): ResultatImport {
        val feuilleCompteurs = classeur[FEUILLE_COMPTEURS]
        if (feuilleCompteurs == null || enTexte(feuilleCompteurs.valeur(0, 0)) != "Code") {
            throw ClasseurIllisible(
                "Ce classeur n'est pas un export de l'application : la feuille « Compteurs » est absente."
            )
        }

        // --- maisons et stations -------------------------------------------
        val stations = LinkedHashMap<String, StationLocale>()
        val maisons = LinkedHashMap<String, MaisonLocale>()
        val sorties = HashMap<String, LocalDate?>()
        classeur[FEUILLE_MAISONS]?.let { feuille ->
            for (l in tableau(feuille)) {
                val code = enTexte(l["Code"])
                if (code.isEmpty()) continue
                val nomStation = enTexte(l["Station météo"])
                val station = if (nomStation.isEmpty()) null else stations.getOrPut(nomStation) {
                    StationLocale(
                        id = stations.size + 1L,
                        nom = nomStation,
                        latitude = enNombre(l["Latitude"]) ?: 0.0,
                        longitude = enNombre(l["Longitude"]) ?: 0.0,
                        base = enNombre(l["Base des degrés-jours"]) ?: DegresJours.BASE_BELGE,
                    )
                }
                val sortie = enDate(l["Occupée jusqu'au"])
                sorties[code] = sortie
                maisons[code] = MaisonLocale(
                    id = maisons.size + 1L,
                    nom = enTexte(l["Nom"]).ifEmpty { code },
                    actuelle = sortie == null,
                    stationId = station?.id,
                    adresse = enTexte(l["Adresse"]),
                    nbFacades = enNombre(l["Façades"])?.toInt(),
                    surface = enNombre(l["Surface chauffée (m²)"])?.toInt(),
                    dateEntree = enDate(l["Occupée depuis"])?.toString(),
                    dateSortie = sortie?.toString(),
                    notes = enTexte(l["Notes"]),
                )
            }
        }

        // --- compteurs -----------------------------------------------------
        class Fiche(var historique: CompteurHistorique, val depose: Boolean, val maison: MaisonLocale)
        val fiches = LinkedHashMap<String, Fiche>()
        val remplacements = mutableListOf<Pair<String, String>>()
        for (l in tableau(feuilleCompteurs)) {
            val code = enTexte(l["Code"])
            if (code.isEmpty()) continue
            val maison = maisons[enTexte(l["Maison"])] ?: continue
            val energie = choix(enTexte(l["Énergie"]), Energie.entries.map { it.code to it.libelle }) ?: continue
            val plage = choix(enTexte(l["Plage"]), Plage.entries.map { it.code to it.libelle }) ?: Plage.UNIQUE.code
            fiches[code] = Fiche(
                CompteurHistorique(
                    id = fiches.size + 1L,
                    maisonId = maison.id,
                    energie = energie,
                    plage = plage,
                    unite = enTexte(l["Unité"]).ifEmpty { "m³" },
                    libelle = enTexte(l["Libellé"]),
                    datePose = enDate(l["Posé le"])?.toString(),
                    numero = enTexte(l["Numéro"]),
                    coefKwh = enNombre(l["Coefficient kWh"]) ?: 1.0,
                    dateDepose = enDate(l["Déposé le"])?.toString(),
                ),
                depose = enDate(l["Déposé le"]) != null,
                maison = maison,
            )
            enTexte(l["Remplace"]).takeIf { it.isNotEmpty() }?.let { remplacements += code to it }
        }
        // Le compteur remplacé peut figurer plus bas dans la feuille.
        for ((code, ancien) in remplacements) {
            val fiche = fiches.getValue(code)
            fiches[ancien]?.let { fiche.historique = fiche.historique.copy(remplaceId = it.historique.id) }
        }

        // --- relevés : un bloc par compteur, ouvert par son code -------------
        val releves = LinkedHashMap<Pair<Long, String>, ReleveHistorique>()
        for (nom in classeur.noms) {
            if (nom in FEUILLES_TABLEAUX) continue
            val feuille = classeur[nom] ?: continue
            var courant: Fiche? = null
            for (rang in feuille.lignes) {
                val premiere = feuille.valeur(rang, 0)
                if (premiere is String && premiere.trim() in fiches) {
                    courant = fiches.getValue(premiere.trim())
                    continue
                }
                val fiche = courant ?: continue
                val jour = enDate(premiere) ?: continue // en-tête, ligne vide…
                val index = enNombre(feuille.valeur(rang, 1))?.takeIf { it >= 0 } ?: continue
                val annuel = enTexte(feuille.valeur(rang, 6)).lowercase() == "oui"
                val source = enTexte(feuille.valeur(rang, 7)).let { SOURCES[it] ?: it.takeIf(SOURCES.values::contains) }
                val cle = fiche.historique.id to jour.toString()
                releves[cle] = ReleveHistorique(
                    compteurId = fiche.historique.id,
                    date = jour.toString(),
                    index = arrondi3(index),
                    annuel = annuel,
                    source = source ?: "IMPORT",
                    commentaire = enTexte(feuille.valeur(rang, 8)).take(200),
                )
            }
        }

        // --- tarifs ----------------------------------------------------------
        val tarifs = classeur[FEUILLE_TARIFS]?.let { feuille ->
            tableau(feuille).mapNotNull { l ->
                val energie = choix(enTexte(l["Énergie"]), Energie.entries.map { it.code to it.libelle })
                    ?: return@mapNotNull null
                val debut = enDate(l["Début"]) ?: return@mapNotNull null
                TarifLocal(
                    maisonId = maisons[enTexte(l["Maison"])]?.id,
                    energie = energie,
                    debut = debut.toString(),
                    fin = enDate(l["Fin"])?.toString(),
                    prix = enNombre(l["Prix unitaire (€)"]) ?: 0.0,
                    abonnement = enNombre(l["Abonnement (€/mois)"]) ?: 0.0,
                    fournisseur = enTexte(l["Fournisseur"]),
                    notes = enTexte(l["Notes"]),
                )
            }
        }.orEmpty()

        // --- événements --------------------------------------------------------
        val evenements = classeur[FEUILLE_EVENEMENTS]?.let { feuille ->
            tableau(feuille).mapNotNull { l ->
                val maison = maisons[enTexte(l["Maison"])] ?: return@mapNotNull null
                val jour = enDate(l["Date"]) ?: return@mapNotNull null
                val libelle = enTexte(l["Libellé"]).ifEmpty { return@mapNotNull null }
                EvenementLocal(
                    maisonId = maison.id,
                    date = jour.toString(),
                    energie = choix(enTexte(l["Énergie"]), Energie.entries.map { it.code to it.libelle }).orEmpty(),
                    libelle = libelle.take(150),
                    description = enTexte(l["Description"]),
                )
            }
        }.orEmpty()

        // --- degrés-jours ----------------------------------------------------
        val djs = LinkedHashMap<Pair<Long, String>, DegreJourLocal>()
        classeur[FEUILLE_DJ]?.let { feuille ->
            for (l in tableau(feuille)) {
                val station = stations[enTexte(l["Station"])] ?: continue
                val jour = enDate(l["Date"]) ?: continue
                val valeur = enNombre(l["Degrés-jours"]) ?: continue
                djs[station.id to jour.toString()] = DegreJourLocal(station.id, jour.toString(), valeur)
            }
        }

        val historique = Historique(
            version = version,
            maisons = maisons.values.toList(),
            stations = stations.values.toList(),
            compteurs = fiches.values.map { it.historique },
            releves = releves.values.sortedWith(compareBy({ it.compteurId }, { it.date })),
            degresJours = djs.values.sortedWith(compareBy({ it.stationId }, { it.date })),
            tarifs = tarifs,
            evenements = evenements,
        )
        return ResultatImport(historique, compteursASaisir(historique), historique.releves.size)
    }

    /**
     * Relevés que détient déjà l'appareil pour ses compteurs en service. Un
     * nouvel import ne doit rien perdre de ce qui a été noté ici depuis le
     * classeur précédent.
     */
    fun relevesDeLAppareil(
        historique: Historique,
        compteursEnService: List<CompteurLocal>,
    ): List<ReleveHistorique> {
        val enService = compteursEnService.map { it.id.toLong() }.toSet()
        return historique.releves.filter { it.compteurId in enService }
    }

    /**
     * Reprend les relevés saisis sur l'appareil avant l'import.
     *
     * Ils visent les compteurs d'avant (ceux du serveur), dont les numéros ne
     * correspondent pas à ceux du classeur : on les rattache par le nom du
     * compteur, identique des deux côtés. Un relevé que le classeur contient
     * déjà, à la même date, est laissé tel que le classeur le donne.
     */
    fun reprendreSaisies(
        resultat: ResultatImport,
        anciensCompteurs: List<CompteurLocal>,
        saisies: List<ReleveHistorique>,
    ): ResultatImport {
        val libelleDe = anciensCompteurs.associate { it.id to it.libelle }
        val nouveauDe = resultat.compteursASaisir.associate { it.libelle to it.id.toLong() }
        val connus = resultat.historique.releves.map { it.compteurId to it.date }.toHashSet()

        val repris = saisies
            .mapNotNull { s ->
                val id = libelleDe[s.compteurId.toInt()]?.let { nouveauDe[it] } ?: return@mapNotNull null
                if ((id to s.date) in connus) null else s.copy(compteurId = id)
            }
            .distinctBy { it.compteurId to it.date }
        if (repris.isEmpty()) return resultat

        val releves = (resultat.historique.releves + repris)
            .sortedWith(compareBy({ it.compteurId }, { it.date }))
        val parCompteur = releves.groupBy { it.compteurId }
        return resultat.copy(
            historique = resultat.historique.copy(releves = releves),
            compteursASaisir = resultat.compteursASaisir.map { c ->
                val dernier = parCompteur[c.id.toLong()]?.lastOrNull()
                c.copy(dernierIndex = dernier?.index, dernierReleve = dernier?.date)
            },
            nbReleves = releves.size,
        )
    }

    /**
     * Compteurs proposés à la saisie : encore posés, dans une maison encore
     * occupée — les seuls que l'on puisse encore relever.
     */
    fun compteursASaisir(historique: Historique): List<CompteurLocal> {
        val maisons = historique.maisons.associateBy { it.id }
        val parCompteur = historique.releves.groupBy { it.compteurId }
        // Début et fin de service de chaque compteur : date de pose ou premier
        // relevé, dernier relevé.
        val debut = historique.compteurs.associate { c ->
            c.id to listOfNotNull(c.datePose, parCompteur[c.id]?.minOf { it.date }).minOrNull()
        }
        val fin = parCompteur.mapValues { (_, r) -> r.maxOf { it.date } }
        return historique.compteurs.mapNotNull { c ->
            val maison = maisons[c.maisonId]?.takeIf { it.actuelle } ?: return@mapNotNull null
            if (c.dateDepose != null) return@mapNotNull null
            // Un compteur auquel un autre a succédé est déposé, même si la date
            // de dépose manque — données d'avant sa prise en charge, saisie
            // oubliée : on ne propose pas de relever un compteur disparu.
            val successeur = historique.compteurs.any { autre ->
                autre.id != c.id && autre.maisonId == c.maisonId && autre.energie == c.energie &&
                    autre.plage == c.plage &&
                    (autre.remplaceId == c.id || fin[c.id]?.let { (debut[autre.id] ?: "") >= it } == true)
            }
            if (successeur) return@mapNotNull null
            val siens = parCompteur[c.id].orEmpty().sortedBy { it.date }
            val energie = Energie.depuisCode(c.energie)
            val plage = Plage.depuisCode(c.plage)
            val libelle = buildList {
                add(maison.nom)
                add(energie.libelle)
                if (plage != Plage.UNIQUE) add(plage.libelle)
                if (c.libelle.isNotEmpty()) add("(${c.libelle})")
            }.joinToString(" – ")
            val moteur = Compteur(
                id = c.id, energie = energie, plage = plage, unite = c.unite,
                releves = siens.map { Releve(LocalDate.parse(it.date), it.index) },
            )
            // Même calcul que le serveur : sert à borner ce que l'OCR peut proposer.
            val debits = periodes(listOf(moteur)).filter { it.nbJours > 0 && it.volume > 0 }
                .map { it.volumeJournalier }
            CompteurLocal(
                id = c.id.toInt(),
                maison = maison.nom,
                libelle = libelle,
                energie = c.energie,
                unite = c.unite,
                decimales = if (energie == Energie.ELECTRICITE) 1 else 3,
                dernierIndex = siens.lastOrNull()?.index,
                dernierReleve = siens.lastOrNull()?.date,
                consoJournaliereMax = round(max(debits.maxOrNull() ?: 1.0, 1.0) * 1000) / 1000,
            )
        }
    }

    /** Lignes d'un tableau, en dictionnaires indexés par le titre de colonne. */
    private fun tableau(feuille: Feuille): List<Map<String, Any?>> {
        val titres = feuille.ligne(0).map { enTexte(it) }
        return feuille.lignes.filter { it > 0 }.map { rang ->
            val valeurs = feuille.ligne(rang)
            titres.withIndex().associate { (i, titre) -> titre to valeurs.getOrNull(i) }
        }.filter { l -> l.values.any { it != null } }
    }

    /** Code d'un choix à partir de son libellé (« Gaz ») ou de son code (« GAZ »). */
    private fun choix(texte: String, choix: List<Pair<String, String>>): String? =
        choix.firstOrNull { (code, libelle) -> texte == code || texte == libelle }?.first

    private fun arrondi3(x: Double) = round(x * 1000) / 1000
}
