package be.suivicompteurs.app.gestion

import android.content.Context
import be.suivicompteurs.app.R
import be.suivicompteurs.app.classeur.ImportClasseur
import be.suivicompteurs.app.donnees.BaseLocale
import be.suivicompteurs.app.donnees.CompteurHistorique
import be.suivicompteurs.app.donnees.EvenementLocal
import be.suivicompteurs.app.donnees.MaisonLocale
import be.suivicompteurs.app.donnees.ReleveHistorique
import be.suivicompteurs.app.donnees.StationLocale
import be.suivicompteurs.app.donnees.TarifLocal
import be.suivicompteurs.app.moteur.DegresJours
import java.time.LocalDate
import kotlin.math.abs

/** Refus d'enregistrer, avec un motif à montrer tel quel. */
class Refus(val motif: Motif, vararg val details: Any) : Exception(motif.name) {

    /** Le motif en clair, dans la langue de l'interface. */
    fun texte(contexte: Context): String {
        fun jour(iso: Any) = java.time.LocalDate.parse(iso as String)
            .format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM))
        fun nombre(x: Any) = java.text.NumberFormat.getNumberInstance().format(x as Double)
        return when (motif) {
            Motif.INDEX_RECULE -> contexte.getString(R.string.refus_index_recule, jour(details[0]), nombre(details[1]))
            Motif.INDEX_DEPASSE -> contexte.getString(R.string.refus_index_depasse, jour(details[0]), nombre(details[1]))
            Motif.DATE_DEJA_RELEVEE -> contexte.getString(R.string.refus_date_deja_relevee, jour(details[0]))
            Motif.NOM_OBLIGATOIRE -> contexte.getString(R.string.nom_obligatoire)
            Motif.DATE_OBLIGATOIRE -> contexte.getString(R.string.date_obligatoire)
        }
    }
}

enum class Motif { NOM_OBLIGATOIRE, INDEX_RECULE, INDEX_DEPASSE, DATE_DEJA_RELEVEE, DATE_OBLIGATOIRE }

/**
 * Gestion des données de l'appareil, en mode autonome.
 *
 * Mêmes règles que le site : un index ne recule pas, un compteur remplacé est
 * un nouveau compteur, et la liste des compteurs à relever suit les maisons
 * occupées et les compteurs encore posés.
 */
class Gestion(contexte: Context) {

    private val base = BaseLocale.obtenir(contexte)
    private val dao = base.historique()

    suspend fun maisons() = dao.maisons()
    suspend fun maison(id: Long) = dao.maison(id)
    suspend fun station(id: Long?) = id?.let { s -> dao.stations().firstOrNull { it.id == s } }
    suspend fun compteurs(maisonId: Long) = dao.compteursDe(maisonId)
    suspend fun compteur(id: Long) = dao.compteur(id)
    suspend fun releves(compteurId: Long) = dao.relevesDe(compteurId)
    suspend fun tarifs(maisonId: Long) = dao.tarifsDe(maisonId)
    suspend fun evenements(maisonId: Long) = dao.evenementsDe(maisonId)

    // --- maisons ------------------------------------------------------------

    /**
     * Enregistre la maison et sa position. Les degrés-jours dépendent de la
     * station météo : deux maisons au même endroit partagent la leur, une
     * position nouvelle en crée une, que la synchronisation remplira.
     */
    suspend fun enregistrerMaison(maison: MaisonLocale, latitude: Double?, longitude: Double?): Long {
        if (maison.nom.isBlank()) throw Refus(Motif.NOM_OBLIGATOIRE)
        val id = if (maison.id == 0L) dao.prochaineMaison() else maison.id

        var stationId = maison.stationId
        if (latitude != null && longitude != null) {
            val actuelle = station(stationId)
            val proche = { s: StationLocale -> abs(s.latitude - latitude) < 1e-4 && abs(s.longitude - longitude) < 1e-4 }
            stationId = when {
                actuelle != null && proche(actuelle) -> actuelle.id
                else -> dao.stations().firstOrNull(proche)?.id ?: dao.prochaineStation().also {
                    dao.enregistrerStation(
                        StationLocale(it, maison.nom.trim(), latitude, longitude, DegresJours.BASE_BELGE)
                    )
                }
            }
        }
        dao.enregistrerMaison(
            maison.copy(
                id = id,
                nom = maison.nom.trim(),
                stationId = stationId,
                actuelle = maison.dateSortie == null,
            )
        )
        rafraichirSaisie()
        return id
    }

    suspend fun supprimerMaison(id: Long) {
        dao.supprimerMaison(id)
        rafraichirSaisie()
    }

    // --- compteurs ----------------------------------------------------------

    suspend fun enregistrerCompteur(compteur: CompteurHistorique): Long {
        val id = if (compteur.id == 0L) dao.prochainCompteur() else compteur.id
        dao.enregistrerCompteur(compteur.copy(id = id, libelle = compteur.libelle.trim()))
        rafraichirSaisie()
        return id
    }

    /**
     * Remplace un compteur : l'ancien est déposé à [date] et un nouveau, de
     * même énergie, prend sa suite avec son propre index de départ. Les écarts
     * ne sont jamais calculés de l'un à l'autre.
     */
    suspend fun remplacerCompteur(ancienId: Long, date: LocalDate, indexDepart: Double, numero: String): Long {
        val ancien = dao.compteur(ancienId) ?: error("compteur inconnu")
        dao.enregistrerCompteur(ancien.copy(dateDepose = date.toString()))
        val freres = dao.compteursDe(ancien.maisonId).count { it.energie == ancien.energie && it.plage == ancien.plage }
        val nouveau = CompteurHistorique(
            id = dao.prochainCompteur(),
            maisonId = ancien.maisonId,
            energie = ancien.energie,
            plage = ancien.plage,
            unite = ancien.unite,
            libelle = "compteur ${freres + 1}",
            datePose = date.toString(),
            numero = numero.trim(),
            coefKwh = ancien.coefKwh,
            remplaceId = ancien.id,
        )
        dao.enregistrerCompteur(nouveau)
        dao.enregistrerReleve(ReleveHistorique(nouveau.id, date.toString(), indexDepart, annuel = false))
        rafraichirSaisie()
        return nouveau.id
    }

    suspend fun supprimerCompteur(id: Long) {
        dao.supprimerCompteur(id)
        rafraichirSaisie()
    }

    // --- relevés ------------------------------------------------------------

    /**
     * Enregistre un relevé, [dateAvant] étant sa date d'origine s'il est
     * modifié. Comme sur le site, l'index doit rester entre ses voisins.
     */
    suspend fun enregistrerReleve(releve: ReleveHistorique, dateAvant: String?) {
        val autres = dao.relevesDe(releve.compteurId).filter { it.date != dateAvant }
        if (autres.any { it.date == releve.date }) throw Refus(Motif.DATE_DEJA_RELEVEE, releve.date)
        autres.filter { it.date < releve.date }.maxByOrNull { it.date }?.let {
            if (releve.index < it.index) throw Refus(Motif.INDEX_RECULE, it.date, it.index)
        }
        autres.filter { it.date > releve.date }.minByOrNull { it.date }?.let {
            if (releve.index > it.index) throw Refus(Motif.INDEX_DEPASSE, it.date, it.index)
        }
        if (dateAvant != null && dateAvant != releve.date) dao.supprimerReleve(releve.compteurId, dateAvant)
        dao.enregistrerReleve(releve)
        rafraichirSaisie()
    }

    suspend fun supprimerReleve(compteurId: Long, date: String) {
        dao.supprimerReleve(compteurId, date)
        rafraichirSaisie()
    }

    // --- tarifs et événements -----------------------------------------------

    suspend fun enregistrerTarif(tarif: TarifLocal) = dao.enregistrerTarif(tarif)
    suspend fun supprimerTarif(id: Long) = dao.supprimerTarif(id)

    suspend fun enregistrerEvenement(evenement: EvenementLocal) {
        if (evenement.libelle.isBlank()) throw Refus(Motif.NOM_OBLIGATOIRE)
        dao.enregistrerEvenement(evenement.copy(libelle = evenement.libelle.trim()))
    }

    suspend fun supprimerEvenement(id: Long) = dao.supprimerEvenement(id)

    /** La liste de l'écran d'accueil suit les maisons et compteurs actifs. */
    private suspend fun rafraichirSaisie() {
        val aSaisir = ImportClasseur.compteursASaisir(dao.tout())
        base.compteurs().enregistrer(aSaisir)
        base.compteurs().supprimerAbsents(aSaisir.map { it.id })
    }
}
