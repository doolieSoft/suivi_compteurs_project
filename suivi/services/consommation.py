"""Calcul des consommations à partir des relevés d'index.

Deux difficultés que ce module résout :

1. **Les relevés sont irréguliers.** Entre deux index peuvent s'écouler
   7 jours comme 300. Pour comparer des années, il faut répartir chaque écart
   d'index sur les jours qu'il couvre. C'est la *ventilation*.

2. **Répartir linéairement fausse le gaz.** 300 m³ consommés entre octobre et
   mars ne se répartissent pas à parts égales : ils suivent le froid. On ajuste
   donc, par compteur, un modèle ``conso_jour = base + k × DJ_jour`` où ``base``
   couvre l'eau chaude et la cuisson, et ``k`` mesure la sensibilité au climat
   (en m³ par degré-jour). La ventilation suit ensuite ce modèle, en conservant
   exactement le volume mesuré.

Ce même coefficient ``k`` sert à la normalisation climatique : une année froide
et une année douce deviennent comparables via
``conso_corrigée = conso_mesurée + k × (DJ_normal − DJ_réel)``.
"""
from __future__ import annotations

import calendar
import datetime as dt
from collections.abc import Iterable, Sequence
from dataclasses import dataclass, field

from django.urls import reverse

from suivi.models import Compteur, Energie, Maison, Plage
from suivi.services import degres_jours as dj_service

# Correspondance entre codes internes et segments d'URL lisibles.
SLUGS_ENERGIE = {
    Energie.EAU: "eau",
    Energie.GAZ: "gaz",
    Energie.ELECTRICITE: "electricite",
}
ENERGIES_PAR_SLUG = {v: k for k, v in SLUGS_ENERGIE.items()}

SLUGS_PLAGE = {
    Plage.UNIQUE: "total",
    Plage.HAUT: "heures-pleines",
    Plage.BAS: "heures-creuses",
}
PLAGES_PAR_SLUG = {v: k for k, v in SLUGS_PLAGE.items()}

UN_JOUR = dt.timedelta(days=1)

# En deçà, l'ajustement thermique n'est pas jugé fiable et l'on retombe sur une
# ventilation linéaire.
MIN_PERIODES_MODELE = 4
# Écart de DJ moyen/jour entre la période la plus douce et la plus froide. Le
# seuil est bas à dessein : sur des relevés annuels, l'amplitude entre une année
# rigoureuse et une année douce dépasse rarement 1,5 DJ/jour, alors que la
# régression y reste pertinente (signature énergétique annuelle).
MIN_AMPLITUDE_DJ = 1.0

# Seules ces énergies sont normalisées par les degrés-jours. La consommation
# d'eau suit l'occupation du logement, pas la météo : une corrélation apparente
# sur quelques années y serait fortuite, et la « corriger » fausserait tout.
ENERGIES_THERMOSENSIBLES = frozenset({Energie.GAZ, Energie.ELECTRICITE})


# ---------------------------------------------------------------------------
# Périodes entre relevés
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class Periode:
    """Intervalle entre deux relevés consécutifs d'un même compteur.

    Les jours attribués vont de ``debut + 1 jour`` à ``fin`` inclus.
    """

    compteur: Compteur
    debut: dt.date
    fin: dt.date
    volume: float

    @property
    def nb_jours(self) -> int:
        return (self.fin - self.debut).days

    @property
    def volume_journalier(self) -> float:
        return self.volume / self.nb_jours if self.nb_jours else 0.0

    @property
    def jours(self) -> Iterable[dt.date]:
        jour = self.debut + UN_JOUR
        while jour <= self.fin:
            yield jour
            jour += UN_JOUR


def periodes(compteurs: Sequence[Compteur]) -> list[Periode]:
    """Périodes de consommation d'une ligne de compteurs.

    Les écarts d'index ne sont jamais calculés d'un compteur à l'autre : lors
    d'un remplacement, la période à cheval est simplement absente.
    """
    resultat: list[Periode] = []
    for compteur in compteurs:
        releves = list(compteur.releves.order_by("date").values_list("date", "index"))
        for (date_avant, index_avant), (date_apres, index_apres) in zip(
            releves, releves[1:]
        ):
            volume = float(index_apres) - float(index_avant)
            if date_apres <= date_avant or volume < 0:
                # Index en recul : compteur remis à zéro ou saisie erronée.
                continue
            resultat.append(
                Periode(
                    compteur=compteur,
                    debut=date_avant,
                    fin=date_apres,
                    volume=volume,
                )
            )
    resultat.sort(key=lambda p: (p.debut, p.fin))
    return resultat


# ---------------------------------------------------------------------------
# Modèle thermique
# ---------------------------------------------------------------------------


@dataclass
class ModeleThermique:
    """Régression ``conso_jour = base + k × DJ_jour``."""

    base: float = 0.0
    k: float = 0.0
    r2: float = 0.0
    nb_periodes: int = 0
    fiable: bool = False

    def attendu(self, dj_du_jour: float) -> float:
        return self.base + self.k * dj_du_jour

    @property
    def part_chauffage_pct(self) -> float | None:
        """Part de la consommation annuelle imputable au chauffage."""
        if not self.fiable:
            return None
        total = self.base * 365 + self.k * self._dj_reference
        if total <= 0:
            return None
        return 100.0 * (self.k * self._dj_reference) / total

    _dj_reference: float = field(default=2000.0, repr=False)


def ajuster_modele(
    periodes_: Sequence[Periode], djs: dict[dt.date, float]
) -> ModeleThermique:
    """Ajuste le modèle thermique par moindres carrés pondérés.

    Chaque période fournit un point ``(DJ moyen/jour, conso moyenne/jour)``,
    pondéré par sa durée : une période de 300 jours pèse plus qu'une de 7.
    """
    points: list[tuple[float, float, float]] = []  # (x, y, poids)
    for periode in periodes_:
        if periode.nb_jours <= 0:
            continue
        if periode.volume <= 0:
            # Deux index identiques sur une longue période traduisent un relevé
            # manquant, pas une consommation nulle : le point fausserait la droite.
            continue
        jours = list(periode.jours)
        if not all(jour in djs for jour in jours):
            continue  # couverture météo incomplète : point inexploitable
        dj_total = sum(djs[jour] for jour in jours)
        points.append(
            (
                dj_total / periode.nb_jours,
                periode.volume_journalier,
                float(periode.nb_jours),
            )
        )

    modele = ModeleThermique(nb_periodes=len(points))
    if len(points) < MIN_PERIODES_MODELE:
        return modele

    amplitude = max(p[0] for p in points) - min(p[0] for p in points)
    if amplitude < MIN_AMPLITUDE_DJ:
        return modele

    poids_total = sum(p[2] for p in points)
    x_moy = sum(x * w for x, _, w in points) / poids_total
    y_moy = sum(y * w for _, y, w in points) / poids_total

    variance = sum(w * (x - x_moy) ** 2 for x, _, w in points)
    if variance <= 0:
        return modele

    covariance = sum(w * (x - x_moy) * (y - y_moy) for x, y, w in points)
    k = covariance / variance
    base = y_moy - k * x_moy

    if k <= 0:
        # Consommation insensible au froid (eau, souvent électricité).
        return modele
    if base < 0:
        # Une base négative n'a pas de sens : on refait passer la droite par 0.
        denominateur = sum(w * x * x for x, _, w in points)
        k = (
            sum(w * x * y for x, y, w in points) / denominateur
            if denominateur > 0
            else 0.0
        )
        base = 0.0
        if k <= 0:
            return modele

    sce = sum(w * (y - (base + k * x)) ** 2 for x, y, w in points)
    sct = sum(w * (y - y_moy) ** 2 for _, y, w in points)
    r2 = 1 - sce / sct if sct > 0 else 0.0

    modele.base = base
    modele.k = k
    modele.r2 = r2
    modele.fiable = r2 >= 0.5
    if djs:
        modele._dj_reference = sum(djs.values()) / len(djs) * 365
    return modele


# ---------------------------------------------------------------------------
# Ventilation journalière
# ---------------------------------------------------------------------------


@dataclass
class SerieConso:
    """Consommation ventilée au jour le jour."""

    jours: dict[dt.date, float] = field(default_factory=dict)
    modele: ModeleThermique = field(default_factory=ModeleThermique)
    djs: dict[dt.date, float] = field(default_factory=dict)
    lacunes: list[tuple[dt.date, dt.date]] = field(default_factory=list)

    # -- agrégations --------------------------------------------------------

    @property
    def total(self) -> float:
        return sum(self.jours.values())

    @property
    def premier_jour(self) -> dt.date | None:
        return min(self.jours) if self.jours else None

    @property
    def dernier_jour(self) -> dt.date | None:
        return max(self.jours) if self.jours else None

    def entre(self, debut: dt.date, fin: dt.date) -> float:
        return sum(v for j, v in self.jours.items() if debut <= j <= fin)

    def nb_jours_couverts(self, debut: dt.date, fin: dt.date) -> int:
        return sum(1 for j in self.jours if debut <= j <= fin)

    def par_annee(self) -> dict[int, float]:
        cumuls: dict[int, float] = {}
        for jour, valeur in self.jours.items():
            cumuls[jour.year] = cumuls.get(jour.year, 0.0) + valeur
        return dict(sorted(cumuls.items()))

    def par_mois(self) -> dict[tuple[int, int], float]:
        cumuls: dict[tuple[int, int], float] = {}
        for jour, valeur in self.jours.items():
            cle = (jour.year, jour.month)
            cumuls[cle] = cumuls.get(cle, 0.0) + valeur
        return dict(sorted(cumuls.items()))

    def jours_couverts_par_annee(self) -> dict[int, int]:
        cumuls: dict[int, int] = {}
        for jour in self.jours:
            cumuls[jour.year] = cumuls.get(jour.year, 0) + 1
        return dict(sorted(cumuls.items()))

    def cumul_annuel(self, annee: int) -> list[tuple[dt.date, float]]:
        """Courbe cumulée du 1er janvier au dernier jour connu de l'année."""
        cumul = 0.0
        points: list[tuple[dt.date, float]] = []
        for jour in sorted(j for j in self.jours if j.year == annee):
            cumul += self.jours[jour]
            points.append((jour, cumul))
        return points

    def dj_par_annee(self) -> dict[int, float]:
        """DJ cumulés sur les seuls jours réellement couverts par des relevés."""
        cumuls: dict[int, float] = {}
        for jour in self.jours:
            if jour in self.djs:
                cumuls[jour.year] = cumuls.get(jour.year, 0.0) + self.djs[jour]
        return dict(sorted(cumuls.items()))


def ventiler(
    compteurs: Sequence[Compteur],
    *,
    station=None,
    modele: ModeleThermique | None = None,
) -> SerieConso:
    """Répartit les écarts d'index sur les jours qu'ils couvrent."""
    liste_periodes = periodes(compteurs)
    if not liste_periodes:
        return SerieConso()

    debut = min(p.debut for p in liste_periodes) + UN_JOUR
    fin = max(p.fin for p in liste_periodes)
    djs = dj_service.dj_par_jour(station, debut, fin)

    if modele is None:
        thermosensible = all(
            c.energie in ENERGIES_THERMOSENSIBLES for c in compteurs
        )
        modele = (
            ajuster_modele(liste_periodes, djs)
            if thermosensible
            else ModeleThermique(nb_periodes=len(liste_periodes))
        )

    serie = SerieConso(modele=modele, djs=djs)

    for periode in liste_periodes:
        jours = list(periode.jours)
        if not jours:
            continue

        poids: list[float] | None = None
        if modele.fiable and all(jour in djs for jour in jours):
            brut = [modele.attendu(djs[jour]) for jour in jours]
            somme = sum(brut)
            if somme > 0:
                poids = [b / somme for b in brut]

        if poids is None:
            poids = [1.0 / len(jours)] * len(jours)

        for jour, part in zip(jours, poids):
            serie.jours[jour] = serie.jours.get(jour, 0.0) + periode.volume * part

    # Trous de couverture (changement de compteur, relevés interrompus).
    tries = sorted(liste_periodes, key=lambda p: p.fin)
    for precedente, suivante in zip(tries, tries[1:]):
        if suivante.debut > precedente.fin:
            serie.lacunes.append(
                (precedente.fin + UN_JOUR, suivante.debut)
            )

    return serie


# ---------------------------------------------------------------------------
# Lignes de compteurs
# ---------------------------------------------------------------------------


@dataclass
class Ligne:
    """Une énergie d'une maison, tous compteurs successifs confondus."""

    maison: Maison
    energie: str
    plage: str
    compteurs: list[Compteur]
    serie: SerieConso

    @property
    def cle(self) -> str:
        return f"{self.maison.slug}-{self.energie}-{self.plage}".lower()

    @property
    def slug_energie(self) -> str:
        return SLUGS_ENERGIE.get(self.energie, self.energie.lower())

    @property
    def slug_plage(self) -> str:
        return SLUGS_PLAGE.get(self.plage, self.plage.lower())

    def get_absolute_url(self) -> str:
        return reverse(
            "suivi:ligne_detail",
            args=[self.maison.slug, self.slug_energie, self.slug_plage],
        )

    @property
    def thermosensible(self) -> bool:
        return self.energie in ENERGIES_THERMOSENSIBLES

    @property
    def unite(self) -> str:
        return self.compteurs[0].unite if self.compteurs else ""

    @property
    def libelle_energie(self) -> str:
        libelle = dict(Energie.choices).get(self.energie, self.energie)
        if self.plage != Plage.UNIQUE:
            libelle += f" – {dict(Plage.choices).get(self.plage, self.plage)}"
        return libelle

    @property
    def libelle(self) -> str:
        return f"{self.maison.nom} – {self.libelle_energie}"


def lignes_de(maison: Maison, *, energie: str | None = None) -> list[Ligne]:
    """Construit les lignes analysables d'une maison."""
    qs = maison.compteurs.all().order_by("energie", "plage", "date_pose", "id")
    if energie:
        qs = qs.filter(energie=energie)

    groupes: dict[tuple[str, str], list[Compteur]] = {}
    for compteur in qs:
        groupes.setdefault((compteur.energie, compteur.plage), []).append(compteur)

    ordre = {Energie.EAU: 0, Energie.GAZ: 1, Energie.ELECTRICITE: 2}
    resultat: list[Ligne] = []
    for (energie_code, plage), compteurs in sorted(
        groupes.items(), key=lambda kv: (ordre.get(kv[0][0], 9), kv[0][1])
    ):
        serie = ventiler(compteurs, station=maison.station)
        resultat.append(
            Ligne(
                maison=maison,
                energie=energie_code,
                plage=plage,
                compteurs=compteurs,
                serie=serie,
            )
        )
    return resultat


def ligne_unique(maison: Maison, energie: str, plage: str) -> Ligne | None:
    for ligne in lignes_de(maison, energie=energie):
        if ligne.plage == plage:
            return ligne
    return None


# ---------------------------------------------------------------------------
# Normalisation climatique
# ---------------------------------------------------------------------------


@dataclass
class AnneeComparee:
    annee: int
    consommation: float
    jours_couverts: int
    dj_reel: float
    dj_normal: float
    consommation_normalisee: float | None
    complete: bool

    @property
    def couverture_pct(self) -> float:
        total = 366 if calendar.isleap(self.annee) else 365
        return 100.0 * self.jours_couverts / total

    @property
    def ecart_climatique_pct(self) -> float | None:
        """Écart entre la rigueur de l'année et la normale climatique."""
        if self.dj_normal <= 0:
            return None
        return 100.0 * (self.dj_reel - self.dj_normal) / self.dj_normal


def comparer_annees(
    ligne: Ligne,
    *,
    normales: dict[tuple[int, int], float] | None = None,
    seuil_completude: float = 90.0,
) -> list[AnneeComparee]:
    """Consommation annuelle, brute puis corrigée du climat.

    La correction applique ``+ k × (DJ_normal − DJ_réel)`` : une année plus
    froide que la normale voit sa consommation revue à la baisse, et
    inversement. Elle n'est calculée que si le modèle thermique est fiable.
    """
    serie = ligne.serie
    if not serie.jours:
        return []

    if normales is None:
        normales = dj_service.dj_normaux_par_jour_calendaire(ligne.maison.station)

    conso_annuelle = serie.par_annee()
    jours_annuels = serie.jours_couverts_par_annee()
    dj_annuels = serie.dj_par_annee()

    resultat: list[AnneeComparee] = []
    for annee, consommation in conso_annuelle.items():
        jours_couverts = jours_annuels.get(annee, 0)
        total_jours = 366 if calendar.isleap(annee) else 365
        complete = jours_couverts >= total_jours * seuil_completude / 100

        # La normale est restreinte aux mêmes jours que ceux réellement couverts,
        # sans quoi on comparerait une année partielle à une année pleine.
        dj_normal = 0.0
        for jour in serie.jours:
            if jour.year != annee:
                continue
            cle = (jour.month, 28 if (jour.month, jour.day) == (2, 29) else jour.day)
            dj_normal += normales.get(cle, 0.0)

        dj_reel = dj_annuels.get(annee, 0.0)
        normalisee = None
        if serie.modele.fiable and dj_normal > 0 and dj_reel > 0:
            normalisee = consommation + serie.modele.k * (dj_normal - dj_reel)
            normalisee = max(0.0, normalisee)

        resultat.append(
            AnneeComparee(
                annee=annee,
                consommation=consommation,
                jours_couverts=jours_couverts,
                dj_reel=dj_reel,
                dj_normal=dj_normal,
                consommation_normalisee=normalisee,
                complete=complete,
            )
        )
    return resultat


# ---------------------------------------------------------------------------
# Qualité des données
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class Anomalie:
    niveau: str  # "alerte" | "info"
    debut: dt.date
    fin: dt.date
    message: str


# Un index figé plus longtemps signale presque toujours un relevé oublié.
JOURS_SANS_CONSO_SUSPECT = 60
# Facteurs au-delà desquels une période est jugée anormalement consommatrice.
# Le seuil est plus haut face au modèle thermique : celui-ci absorbe déjà la
# saison, donc un dépassement y est plus significatif et plus rare.
FACTEUR_SURCONSOMMATION_MODELE = 2.5
# Face à une simple médiane annuelle, un doublement suffit à mériter un regard :
# sur l'eau, c'est la signature typique d'une fuite.
FACTEUR_SURCONSOMMATION_MEDIANE = 2.0


def detecter_anomalies(ligne: Ligne) -> list[Anomalie]:
    """Repère les points qui méritent un coup d'œil avant d'interpréter les courbes."""
    liste_periodes = periodes(ligne.compteurs)
    if not liste_periodes:
        return []

    anomalies: list[Anomalie] = []

    for debut, fin in ligne.serie.lacunes:
        anomalies.append(
            Anomalie(
                "info",
                debut,
                fin,
                f"Aucun relevé exploitable pendant {(fin - debut).days + 1} jours "
                "(remplacement de compteur ou suivi interrompu) : "
                "la consommation de cette période est absente des totaux.",
            )
        )

    for periode in liste_periodes:
        if periode.volume == 0 and periode.nb_jours >= JOURS_SANS_CONSO_SUSPECT:
            anomalies.append(
                Anomalie(
                    "alerte",
                    periode.debut,
                    periode.fin,
                    f"Index inchangé pendant {periode.nb_jours} jours "
                    f"({periode.compteur.unite} : {periode.volume:.0f}) : "
                    "relevé probablement manquant dans le fichier source.",
                )
            )

    modele = ligne.serie.modele
    djs = ligne.serie.djs

    if modele.fiable:
        # Sur une énergie de chauffage, comparer un mois de janvier à la moyenne
        # de l'année signalerait le chauffage lui-même comme une anomalie. La
        # référence est donc ce que le modèle thermique prédit pour ces jours-là.
        for periode in liste_periodes:
            jours = list(periode.jours)
            if periode.nb_jours < 7 or not all(j in djs for j in jours):
                continue
            attendu = sum(modele.attendu(djs[j]) for j in jours)
            if attendu <= 0:
                continue
            rapport = periode.volume / attendu
            if rapport >= FACTEUR_SURCONSOMMATION_MODELE:
                anomalies.append(
                    Anomalie(
                        "alerte",
                        periode.debut,
                        periode.fin,
                        f"{periode.volume:.1f} {periode.compteur.unite} en "
                        f"{periode.nb_jours} jours, soit {rapport:.1f}× ce que "
                        "le climat de la période laissait attendre.",
                    )
                )
    else:
        # Référence : consommation journalière médiane, robuste aux extrêmes.
        journalieres = sorted(
            p.volume_journalier
            for p in liste_periodes
            if p.volume > 0 and p.nb_jours > 0
        )
        if len(journalieres) >= 5:
            mediane = journalieres[len(journalieres) // 2]
            for periode in liste_periodes:
                if periode.nb_jours < 7 or mediane <= 0:
                    continue
                rapport = periode.volume_journalier / mediane
                if rapport >= FACTEUR_SURCONSOMMATION_MEDIANE:
                    anomalies.append(
                        Anomalie(
                            "alerte",
                            periode.debut,
                            periode.fin,
                            f"{periode.volume:.1f} {periode.compteur.unite} en "
                            f"{periode.nb_jours} jours, soit {rapport:.1f}× la "
                            "consommation journalière habituelle.",
                        )
                    )

    anomalies.sort(key=lambda a: a.debut)
    return anomalies
