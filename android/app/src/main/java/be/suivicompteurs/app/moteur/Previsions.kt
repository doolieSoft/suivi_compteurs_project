package be.suivicompteurs.app.moteur

import java.time.LocalDate
import kotlin.math.max

/*
 * Projection de la consommation de fin d'année.
 *
 * Deux méthodes, choisies automatiquement :
 *
 * - Méthode thermique — quand le modèle `base + k × DJ` est fiable (cas du
 *   gaz) : les jours restants sont estimés avec les degrés-jours *normaux* de
 *   la saison, ce qui évite d'extrapoler un hiver doux sur un mois de janvier.
 * - Méthode du profil saisonnier — sinon : on mesure, sur les années complètes
 *   passées, quelle fraction de l'année est déjà écoulée en termes de
 *   consommation, et on divise le réalisé par cette fraction.
 *
 * Le réalisé mesuré n'est jamais réécrit : la prévision ne porte que sur les
 * jours non encore couverts par un relevé.
 *
 * L'« année » est l'année civile tant qu'aucun relevé n'est marqué annuel ;
 * ensuite, elle court d'anniversaire en anniversaire du dernier relevé annuel.
 *
 * Portage de `suivi/services/previsions.py`.
 */

/** Méthode retenue pour estimer les jours restants. */
sealed interface Methode {
    /** Modèle thermique appliqué aux degrés-jours normaux. */
    data object Thermique : Methode

    /** Part de l'année déjà consommée les années passées. */
    data class ProfilSaisonnier(val nbAnnees: Int) : Methode

    /** Période encore vide : moyenne des années passées. */
    data class MoyennePassee(val nbAnnees: Int) : Methode

    /** Aucun historique complet : simple prorata du temps écoulé. */
    data object Prorata : Methode

    /** Rien à estimer : la période est entièrement relevée. */
    data object Aucune : Methode
}

/** Part des jours d'une période qui doivent être couverts pour qu'elle serve de référence. */
const val SEUIL_COMPLETUDE = 0.9

/** Au-delà, un relevé annuel est trop loin de la borne pour s'y rattacher. */
const val TOLERANCE_BORNE_JOURS = 45

data class Prevision(
    val annee: Int,
    val realise: Double,
    val joursRealises: Int,
    val estimeRestant: Double,
    val joursRestants: Int,
    val totalPrevu: Double,
    val borneBasse: Double?,
    val borneHaute: Double?,
    val methode: Methode,
    /** Consommation de la période complète précédente. */
    val reference: Double?,
    val referenceAnnee: Int?,
    val referenceNormalisee: Double?,
    /** Premier et dernier jour de la période projetée. */
    val debut: LocalDate,
    val fin: LocalDate,
    val surReleveAnnuel: Boolean = false,
    /**
     * Part estimée avant le premier relevé, quand le suivi a commencé en cours
     * de période (nouvel utilisateur) ; comprise dans [totalPrevu].
     */
    val estimeAvant: Double = 0.0,
    val joursEstimesAvant: Int = 0,
) {
    val evolutionPct: Double?
        get() = reference?.takeIf { it != 0.0 }?.let { 100.0 * (totalPrevu - it) / it }

    /** Évolution une fois l'effet du climat retiré des deux termes. */
    val evolutionNormaliseePct: Double?
        get() = referenceNormalisee?.takeIf { it != 0.0 }?.let { 100.0 * (totalPrevu - it) / it }

    val avancementPct: Double get() = 100.0 * joursRealises / (ecartJours(debut, fin) + 1)

    /** Jour dont l'index sert de point de départ à la période. */
    val borneDepart: LocalDate get() = debut.minusDays(1)
}

/** Année à laquelle on rattache une période : celle où tombe son milieu. */
internal fun millesime(debut: LocalDate): Int = debut.plusDays(182).year

/**
 * Période d'un an, bornée par l'anniversaire de [ancre], où tombe [aujourdhui].
 * Le jour même de la borne ouvre la période suivante.
 */
fun periodeEnCours(ancre: LocalDate, aujourdhui: LocalDate): Pair<LocalDate, LocalDate> {
    var borne = decaler(ancre, aujourdhui.year - ancre.year)
    if (borne > aujourdhui) borne = decaler(ancre, aujourdhui.year - ancre.year - 1)
    val suivante = decaler(ancre, borne.year - ancre.year + 1)
    return borne.plusDays(1) to suivante
}

private class PeriodePassee(val recul: Int, val total: Double, val courbe: List<Double>)

/** Mêmes dates les années précédentes, pour celles qui sont complètes, de la plus récente à la plus ancienne. */
private fun periodesPassees(ligne: Ligne, debut: LocalDate, fin: LocalDate): List<PeriodePassee> {
    val serie = ligne.serie
    val premier = serie.premierJour ?: return emptyList()
    val resultat = mutableListOf<PeriodePassee>()
    var recul = 1
    while (true) {
        val d = decaler(debut, -recul)
        val f = decaler(fin, -recul)
        if (f < premier) break
        val nbJours = ecartJours(d, f) + 1
        val valeurs = (0 until nbJours).map { serie.jours[d.plusDays(it.toLong())] }
        val couverts = valeurs.filterNotNull()
        val total = couverts.somme { it }
        // Période incomplète : profil non représentatif.
        if (couverts.size >= nbJours * SEUIL_COMPLETUDE && total > 0) {
            var cumul = 0.0
            val courbe = valeurs.map { v ->
                cumul += v ?: 0.0
                cumul / total
            }
            resultat += PeriodePassee(recul, total, courbe)
        }
        recul++
    }
    return resultat
}

/**
 * Projette la consommation de [ligne] jusqu'à la fin de la période.
 *
 * Sans précision, la période est celle ouverte par le dernier relevé annuel,
 * ou l'année civile s'il n'y en a pas. [annee] impose une année civile,
 * [periode] des bornes quelconques (premier et dernier jour).
 */
fun prevoir(
    ligne: Ligne,
    aujourdhui: LocalDate,
    normales: Map<Int, Double>,
    annee: Int? = null,
    periode: Pair<LocalDate, LocalDate>? = null,
): Prevision? {
    val serie = ligne.serie
    if (serie.jours.isEmpty()) return null

    var surReleveAnnuel = false
    var anneeRetenue = annee
    var debut: LocalDate? = null
    var fin: LocalDate? = null
    if (periode != null) {
        debut = periode.first
        fin = periode.second
        surReleveAnnuel = true
        anneeRetenue = millesime(debut)
    } else if (anneeRetenue == null) {
        val annuels = ligne.relevesAnnuels.filter { it <= aujourdhui }
        if (annuels.isNotEmpty()) {
            val (d, f) = periodeEnCours(annuels.last(), aujourdhui)
            debut = d
            fin = f
            surReleveAnnuel = true
            anneeRetenue = millesime(d)
        } else {
            anneeRetenue = aujourdhui.year
        }
    }
    if (!surReleveAnnuel) {
        debut = LocalDate.of(anneeRetenue!!, 1, 1)
        fin = LocalDate.of(anneeRetenue, 12, 31)
    }
    debut!!
    fin!!
    anneeRetenue!!

    val joursPeriode = serie.jours.keys.filter { it >= debut && it <= fin }
    // Année civile sans relevé encore : on ne prévoit que si le passé le permet
    // (modèle thermique, années précédentes) ; sinon il n'y a rien à dire.
    if (joursPeriode.isEmpty() && !surReleveAnnuel && serie.premierJour?.let { it > fin } == true) return null

    val realise = joursPeriode.somme { serie.jours.getValue(it) }
    // Au lendemain d'un relevé annuel, la période est ouverte mais encore vide.
    val dernierCouvert = joursPeriode.maxOrNull() ?: debut.minusDays(1)
    val joursRealises = joursPeriode.size

    val debutRestant = dernierCouvert.plusDays(1)
    val joursRestants = max(0, ecartJours(debutRestant, fin) + 1)

    val passees = periodesPassees(ligne, debut, fin)
    // Une année marquée par une consommation anormale (fuite…) ne dit rien de
    // l'année en cours : elle ne sert pas à l'estimer. Sans autre année, on
    // prolonge le rythme de l'année en cours. Elle reste la référence affichée
    // (« par rapport à »).
    val surconsommations = detecterAnomalies(ligne).filterIsInstance<Anomalie.Surconsommation>()
    val profils = passees.filter { p ->
        val d = decaler(debut, -p.recul)
        val f = decaler(fin, -p.recul)
        surconsommations.none { it.debut <= f && it.fin >= d }
    }

    // Sans modèle fiable, on prolonge le rythme des derniers relevés : par
    // degré-jour pour le chauffage (sinon un automne ferait croire à un hiver
    // sobre, et un hiver à un été gourmand), par jour pour le reste.
    val chauffage = ligne.energie == Energie.GAZ || ligne.energie == Energie.MAZOUT
    fun prolongerParDegreJour(du: LocalDate, au: LocalDate): Double? {
        if (!chauffage || normales.isEmpty()) return null
        val recents = serie.jours.entries.sortedBy { it.key }.takeLast(365)
        if (recents.isEmpty() || recents.any { it.key !in serie.djs }) return null
        val djRecents = recents.somme { serie.djs.getValue(it.key) }
        // Sans normale climatique pour ces jours (météo trop récente), on ne sait pas.
        val djVises = DegresJours.normalSurPeriode(normales, du, au)
        if (djRecents <= 0 || djVises <= 0) return null
        return recents.somme { it.value } / djRecents * djVises
    }

    var estime = 0.0
    var borneBasse: Double? = null
    var borneHaute: Double? = null
    var methode: Methode = Methode.Aucune

    if (joursRestants > 0) {
        val modele = serie.modele
        if (modele.fiable && normales.isNotEmpty()) {
            val djRestants = DegresJours.normalSurPeriode(normales, debutRestant, fin)
            estime = modele.base * joursRestants + modele.k * djRestants
            methode = Methode.Thermique
            // Amplitude observée des hivers : ±15 % sur la part chauffage.
            val partChauffage = modele.k * djRestants
            borneBasse = realise + estime - 0.15 * partChauffage
            borneHaute = realise + estime + 0.15 * partChauffage
        } else if (joursPeriode.isEmpty()) {
            // Rien de relevé encore : seules les années passées renseignent, et
            // faute de rythme à prolonger, même les années anormales servent.
            val totaux = profils.ifEmpty { passees }.map { it.total }
            if (totaux.isNotEmpty()) {
                estime = totaux.somme { it } / totaux.size
                methode = Methode.MoyennePassee(totaux.size)
                borneBasse = totaux.min()
                borneHaute = totaux.max()
            } else {
                // Ni année complète ni relevé dans la période (nouvel utilisateur) :
                // le rythme des derniers relevés, prolongé.
                val recents = serie.jours.entries.sortedBy { it.key }.takeLast(365)
                if (recents.isEmpty()) return null
                estime = prolongerParDegreJour(debutRestant, fin)
                    ?: (recents.somme { it.value } / recents.size * joursRestants)
                methode = Methode.Prorata
            }
        } else {
            val rang = ecartJours(debut, dernierCouvert) + 1
            val fractions = profils
                .map { it.courbe[minOf(rang, it.courbe.size) - 1] }
                .filter { it > 0.05 }
            if (fractions.isNotEmpty()) {
                val moyenne = fractions.somme { it } / fractions.size
                estime = realise / moyenne - realise
                methode = Methode.ProfilSaisonnier(fractions.size)
                borneBasse = realise / fractions.max()
                borneHaute = realise / fractions.min()
            } else {
                // Aucun historique complet : simple prorata (par degré-jour pour le chauffage).
                estime = prolongerParDegreJour(debutRestant, fin) ?: (realise / joursRealises * joursRestants)
                methode = Methode.Prorata
            }
        }
    }

    // Suivi commencé en cours de période : les jours d'avant le premier relevé
    // ne sont pas « réalisés » mais estimés, pour prévoir toute la période et
    // non sa seule fin.
    var estimeAvant = 0.0
    var joursEstimesAvant = 0
    val premier = serie.premierJour
    if (premier != null && premier > debut && joursRealises > 0) {
        val finAvant = minOf(premier.minusDays(1), fin)
        joursEstimesAvant = ecartJours(debut, finAvant) + 1
        val modele = serie.modele
        estimeAvant = if (modele.fiable && normales.isNotEmpty()) {
            modele.base * joursEstimesAvant + modele.k * DegresJours.normalSurPeriode(normales, debut, finAvant)
        } else {
            prolongerParDegreJour(debut, finAvant) ?: (realise / joursRealises * joursEstimesAvant)
        }
        borneBasse = borneBasse?.plus(estimeAvant)
        borneHaute = borneHaute?.plus(estimeAvant)
    }

    val totalPrevu = realise + estime + estimeAvant

    // Référence : dernière période complète disponible.
    var reference: Double? = null
    var referenceAnnee: Int? = null
    var referenceNormalisee: Double? = null
    passees.firstOrNull()?.let { derniere ->
        reference = derniere.total
        referenceAnnee = anneeRetenue - derniere.recul
        val refDebut = decaler(debut, -derniere.recul)
        val refFin = decaler(fin, -derniere.recul)
        if (serie.modele.fiable) {
            var djReels = 0.0
            for (jour in serie.jours.keys) {
                if (jour >= refDebut && jour <= refFin) djReels += serie.djs[jour] ?: continue
            }
            val djNormal = DegresJours.normalSurPeriode(normales, refDebut, refFin)
            if (djReels > 0 && djNormal > 0) {
                referenceNormalisee = max(0.0, derniere.total + serie.modele.k * (djNormal - djReels))
            }
        }
    }

    return Prevision(
        annee = anneeRetenue,
        realise = realise,
        joursRealises = joursRealises,
        estimeRestant = estime,
        joursRestants = joursRestants,
        totalPrevu = totalPrevu,
        borneBasse = borneBasse,
        borneHaute = borneHaute,
        methode = methode,
        reference = reference,
        referenceAnnee = referenceAnnee,
        referenceNormalisee = referenceNormalisee,
        debut = debut,
        fin = fin,
        surReleveAnnuel = surReleveAnnuel,
        estimeAvant = estimeAvant,
        joursEstimesAvant = joursEstimesAvant,
    )
}

// ---------------------------------------------------------------------------
// Comparaison à date avec l'an dernier
// ---------------------------------------------------------------------------

/** Même portion d'année, cette année et l'an dernier. */
data class ComparaisonGlissante(
    val jusqua: LocalDate,
    val annee: Int,
    val valeur: Double,
    val anneePrecedente: Int,
    val valeurPrecedente: Double,
    val dj: Double,
    val djPrecedent: Double,
) {
    val evolutionPct: Double?
        get() = if (valeurPrecedente == 0.0) null else 100.0 * (valeur - valeurPrecedente) / valeurPrecedente

    val evolutionDjPct: Double?
        get() = if (djPrecedent == 0.0) null else 100.0 * (dj - djPrecedent) / djPrecedent
}

/** Compare le cumul depuis le 1er janvier à celui de l'an dernier à date. */
fun comparerAAnneePrecedente(ligne: Ligne, aujourdhui: LocalDate): ComparaisonGlissante? {
    val serie = ligne.serie
    if (serie.jours.isEmpty()) return null
    val annee = aujourdhui.year
    val borne = serie.jours.keys.filter { it.year == annee }.maxOrNull() ?: return null
    val bornePrecedente = decaler(borne, -1)

    val debut = LocalDate.of(annee, 1, 1)
    val debutPrecedent = LocalDate.of(annee - 1, 1, 1)
    val valeur = serie.entre(debut, borne)
    val valeurPrecedente = serie.entre(debutPrecedent, bornePrecedente)
    if (valeurPrecedente <= 0) return null

    var dj = 0.0
    var djPrecedent = 0.0
    for ((jour, v) in serie.djs) {
        if (jour >= debut && jour <= borne) dj += v
        if (jour >= debutPrecedent && jour <= bornePrecedente) djPrecedent += v
    }
    return ComparaisonGlissante(borne, annee, valeur, annee - 1, valeurPrecedente, dj, djPrecedent)
}

// ---------------------------------------------------------------------------
// Rejeu : ce que la prévision aurait annoncé, comparé à ce qui s'est passé
// ---------------------------------------------------------------------------

/**
 * Limite d'une période, et le relevé annuel qui s'y rattache. [volumeEstime]
 * est la consommation ventilée des jours qui les séparent : la seule part du
 * total qui ne vient pas d'un index lu.
 */
data class Borne(val date: LocalDate, val releve: LocalDate?, val volumeEstime: Double) {
    /** Positif quand le relevé a été fait après la borne. */
    val ecartJours: Int? get() = releve?.let { ecartJours(date, it) }
}

private fun borne(ligne: Ligne, jour: LocalDate, annuels: List<LocalDate>): Borne {
    val proches = annuels.filter { absJours(it, jour) <= TOLERANCE_BORNE_JOURS }
    if (proches.isEmpty()) return Borne(jour, null, 0.0)
    val releve = proches.minBy { absJours(it, jour) }
    val volume = ligne.serie.entre(minOf(releve, jour).plusDays(1), maxOf(releve, jour))
    return Borne(jour, releve, volume)
}

/** Prévision telle qu'elle aurait été affichée à la date [coupe]. */
data class PointRejeu(val coupe: LocalDate, val prevision: Prevision, val reel: Double) {
    val ecartPct: Double?
        get() = if (reel == 0.0) null else 100.0 * (prevision.totalPrevu - reel) / reel

    val dansFourchette: Boolean?
        get() {
            val basse = prevision.borneBasse ?: return null
            val haute = prevision.borneHaute ?: return null
            return reel in basse..haute
        }
}

data class Rejeu(
    val energie: Energie,
    val plage: Plage,
    val annee: Int,
    val reel: Double,
    val joursReels: Int,
    val points: List<PointRejeu>,
    val debut: LocalDate,
    val fin: LocalDate,
    /** Renseignées quand la période s'appuie sur les relevés annuels. */
    val borneDebut: Borne? = null,
    val borneFin: Borne? = null,
) {
    val surReleveAnnuel: Boolean get() = borneDebut != null

    /** Moyenne des écarts absolus : les erreurs ne se compensent pas. */
    val ecartMoyenPct: Double?
        get() {
            val ecarts = points.mapNotNull { it.ecartPct }.map { kotlin.math.abs(it) }
            return if (ecarts.isEmpty()) null else ecarts.somme { it } / ecarts.size
        }
}

private data class PeriodeRejouable(val debut: LocalDate, val fin: LocalDate, val annuelle: Boolean)

/** Périodes closes d'une ligne, par millésime. */
private fun periodesRejouables(ligne: Ligne): Map<Int, PeriodeRejouable> {
    val serie = ligne.serie
    val premier = serie.premierJour ?: return emptyMap()
    val dernier = serie.dernierJour!!

    val annuels = ligne.relevesAnnuels
    val candidates = mutableListOf<PeriodeRejouable>()
    if (annuels.isNotEmpty()) {
        var (debut, fin) = periodeEnCours(annuels.last(), dernier)
        while (fin >= premier) {
            candidates += PeriodeRejouable(debut, fin, true)
            debut = decaler(debut, -1)
            fin = decaler(fin, -1)
        }
    } else {
        for (annee in premier.year..dernier.year) {
            candidates += PeriodeRejouable(LocalDate.of(annee, 1, 1), LocalDate.of(annee, 12, 31), false)
        }
    }

    val resultat = LinkedHashMap<Int, PeriodeRejouable>()
    for (c in candidates) {
        val nbJours = ecartJours(c.debut, c.fin) + 1
        // Sur relevé annuel, la période doit être réellement refermée.
        if (c.annuelle && c.fin > dernier) continue
        if (serie.nbJoursCouverts(c.debut, c.fin) >= nbJours * SEUIL_COMPLETUDE) {
            resultat[millesime(c.debut)] = c
        }
    }
    return resultat
}

/**
 * La même prévision, telle qu'on la faisait au relevé précédent : dit si le
 * dernier relevé l'a rendue plus pessimiste ou plus optimiste. Absente s'il n'y
 * a pas de relevé précédent dans la même période.
 */
fun prevoirAuRelevePrecedent(ligne: Ligne, djsStation: Map<LocalDate, Double>, actuelle: Prevision): PrevisionPrecedente? {
    val dates = ligne.compteurs.flatMap { c -> c.releves.map { it.date } }
        .filter { ligne.jusqua == null || it <= ligne.jusqua }
        .distinct().sorted()
    if (dates.size < 2) return null
    val precedent = dates[dates.size - 2]
    if (precedent < actuelle.debut.minusDays(1)) return null
    val ligneAvant = lignes(ligne.compteurs, djsStation, jusqua = precedent)
        .firstOrNull { it.energie == ligne.energie && it.plage == ligne.plage } ?: return null
    val prevision = prevoir(ligneAvant, precedent, DegresJours.normales(djsStation, precedent), periode = actuelle.debut to actuelle.fin)
        ?: return null
    return PrevisionPrecedente(precedent, prevision)
}

/** Une prévision et le relevé auquel elle a été faite. */
data class PrevisionPrecedente(val releve: LocalDate, val prevision: Prevision)

/** Années pour lesquelles un total réel existe, la plus récente d'abord. */
fun anneesRejouables(lignes: List<Ligne>): List<Int> =
    lignes.flatMap { periodesRejouables(it).keys }.distinct().sortedDescending()

/**
 * Refait la prévision de [annee] à chacune de ses dates de relevé.
 *
 * À chaque date, tout ce qui est postérieur est masqué : les relevés, donc le
 * modèle thermique et les profils saisonniers, ainsi que la normale
 * climatique. Le total réel, lui, vient de l'historique complet.
 */
fun rejouer(compteurs: List<Compteur>, djsStation: Map<LocalDate, Double>, annee: Int): List<Rejeu> {
    val resultat = mutableListOf<Rejeu>()
    val normalesParCoupe = HashMap<LocalDate, Map<Int, Double>>()

    for (ligne in lignes(compteurs, djsStation)) {
        val periode = periodesRejouables(ligne)[annee] ?: continue
        val reel = ligne.serie.entre(periode.debut, periode.fin)
        // Le relevé qui ouvre la période compte : c'est la première prévision.
        val coupes = ligne.compteurs.flatMap { c -> c.releves.map { it.date } }
            .filter { it >= periode.debut.minusDays(1) && it < periode.fin }
            .distinct().sorted()

        val points = mutableListOf<PointRejeu>()
        for (coupe in coupes) {
            val ligneADate = lignes(ligne.compteurs, djsStation, jusqua = coupe)
                .firstOrNull { it.plage == ligne.plage } ?: continue
            val normales = normalesParCoupe.getOrPut(coupe) { DegresJours.normales(djsStation, coupe) }
            val prevision = if (periode.annuelle) {
                prevoir(ligneADate, coupe, normales, periode = periode.debut to periode.fin)
            } else {
                prevoir(ligneADate, coupe, normales, annee = annee)
            }
            if (prevision == null || prevision.joursRestants == 0) continue
            points += PointRejeu(coupe, prevision, reel)
        }

        if (points.isNotEmpty()) {
            val annuels = if (periode.annuelle) ligne.relevesAnnuels else emptyList()
            resultat += Rejeu(
                energie = ligne.energie,
                plage = ligne.plage,
                annee = annee,
                reel = reel,
                joursReels = ligne.serie.nbJoursCouverts(periode.debut, periode.fin),
                points = points,
                debut = periode.debut,
                fin = periode.fin,
                borneDebut = if (periode.annuelle) borne(ligne, periode.debut.minusDays(1), annuels) else null,
                borneFin = if (periode.annuelle) borne(ligne, periode.fin, annuels) else null,
            )
        }
    }
    return resultat
}
