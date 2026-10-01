"""Valorisation des consommations en euros.

Le coût d'une journée est ``consommation_du_jour × prix_unitaire_en_vigueur``,
auquel s'ajoute l'abonnement au prorata des jours du mois. Les tarifs se
succèdent dans le temps ; ceux rattachés à une maison priment sur les tarifs
génériques.
"""
from __future__ import annotations

import calendar
import datetime as dt
from dataclasses import dataclass

from django.db.models import Q

from suivi.models import Maison, Tarif
from suivi.services.consommation import Ligne


@dataclass
class CoutAnnuel:
    annee: int
    consommation: float
    cout_variable: float
    cout_abonnement: float
    jours_tarifes: int
    jours_couverts: int

    @property
    def total(self) -> float:
        return self.cout_variable + self.cout_abonnement

    @property
    def prix_moyen(self) -> float | None:
        if self.consommation <= 0:
            return None
        return self.cout_variable / self.consommation

    @property
    def complet(self) -> bool:
        """Vrai si tous les jours consommés sont couverts par un tarif."""
        return self.jours_couverts > 0 and self.jours_tarifes >= self.jours_couverts


def tarifs_applicables(maison: Maison, energie: str) -> list[Tarif]:
    """Tarifs de l'énergie pour la maison, les plus spécifiques d'abord."""
    return list(
        Tarif.objects.filter(Q(maison=maison) | Q(maison__isnull=True))
        .filter(energie=energie)
        .order_by("date_debut", "maison_id")
    )


def _tarif_du_jour(tarifs: list[Tarif], jour: dt.date, maison: Maison) -> Tarif | None:
    candidats = [t for t in tarifs if t.couvre(jour)]
    if not candidats:
        return None
    # Un tarif propre à la maison l'emporte sur un tarif générique ;
    # à spécificité égale, le plus récemment entré en vigueur gagne.
    candidats.sort(key=lambda t: (t.maison_id == maison.pk, t.date_debut))
    return candidats[-1]


def couts_par_annee(ligne: Ligne) -> list[CoutAnnuel]:
    """Coût annuel de la ligne, part variable et abonnement séparés."""
    serie = ligne.serie
    if not serie.jours:
        return []

    tarifs = tarifs_applicables(ligne.maison, ligne.energie)
    if not tarifs:
        return []

    cumuls: dict[int, dict[str, float]] = {}
    # Un abonnement mensuel est réparti sur les jours du mois effectivement suivis.
    jours_par_mois: dict[tuple[int, int], list[dt.date]] = {}

    for jour, valeur in sorted(serie.jours.items()):
        tarif = _tarif_du_jour(tarifs, jour, ligne.maison)
        cellule = cumuls.setdefault(
            jour.year,
            {
                "consommation": 0.0,
                "variable": 0.0,
                "abonnement": 0.0,
                "jours_tarifes": 0.0,
                "jours_couverts": 0.0,
            },
        )
        cellule["consommation"] += valeur
        cellule["jours_couverts"] += 1
        if tarif is None:
            continue
        cellule["jours_tarifes"] += 1
        cellule["variable"] += valeur * float(tarif.prix_unitaire)
        jours_par_mois.setdefault((jour.year, jour.month), []).append(jour)

    for (annee, mois), jours in jours_par_mois.items():
        nb_jours_mois = calendar.monthrange(annee, mois)[1]
        tarif = _tarif_du_jour(tarifs, jours[0], ligne.maison)
        if tarif is None:
            continue
        part = len(jours) / nb_jours_mois
        cumuls[annee]["abonnement"] += float(tarif.abonnement_mensuel) * part

    return [
        CoutAnnuel(
            annee=annee,
            consommation=cellule["consommation"],
            cout_variable=cellule["variable"],
            cout_abonnement=cellule["abonnement"],
            jours_tarifes=int(cellule["jours_tarifes"]),
            jours_couverts=int(cellule["jours_couverts"]),
        )
        for annee, cellule in sorted(cumuls.items())
    ]


def valoriser(ligne: Ligne, quantite: float, jour: dt.date | None = None) -> float | None:
    """Convertit une quantité en euros au tarif en vigueur à une date donnée."""
    jour = jour or dt.date.today()
    tarifs = tarifs_applicables(ligne.maison, ligne.energie)
    tarif = _tarif_du_jour(tarifs, jour, ligne.maison)
    if tarif is None:
        return None
    return quantite * float(tarif.prix_unitaire)
