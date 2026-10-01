"""Récupération des températures et calcul des degrés-jours.

Les données viennent d'Open-Meteo, gratuit et sans clé d'API :

* ``archive-api.open-meteo.com`` sert l'historique (réanalyse ERA5, depuis
  1940) mais accuse environ 5 jours de retard ;
* ``api.open-meteo.com`` complète les jours récents via ``past_days``.

Un degré-jour vaut ``max(0, base - température moyenne du jour)``. Sommé sur
une période, il mesure la rigueur du climat et permet de comparer deux années
entre elles.
"""
from __future__ import annotations

import datetime as dt
from dataclasses import dataclass
from decimal import Decimal

import requests
from django.conf import settings
from django.db.models import Sum

from suivi.models import DegreJour, StationMeteo

ARCHIVE_URL = "https://archive-api.open-meteo.com/v1/archive"
FORECAST_URL = "https://api.open-meteo.com/v1/forecast"

# Retard de publication de la réanalyse ERA5.
DELAI_ARCHIVE_JOURS = 6
# Profondeur maximale d'historique acceptée par l'endpoint « forecast ».
MAX_PAST_DAYS = 92

TIMEOUT = 60


class ErreurMeteo(RuntimeError):
    """Une récupération de températures a échoué."""


@dataclass(frozen=True)
class ReleveMeteo:
    jour: dt.date
    temperature_moyenne: float | None


def _appel(url: str, params: dict) -> dict:
    try:
        reponse = requests.get(url, params=params, timeout=TIMEOUT)
        reponse.raise_for_status()
        return reponse.json()
    except requests.RequestException as exc:
        raise ErreurMeteo(f"Appel à {url} impossible : {exc}") from exc


def _extraire(charge: dict) -> list[ReleveMeteo]:
    quotidien = charge.get("daily") or {}
    jours = quotidien.get("time") or []
    temperatures = quotidien.get("temperature_2m_mean") or []
    releves: list[ReleveMeteo] = []
    for jour, temperature in zip(jours, temperatures):
        releves.append(
            ReleveMeteo(
                jour=dt.date.fromisoformat(jour),
                temperature_moyenne=None if temperature is None else float(temperature),
            )
        )
    return releves


def recuperer_temperatures(
    latitude: float, longitude: float, debut: dt.date, fin: dt.date
) -> list[ReleveMeteo]:
    """Renvoie les températures moyennes journalières entre ``debut`` et ``fin``."""
    if debut > fin:
        return []

    commun = {
        "latitude": round(float(latitude), 5),
        "longitude": round(float(longitude), 5),
        "daily": "temperature_2m_mean",
        "timezone": "Europe/Brussels",
    }
    aujourdhui = dt.date.today()
    limite_archive = aujourdhui - dt.timedelta(days=DELAI_ARCHIVE_JOURS)

    par_jour: dict[dt.date, ReleveMeteo] = {}

    fin_archive = min(fin, limite_archive)
    if debut <= fin_archive:
        charge = _appel(
            ARCHIVE_URL,
            commun
            | {"start_date": debut.isoformat(), "end_date": fin_archive.isoformat()},
        )
        for releve in _extraire(charge):
            par_jour[releve.jour] = releve

    # Jours récents non encore couverts par l'archive.
    debut_recent = max(debut, limite_archive + dt.timedelta(days=1))
    if debut_recent <= fin:
        past_days = min((aujourdhui - debut_recent).days + 1, MAX_PAST_DAYS)
        if past_days > 0:
            charge = _appel(
                FORECAST_URL,
                commun | {"past_days": past_days, "forecast_days": 1},
            )
            for releve in _extraire(charge):
                if debut <= releve.jour <= fin:
                    par_jour.setdefault(releve.jour, releve)

    return [par_jour[jour] for jour in sorted(par_jour)]


def synchroniser(
    station: StationMeteo,
    debut: dt.date,
    fin: dt.date,
    *,
    ecraser: bool = False,
) -> dict[str, int]:
    """Télécharge et enregistre les degrés-jours manquants de la station.

    Renvoie un décompte ``{"crees": n, "mis_a_jour": n, "ignores": n}``.
    """
    base = float(station.base_dj)
    existants = {
        d.date: d
        for d in DegreJour.objects.filter(
            station=station, date__gte=debut, date__lte=fin
        )
    }

    if not ecraser:
        manquants = [
            jour
            for jour in _jours(debut, fin)
            if jour not in existants or existants[jour].temperature_moyenne is None
        ]
        if not manquants:
            return {"crees": 0, "mis_a_jour": 0, "ignores": 0}
        debut, fin = min(manquants), max(manquants)

    releves = recuperer_temperatures(
        float(station.latitude), float(station.longitude), debut, fin
    )

    a_creer: list[DegreJour] = []
    a_modifier: list[DegreJour] = []
    ignores = 0

    for releve in releves:
        if releve.temperature_moyenne is None:
            ignores += 1
            continue
        valeur_dj = max(0.0, base - releve.temperature_moyenne)
        existant = existants.get(releve.jour)
        if existant is None:
            a_creer.append(
                DegreJour(
                    station=station,
                    date=releve.jour,
                    temperature_moyenne=Decimal(f"{releve.temperature_moyenne:.2f}"),
                    dj=Decimal(f"{valeur_dj:.3f}"),
                )
            )
        elif ecraser or existant.temperature_moyenne is None:
            existant.temperature_moyenne = Decimal(f"{releve.temperature_moyenne:.2f}")
            existant.dj = Decimal(f"{valeur_dj:.3f}")
            a_modifier.append(existant)
        else:
            ignores += 1

    if a_creer:
        DegreJour.objects.bulk_create(a_creer, batch_size=500)
    if a_modifier:
        DegreJour.objects.bulk_update(
            a_modifier, ["temperature_moyenne", "dj"], batch_size=500
        )

    return {
        "crees": len(a_creer),
        "mis_a_jour": len(a_modifier),
        "ignores": ignores,
    }


def _jours(debut: dt.date, fin: dt.date):
    jour = debut
    un_jour = dt.timedelta(days=1)
    while jour <= fin:
        yield jour
        jour += un_jour


# ---------------------------------------------------------------------------
# Lecture
# ---------------------------------------------------------------------------


def dj_par_jour(
    station: StationMeteo | None, debut: dt.date, fin: dt.date
) -> dict[dt.date, float]:
    """Degrés-jours journaliers, indexés par date."""
    if station is None:
        return {}
    return {
        d.date: float(d.dj)
        for d in DegreJour.objects.filter(
            station=station, date__gte=debut, date__lte=fin
        ).only("date", "dj")
    }


def cumul_dj(station: StationMeteo | None, debut: dt.date, fin: dt.date) -> float:
    """Somme des degrés-jours sur une période (bornes incluses)."""
    if station is None:
        return 0.0
    total = DegreJour.objects.filter(
        station=station, date__gte=debut, date__lte=fin
    ).aggregate(total=Sum("dj"))["total"]
    return float(total or 0)


def dj_annuels(station: StationMeteo | None) -> dict[int, float]:
    """Degrés-jours cumulés par année civile."""
    if station is None:
        return {}
    lignes = (
        DegreJour.objects.filter(station=station)
        .values_list("date__year")
        .annotate(total=Sum("dj"))
        .order_by("date__year")
    )
    return {annee: float(total) for annee, total in lignes}


def couverture(station: StationMeteo | None) -> tuple[dt.date, dt.date] | None:
    """Première et dernière date disponible pour la station."""
    if station is None:
        return None
    qs = DegreJour.objects.filter(station=station)
    premier = qs.order_by("date").values_list("date", flat=True).first()
    dernier = qs.order_by("-date").values_list("date", flat=True).first()
    if premier is None or dernier is None:
        return None
    return premier, dernier


def dj_normaux_par_jour_calendaire(
    station: StationMeteo | None,
    *,
    annees: int | None = None,
    jusqua: dt.date | None = None,
) -> dict[tuple[int, int], float]:
    """« Normale climatique » : DJ moyen pour chaque (mois, jour).

    Sert de référence pour normaliser les années entre elles et pour projeter
    la consommation des jours restants de l'année en cours.
    """
    if station is None:
        return {}
    annees = annees or getattr(settings, "ANNEES_NORMALE_CLIMATIQUE", 10)
    jusqua = jusqua or dt.date.today()
    depuis = jusqua.replace(year=jusqua.year - annees)

    cumuls: dict[tuple[int, int], list[float]] = {}
    for jour, valeur in DegreJour.objects.filter(
        station=station, date__gte=depuis, date__lte=jusqua
    ).values_list("date", "dj"):
        # Le 29 février est rattaché au 28 pour éviter un trou les années communes.
        cle = (jour.month, 28 if (jour.month, jour.day) == (2, 29) else jour.day)
        cumuls.setdefault(cle, []).append(float(valeur))

    return {cle: sum(v) / len(v) for cle, v in cumuls.items() if v}


def dj_normal_sur_periode(
    normales: dict[tuple[int, int], float], debut: dt.date, fin: dt.date
) -> float:
    """Applique la normale climatique à une plage de dates réelle."""
    if not normales or debut > fin:
        return 0.0
    total = 0.0
    for jour in _jours(debut, fin):
        cle = (jour.month, 28 if (jour.month, jour.day) == (2, 29) else jour.day)
        total += normales.get(cle, 0.0)
    return total


def dj_normal_annuel(normales: dict[tuple[int, int], float]) -> float:
    """Total annuel de la normale climatique (année de 365 jours)."""
    return sum(normales.values())
