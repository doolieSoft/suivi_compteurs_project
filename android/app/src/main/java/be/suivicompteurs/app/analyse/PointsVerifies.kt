package be.suivicompteurs.app.analyse

import be.suivicompteurs.app.donnees.PointVerifie
import be.suivicompteurs.app.moteur.Anomalie
import be.suivicompteurs.app.moteur.Energie
import be.suivicompteurs.app.moteur.Plage
import java.time.LocalDate

/** Code stable du genre d'un point à vérifier, pour la base et le classeur. */
val Anomalie.genre: String
    get() = when (this) {
        is Anomalie.Lacune -> "lacune"
        is Anomalie.IndexFige -> "index_fige"
        is Anomalie.Surconsommation -> "surconsommation"
    }

/**
 * Ce point vérifié porte-t-il sur [anomalie] ? Même énergie, plage et genre, et
 * des périodes qui se recouvrent : corriger un relevé déplace un peu les dates
 * d'un point sans en faire un autre.
 */
fun PointVerifie.couvre(energie: Energie, plage: Plage, anomalie: Anomalie): Boolean =
    this.energie == energie.code && this.plage == plage.code && genre == anomalie.genre &&
        LocalDate.parse(debut) <= anomalie.fin && LocalDate.parse(fin) >= anomalie.debut

/** Les points encore à vérifier : ceux qu'aucun point vérifié ne couvre. */
fun aVerifier(
    energie: Energie,
    plage: Plage,
    anomalies: List<Anomalie>,
    verifies: List<PointVerifie>,
): List<Anomalie> = anomalies.filter { a -> verifies.none { it.couvre(energie, plage, a) } }
