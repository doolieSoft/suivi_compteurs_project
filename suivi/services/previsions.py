"""Projection de la consommation de fin d'année.

Deux méthodes, choisies automatiquement :

* **Méthode thermique** — quand le modèle ``base + k × DJ`` est fiable (cas du
  gaz) : les jours restants sont estimés avec les degrés-jours *normaux* de la
  saison, ce qui évite d'extrapoler un hiver doux sur un mois de janvier.
* **Méthode du profil saisonnier** — sinon : on mesure, sur les années
  complètes passées, quelle fraction de l'année est déjà écoulée en termes de
  consommation, et on divise le réalisé par cette fraction.

Dans les deux cas, le réalisé mesuré n'est jamais réécrit : la prévision ne
porte que sur les jours non encore couverts par un relevé.
"""
from __future__ import annotations

import calendar
import datetime as dt
from dataclasses import dataclass

from suivi.services import degres_jours as dj_service
from suivi.services.consommation import Ligne

UN_JOUR = dt.timedelta(days=1)


@dataclass
class Prevision:
    annee: int
    realise: float
    jours_realises: int
    estime_restant: float
    jours_restants: int
    total_prevu: float
    borne_basse: float | None
    borne_haute: float | None
    methode: str
    reference: float | None  # consommation de l'année complète précédente
    reference_annee: int | None
    reference_normalisee: float | None

    @property
    def evolution_pct(self) -> float | None:
        if not self.reference:
            return None
        return 100.0 * (self.total_prevu - self.reference) / self.reference

    @property
    def evolution_normalisee_pct(self) -> float | None:
        """Évolution une fois l'effet du climat retiré des deux termes."""
        if not self.reference_normalisee:
            return None
        return (
            100.0
            * (self.total_prevu - self.reference_normalisee)
            / self.reference_normalisee
        )

    @property
    def avancement_pct(self) -> float:
        total = 366 if calendar.isleap(self.annee) else 365
        return 100.0 * self.jours_realises / total


def _profil_journalier(ligne: Ligne, annee_cible: int) -> dict[int, list[float]]:
    """Part de l'année consommée jour après jour, pour chaque année complète.

    Renvoie ``{année: [part cumulée au jour 1, …, au jour 365]}``.
    """
    serie = ligne.serie
    jours_par_annee: dict[int, dict[int, float]] = {}
    for jour, valeur in serie.jours.items():
        if jour.year >= annee_cible:
            continue
        jours_par_annee.setdefault(jour.year, {})[jour.timetuple().tm_yday] = valeur

    profils: dict[int, list[float]] = {}
    for annee, valeurs in jours_par_annee.items():
        total_jours = 366 if calendar.isleap(annee) else 365
        if len(valeurs) < total_jours * 0.9:
            continue  # année incomplète : profil non représentatif
        total = sum(valeurs.values())
        if total <= 0:
            continue
        cumul = 0.0
        courbe: list[float] = []
        for rang in range(1, total_jours + 1):
            cumul += valeurs.get(rang, 0.0)
            courbe.append(cumul / total)
        profils[annee] = courbe
    return profils


def prevoir(
    ligne: Ligne,
    *,
    annee: int | None = None,
    aujourdhui: dt.date | None = None,
    normales: dict[tuple[int, int], float] | None = None,
) -> Prevision | None:
    """Projette la consommation de ``ligne`` jusqu'au 31 décembre."""
    serie = ligne.serie
    if not serie.jours:
        return None

    aujourdhui = aujourdhui or dt.date.today()
    annee = annee or aujourdhui.year

    debut_annee = dt.date(annee, 1, 1)
    fin_annee = dt.date(annee, 12, 31)

    jours_annee = [j for j in serie.jours if j.year == annee]
    if not jours_annee:
        return None

    realise = sum(serie.jours[j] for j in jours_annee)
    dernier_couvert = max(jours_annee)
    jours_realises = len(jours_annee)

    debut_restant = dernier_couvert + UN_JOUR
    jours_restants = max(0, (fin_annee - debut_restant).days + 1)

    if normales is None:
        normales = dj_service.dj_normaux_par_jour_calendaire(ligne.maison.station)

    estime = 0.0
    borne_basse: float | None = None
    borne_haute: float | None = None
    methode = "linéaire"

    if jours_restants > 0:
        modele = serie.modele
        if modele.fiable and normales:
            dj_restants = dj_service.dj_normal_sur_periode(
                normales, debut_restant, fin_annee
            )
            estime = modele.base * jours_restants + modele.k * dj_restants
            methode = "modèle thermique (DJ normaux)"
            # Amplitude observée des hivers : ±15 % sur la part chauffage.
            part_chauffage = modele.k * dj_restants
            borne_basse = realise + estime - 0.15 * part_chauffage
            borne_haute = realise + estime + 0.15 * part_chauffage
        else:
            profils = _profil_journalier(ligne, annee)
            rang = dernier_couvert.timetuple().tm_yday
            fractions = [
                courbe[min(rang, len(courbe)) - 1]
                for courbe in profils.values()
                if courbe
            ]
            fractions = [f for f in fractions if f > 0.05]
            if fractions:
                moyenne = sum(fractions) / len(fractions)
                estime = realise / moyenne - realise
                methode = f"profil saisonnier ({len(fractions)} année(s) de référence)"
                borne_basse = realise / max(fractions)
                borne_haute = realise / min(fractions)
            else:
                # Aucun historique complet : simple prorata temporis.
                total_jours = 366 if calendar.isleap(annee) else 365
                estime = realise / jours_realises * jours_restants
                methode = "prorata temporis (pas d'historique complet)"

    total_prevu = realise + estime

    # Référence : dernière année civile complète disponible.
    reference = reference_annee = reference_normalisee = None
    conso_par_annee = serie.par_annee()
    jours_par_annee = serie.jours_couverts_par_annee()
    for candidate in sorted((a for a in conso_par_annee if a < annee), reverse=True):
        total_jours = 366 if calendar.isleap(candidate) else 365
        if jours_par_annee.get(candidate, 0) >= total_jours * 0.9:
            reference_annee = candidate
            reference = conso_par_annee[candidate]
            break

    if reference is not None and serie.modele.fiable:
        dj_reels = serie.dj_par_annee().get(reference_annee, 0.0)
        dj_normal = dj_service.dj_normal_sur_periode(
            normales, dt.date(reference_annee, 1, 1), dt.date(reference_annee, 12, 31)
        )
        if dj_reels > 0 and dj_normal > 0:
            reference_normalisee = max(
                0.0, reference + serie.modele.k * (dj_normal - dj_reels)
            )

    return Prevision(
        annee=annee,
        realise=realise,
        jours_realises=jours_realises,
        estime_restant=estime,
        jours_restants=jours_restants,
        total_prevu=total_prevu,
        borne_basse=borne_basse,
        borne_haute=borne_haute,
        methode=methode,
        reference=reference,
        reference_annee=reference_annee,
        reference_normalisee=reference_normalisee,
    )


@dataclass
class ComparaisonGlissante:
    """Même portion d'année, cette année et l'an dernier."""

    jusqua: dt.date
    annee: int
    valeur: float
    annee_precedente: int
    valeur_precedente: float
    dj: float
    dj_precedent: float

    @property
    def evolution_pct(self) -> float | None:
        if not self.valeur_precedente:
            return None
        return 100.0 * (self.valeur - self.valeur_precedente) / self.valeur_precedente

    @property
    def evolution_dj_pct(self) -> float | None:
        if not self.dj_precedent:
            return None
        return 100.0 * (self.dj - self.dj_precedent) / self.dj_precedent


def comparer_a_annee_precedente(
    ligne: Ligne, *, aujourdhui: dt.date | None = None
) -> ComparaisonGlissante | None:
    """Compare le cumul depuis le 1er janvier à celui de l'an dernier à date."""
    serie = ligne.serie
    if not serie.jours:
        return None

    aujourdhui = aujourdhui or dt.date.today()
    annee = aujourdhui.year
    jours_annee = [j for j in serie.jours if j.year == annee]
    if not jours_annee:
        return None

    borne = max(jours_annee)
    try:
        borne_precedente = borne.replace(year=annee - 1)
    except ValueError:  # 29 février
        borne_precedente = borne.replace(year=annee - 1, day=28)

    valeur = serie.entre(dt.date(annee, 1, 1), borne)
    valeur_precedente = serie.entre(dt.date(annee - 1, 1, 1), borne_precedente)
    if valeur_precedente <= 0:
        return None

    dj = sum(
        v for j, v in serie.djs.items() if dt.date(annee, 1, 1) <= j <= borne
    )
    dj_precedent = sum(
        v
        for j, v in serie.djs.items()
        if dt.date(annee - 1, 1, 1) <= j <= borne_precedente
    )

    return ComparaisonGlissante(
        jusqua=borne,
        annee=annee,
        valeur=valeur,
        annee_precedente=annee - 1,
        valeur_precedente=valeur_precedente,
        dj=dj,
        dj_precedent=dj_precedent,
    )
