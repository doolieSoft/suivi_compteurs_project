package be.suivicompteurs.app.classeur

import be.suivicompteurs.app.classeur.EcrivainXlsx.Cellule
import be.suivicompteurs.app.classeur.EcrivainXlsx.Format
import be.suivicompteurs.app.classeur.EcrivainXlsx.Formule
import be.suivicompteurs.app.donnees.CompteurHistorique
import be.suivicompteurs.app.donnees.Historique
import be.suivicompteurs.app.donnees.MaisonLocale
import be.suivicompteurs.app.moteur.Compteur
import be.suivicompteurs.app.moteur.DegresJours
import be.suivicompteurs.app.moteur.Energie
import be.suivicompteurs.app.moteur.Plage
import be.suivicompteurs.app.moteur.Releve
import be.suivicompteurs.app.moteur.comparerAnnees
import be.suivicompteurs.app.moteur.lignes
import java.io.OutputStream
import java.text.Normalizer
import java.time.LocalDate
import kotlin.math.round

/**
 * Export de toutes les données de l'appareil dans un classeur Excel.
 *
 * Même présentation que l'export du serveur (`suivi/services/export_excel.py`)
 * : une feuille par énergie, un bloc par compteur ouvert par son code, les
 * écarts calculés par formule ; puis une synthèse annuelle et les données de
 * référence. Le classeur se réimporte donc indifféremment sur le site ou sur
 * un téléphone.
 */
object ExportClasseur {

    private val SOURCES = mapOf(
        "MANUEL" to "Relevé manuel",
        "FOURNISSEUR" to "Index fournisseur",
        "ESTIME" to "Estimation",
        "IMPORT" to "Import Excel",
    )

    fun ecrire(h: Historique, sortie: OutputStream, aujourdhui: LocalDate = LocalDate.now()) {
        val x = EcrivainXlsx()

        // Maison actuelle d'abord, comme on la consulte le plus souvent.
        val maisons = h.maisons.sortedWith(compareBy({ !it.actuelle }, { it.nom }))
        val codesMaisons = codesUniques(maisons.map { it.id to slugifier(it.nom) })
        val maisonDe = maisons.associateBy { it.id }
        val rangMaison = maisons.withIndex().associate { (i, m) -> m.id to i }
        val compteurs = h.compteurs.sortedWith(
            compareBy<CompteurHistorique>({ rangMaison[it.maisonId] ?: 99 }, { it.plage })
                .thenBy(nullsFirst()) { it.datePose }
        )
        val codesCompteurs = codesUniques(compteurs.map { c ->
            c.id to listOf(
                codesMaisons[c.maisonId].orEmpty(),
                slugifier(Energie.depuisCode(c.energie).libelle),
                slugifier(Plage.depuisCode(c.plage).libelle),
                slugifier(c.libelle),
            ).filter { it.isNotEmpty() }.joinToString("-")
        })
        val relevesDe = h.releves.groupBy { it.compteurId }

        // --- une feuille par énergie ------------------------------------------
        for (energie in Energie.entries) {
            val f = x.feuille(energie.libelle)
            f.largeurs(12, 13, 11, 7, 10, 11, 13, 18, 40)
            for (c in compteurs.filter { it.energie == energie.code }) {
                val releves = relevesDe[c.id].orEmpty().sortedBy { it.date }
                if (releves.isEmpty()) continue
                val maison = maisonDe[c.maisonId]
                val details = buildList {
                    add(libelle(c, maison))
                    add(c.unite)
                    if (c.numero.isNotEmpty()) add("n° ${c.numero}")
                    c.dateDepose?.let { add("déposé le ${LocalDate.parse(it).let(::jourCourt)}") }
                }
                // Le code, en tête de bloc, est ce que l'import reconnaît.
                f.ligne(Cellule(codesCompteurs[c.id], Format.TITRE), null, Cellule(details.joinToString(" · ")))
                f.ligne(*listOf(
                    "Date", "Index", "Écart", "Jours", "Par jour", "Sur un an",
                    "Relevé annuel", "Source", "Commentaire",
                ).map { Cellule(it, Format.GRAS) }.toTypedArray())
                val premiere = f.prochaineLigne
                for (r in releves) {
                    val n = f.prochaineLigne
                    val p = n - 1
                    val calculs = if (n > premiere) listOf(
                        Cellule(Formule("B$n-B$p"), Format.DECIMALES_3),
                        Cellule(Formule("A$n-A$p"), Format.DECIMALES_0),
                        Cellule(Formule("IF(D$n>0,C$n/D$n,\"\")"), Format.DECIMALES_3),
                        Cellule(Formule("IF(D$n>0,E$n*365,\"\")"), Format.DECIMALES_0),
                    ) else listOf(null, null, null, null)
                    f.ligne(
                        Cellule(LocalDate.parse(r.date), Format.DATE),
                        Cellule(r.index, Format.DECIMALES_3),
                        *calculs.toTypedArray(),
                        Cellule(if (r.annuel) "oui" else null),
                        Cellule(SOURCES[r.source] ?: r.source),
                        Cellule(r.commentaire.ifEmpty { null }),
                    )
                }
                f.ligneVide()
            }
        }

        // --- synthèse annuelle ----------------------------------------------------
        val synthese = x.feuille("Par année")
        synthese.figerEnTete()
        synthese.largeurs(14, 28, 8, 14, 7, 15, 15, 13, 16)
        synthese.ligne(*listOf(
            "Maison", "Énergie", "Année", "Consommation", "Unité", "Jours couverts",
            "Année complète", "Degrés-jours", "À climat normal",
        ).map { Cellule(it, Format.GRAS) }.toTypedArray())
        for (maison in maisons) {
            val djs = h.degresJours.filter { it.stationId == maison.stationId }
                .associateTo(LinkedHashMap()) { LocalDate.parse(it.date) to it.dj }
            val normales = DegresJours.normales(djs, aujourdhui)
            val moteur = h.compteurs.filter { it.maisonId == maison.id }.map { c ->
                Compteur(
                    id = c.id,
                    energie = Energie.depuisCode(c.energie),
                    plage = Plage.depuisCode(c.plage),
                    unite = c.unite,
                    datePose = c.datePose?.let(LocalDate::parse),
                    releves = relevesDe[c.id].orEmpty().map { Releve(LocalDate.parse(it.date), it.index, it.annuel) },
                )
            }
            for (ligne in lignes(moteur, djs)) {
                for (a in comparerAnnees(ligne, normales)) {
                    synthese.ligne(
                        Cellule(maison.nom),
                        Cellule(ligne.libelle),
                        Cellule(a.annee),
                        Cellule(arrondi(a.consommation, 3), Format.DECIMALES_1),
                        Cellule(ligne.unite),
                        Cellule(a.joursCouverts),
                        Cellule(if (a.complete) "oui" else "non"),
                        Cellule(arrondi(a.djReel, 1), Format.DECIMALES_0),
                        Cellule(a.consommationNormalisee?.let { arrondi(it, 3) }, Format.DECIMALES_1),
                    )
                }
            }
        }

        // --- données de référence -------------------------------------------------
        val stations = h.stations.associateBy { it.id }
        tableau(
            x, "Maisons",
            listOf(
                "Code", "Nom", "Adresse", "Façades", "Surface chauffée (m²)", "Occupée depuis",
                "Occupée jusqu'au", "Station météo", "Latitude", "Longitude", "Base des degrés-jours", "Notes",
                "Compteurs à relever", "Ville",
            ),
            maisons.map { m ->
                val s = m.stationId?.let { stations[it] }
                listOf(
                    Cellule(codesMaisons[m.id]), Cellule(m.nom), Cellule(m.adresse), Cellule(m.nbFacades),
                    Cellule(m.surface), date(m.dateEntree), date(m.dateSortie), Cellule(s?.nom),
                    Cellule(s?.latitude, Format.DECIMALES_5), Cellule(s?.longitude, Format.DECIMALES_5),
                    Cellule(s?.base), Cellule(m.notes), Cellule(if (m.actuelle) "oui" else "non"),
                    Cellule(m.ville),
                )
            },
        )
        tableau(
            x, "Compteurs",
            listOf(
                "Code", "Maison", "Énergie", "Plage", "Libellé", "Numéro", "Unité",
                "Coefficient kWh", "Posé le", "Déposé le", "Remplace",
            ),
            compteurs.map { c ->
                listOf(
                    Cellule(codesCompteurs[c.id]), Cellule(codesMaisons[c.maisonId]),
                    Cellule(Energie.depuisCode(c.energie).libelle), Cellule(Plage.depuisCode(c.plage).libelle),
                    Cellule(c.libelle), Cellule(c.numero), Cellule(c.unite), Cellule(c.coefKwh),
                    date(c.datePose), date(c.dateDepose), Cellule(c.remplaceId?.let { codesCompteurs[it] }),
                )
            },
            largeurs = listOf(34, 14, 12, 15, 16, 14, 7, 16, 12, 12, 34),
        )
        tableau(
            x, "Événements",
            listOf("Maison", "Date", "Énergie", "Libellé", "Description"),
            h.evenements.sortedBy { it.date }.map { e ->
                listOf(
                    Cellule(codesMaisons[e.maisonId]), date(e.date),
                    Cellule(e.energie.takeIf { it.isNotEmpty() }?.let { Energie.depuisCode(it).libelle }),
                    Cellule(e.libelle), Cellule(e.description),
                )
            },
            largeurs = listOf(14, 12, 12, 40, 60),
        )
        tableau(
            x, "Tarifs",
            listOf(
                "Maison", "Énergie", "Fournisseur", "Début", "Fin", "Prix unitaire (€)",
                "Abonnement (€/mois)", "Notes",
            ),
            h.tarifs.sortedBy { it.debut }.map { t ->
                listOf(
                    Cellule(t.maisonId?.let { codesMaisons[it] }), Cellule(Energie.depuisCode(t.energie).libelle),
                    Cellule(t.fournisseur), date(t.debut), date(t.fin),
                    Cellule(t.prix, Format.DECIMALES_5), Cellule(t.abonnement, Format.DECIMALES_2), Cellule(t.notes),
                )
            },
        )
        tableau(
            x, "Degrés-jours",
            listOf("Station", "Date", "Température moyenne (°C)", "Degrés-jours"),
            h.degresJours.sortedWith(compareBy({ it.stationId }, { it.date })).map { d ->
                listOf(
                    Cellule(stations[d.stationId]?.nom), date(d.date),
                    // La température n'est pas conservée sur l'appareil, seul le
                    // degré-jour compte pour les calculs.
                    null, Cellule(d.dj, Format.DECIMALES_3),
                )
            },
            largeurs = listOf(12, 12, 26, 13),
        )

        x.ecrire(sortie)
    }

    private fun tableau(
        x: EcrivainXlsx,
        nom: String,
        entetes: List<String>,
        lignes: List<List<Cellule?>>,
        largeurs: List<Int>? = null,
    ) {
        val f = x.feuille(nom)
        f.figerEnTete()
        f.largeurs(*(largeurs ?: entetes.map { maxOf(12, it.length + 2) }).toIntArray())
        f.ligne(*entetes.map { Cellule(it, Format.GRAS) }.toTypedArray())
        lignes.forEach { f.ligne(*it.toTypedArray()) }
    }

    private fun date(iso: String?) = Cellule(iso?.let(LocalDate::parse), Format.DATE)

    private fun jourCourt(jour: LocalDate) = "%02d/%02d/%d".format(jour.dayOfMonth, jour.monthValue, jour.year)

    private fun libelle(c: CompteurHistorique, maison: MaisonLocale?) = buildList {
        add(maison?.nom.orEmpty())
        add(Energie.depuisCode(c.energie).libelle)
        Plage.depuisCode(c.plage).takeIf { it != Plage.UNIQUE }?.let { add(it.libelle) }
        if (c.libelle.isNotEmpty()) add("(${c.libelle})")
    }.joinToString(" – ")

    private fun arrondi(x: Double, decimales: Int): Double {
        var facteur = 1.0
        repeat(decimales) { facteur *= 10 }
        return round(x * facteur) / facteur
    }

    /** Codes lisibles et uniques, dans l'ordre donné. */
    private fun codesUniques(bases: List<Pair<Long, String>>): Map<Long, String> {
        val pris = HashSet<String>()
        return bases.associate { (id, base) ->
            var code = base.ifEmpty { "x" }
            var n = 2
            while (!pris.add(code)) code = "${base}-${n++}"
            id to code
        }
    }

    /** Comme `slugify` de Django : « Maison d'essai » → « maison-dessai ». */
    internal fun slugifier(texte: String): String =
        Normalizer.normalize(texte, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}"), "")
            .lowercase()
            .replace(Regex("[^\\w\\s-]"), "")
            .trim()
            .replace(Regex("[-\\s]+"), "-")
}
