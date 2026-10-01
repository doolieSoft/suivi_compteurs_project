"""Jeux de données synthétiques pour les tests.

On fabrique un climat déterministe (sinusoïde annuelle) plutôt que d'appeler
Open-Meteo : les tests doivent tourner hors ligne et donner le même résultat à
chaque exécution.
"""
from __future__ import annotations

import datetime as dt
import math
from decimal import Decimal

from django.utils.text import slugify

from suivi.models import (
    Compteur,
    DegreJour,
    Energie,
    Maison,
    Plage,
    Releve,
    StationMeteo,
)

UN_JOUR = dt.timedelta(days=1)


def jours(debut: dt.date, fin: dt.date):
    jour = debut
    while jour <= fin:
        yield jour
        jour += UN_JOUR


def temperature(jour: dt.date, *, moyenne: float = 10.5, amplitude: float = 8.0) -> float:
    """Température moyenne journalière : minimum mi-janvier, maximum mi-juillet."""
    rang = jour.timetuple().tm_yday
    return moyenne - amplitude * math.cos(2 * math.pi * (rang - 15) / 365.0)


def station(nom: str = "Essai", base: float = 16.5) -> StationMeteo:
    return StationMeteo.objects.create(
        nom=nom,
        latitude=Decimal("50.6"),
        longitude=Decimal("5.6"),
        base_dj=Decimal(str(base)),
    )


def climat(st: StationMeteo, debut: dt.date, fin: dt.date) -> dict[dt.date, float]:
    """Crée les degrés-jours de la période et renvoie la table {jour: dj}."""
    base = float(st.base_dj)
    table: dict[dt.date, float] = {}
    lignes = []
    for jour in jours(debut, fin):
        t = temperature(jour)
        valeur = max(0.0, base - t)
        table[jour] = valeur
        lignes.append(
            DegreJour(
                station=st,
                date=jour,
                temperature_moyenne=Decimal(f"{t:.2f}"),
                dj=Decimal(f"{valeur:.3f}"),
            )
        )
    DegreJour.objects.bulk_create(lignes, batch_size=1000)
    return table


def maison(st: StationMeteo | None = None, nom: str = "Essai") -> Maison:
    return Maison.objects.create(
        # slugify plutôt qu'un remplacement d'espaces : il écarte apostrophes et
        # accents, qu'un segment d'URL « slug » refuse. Un nom de maison comme
        # « Maison d'essai » rendait sinon toutes les URL irréversibles.
        nom=nom, slug=slugify(nom), station=st, nb_facades=4
    )


def compteur(
    m: Maison,
    energie: str = Energie.GAZ,
    *,
    plage: str = Plage.UNIQUE,
    libelle: str = "",
    unite: str = "m³",
) -> Compteur:
    return Compteur.objects.create(
        maison=m, energie=energie, plage=plage, libelle=libelle, unite=unite
    )


def releves_depuis_consommation(
    c: Compteur,
    conso_par_jour: dict[dt.date, float],
    dates_relevé: list[dt.date],
    *,
    index_initial: float = 1000.0,
) -> None:
    """Crée des relevés d'index cohérents avec une consommation journalière connue.

    C'est l'inverse de ce que fait l'application : on part de la vérité
    journalière pour produire des index, afin de vérifier ensuite que la
    ventilation la retrouve.
    """
    index = index_initial
    precedent = min(dates_relevé)
    Releve.objects.create(compteur=c, date=precedent, index=Decimal(f"{index:.3f}"))
    for jour_releve in sorted(dates_relevé)[1:]:
        cumul = sum(
            v for j, v in conso_par_jour.items() if precedent < j <= jour_releve
        )
        index += cumul
        Releve.objects.create(
            compteur=c, date=jour_releve, index=Decimal(f"{index:.3f}")
        )
        precedent = jour_releve
