"""Maison de démonstration pour les captures des stores.

Météo réelle de Liège (Open-Meteo, archive), consommations simulées :
gaz selon les degrés-jours, électricité et eau saisonnières. Un classeur
par langue, au format que l'application importe.
"""
import datetime as dt
import json
import math
import random
import sys
import urllib.request

import openpyxl

LAT, LON, BASE = 50.6326, 5.5797, 16.5
DEBUT, FIN = dt.date(2021, 1, 1), dt.date(2026, 11, 16)
DERNIERS = {"GAZ": dt.date(2026, 11, 16), "ELEC": dt.date(2026, 11, 12), "EAU": dt.date(2026, 11, 9)}
NOMS = {"fr": "Ma maison", "en": "My house", "nl": "Mijn huis", "de": "Mein Haus"}


def temperatures():
    url = ("https://archive-api.open-meteo.com/v1/archive"
           f"?latitude={LAT}&longitude={LON}&start_date={DEBUT}&end_date={FIN}"
           "&daily=temperature_2m_mean&timezone=Europe%2FBrussels")
    with urllib.request.urlopen(url, timeout=60) as r:
        d = json.load(r)["daily"]
    return {dt.date.fromisoformat(j): t for j, t in zip(d["time"], d["temperature_2m_mean"]) if t is not None}


def simuler(temp):
    alea = random.Random(2026)
    jours = sorted(temp)
    conso = {"GAZ": {}, "ELEC": {}, "EAU": {}}
    for j in jours:
        dj = max(0.0, BASE - temp[j])
        # Une isolation de la toiture fin 2023 : un peu moins par degré-jour ensuite.
        k = 0.62 if j < dt.date(2023, 10, 1) else 0.56
        conso["GAZ"][j] = max(0.0, (0.55 + k * dj) * alea.lognormvariate(0, 0.08))
        saison = math.cos(2 * math.pi * (j.timetuple().tm_yday - 15) / 365.25)
        conso["ELEC"][j] = max(0.0, (9.6 + 2.4 * saison) * alea.lognormvariate(0, 0.12))
        vacances = j.month == 8 and 5 <= j.day <= 20
        conso["EAU"][j] = 0.02 if vacances else max(0.0, 0.31 * alea.lognormvariate(0, 0.15))
    return conso


def dates_releves(energie, alea):
    """Environ une fois par mois, un relevé annuel début janvier (facture)."""
    dates, j = [], DEBUT
    pas = {"GAZ": (21, 35), "ELEC": (26, 40), "EAU": (28, 45)}[energie]
    while j < DERNIERS[energie]:
        dates.append(j)
        j += dt.timedelta(days=alea.randint(*pas))
    dates.append(DERNIERS[energie])
    for annee in range(2021, 2027):
        dates.append(dt.date(annee, 1, 5))
    return sorted(set(d for d in dates if d <= DERNIERS[energie]))


def releves(conso, depart, energie):
    alea = random.Random({"GAZ": 1, "ELEC": 2, "EAU": 3}[energie])
    liste, index, j = [], depart, DEBUT
    jours = sorted(conso)
    cumul = {}
    for jour in jours:
        if jour > DEBUT:
            index += conso[jour]
        cumul[jour] = index
    for d in dates_releves(energie, alea):
        annuel = d.month == 1 and d.day == 5
        liste.append((d, round(cumul[d], 3), annuel))
    return liste


def classeur(langue, temp, conso, chemin):
    wb = openpyxl.Workbook()
    ws = wb.active
    ws.title = "Maisons"
    ws.append(["Code", "Nom", "Ville", "Adresse", "Station météo", "Latitude", "Longitude",
               "Base des degrés-jours", "Occupée depuis", "Façades", "Surface chauffée (m²)"])
    ws.append(["M1", NOMS[langue], "Liège", "", "Liège", LAT, LON, BASE, "2020-11-01", 3, 140])

    ws = wb.create_sheet("Compteurs")
    ws.append(["Code", "Maison", "Énergie", "Plage", "Unité", "Libellé", "Posé le", "Numéro", "Coefficient kWh"])
    compteurs = [("C1", "EAU", "m³", 1.0, 312.0), ("C2", "ELEC", "kWh", 1.0, 27_850.0), ("C3", "GAZ", "m³", 11.0, 9_120.0)]
    for code, energie, unite, coef, _ in compteurs:
        ws.append([code, "M1", energie, "UNIQUE", unite, "", "2020-11-01", "", coef])

    ws = wb.create_sheet("Tarifs")
    ws.append(["Maison", "Énergie", "Début", "Fin", "Prix unitaire (€)", "Abonnement (€/mois)", "Fournisseur", "Notes"])
    tarifs = {
        "GAZ": [("2020-11-01", "2021-12-31", 0.78), ("2022-01-01", "2022-12-31", 1.62), ("2023-01-01", "2023-12-31", 1.08), ("2024-01-01", None, 0.94)],
        "ELEC": [("2020-11-01", "2021-12-31", 0.27), ("2022-01-01", "2022-12-31", 0.46), ("2023-01-01", "2023-12-31", 0.36), ("2024-01-01", None, 0.31)],
        "EAU": [("2020-11-01", "2023-12-31", 4.85), ("2024-01-01", None, 5.40)],
    }
    abonnements = {"GAZ": 8.5, "ELEC": 9.0, "EAU": 6.0}
    for energie, lignes in tarifs.items():
        for debut, fin, prix in lignes:
            ws.append(["M1", energie, debut, fin, prix, abonnements[energie], "", ""])

    ws = wb.create_sheet("Relevés")
    for code, energie, _, _, depart in compteurs:
        ws.append([code])
        ws.append(["Date", "Index", "", "", "", "", "Annuel", "Source"])
        for d, index, annuel in releves(conso[energie], depart, energie):
            ws.append([d.isoformat(), index, None, None, None, None,
                       "oui" if annuel else "non", "FOURNISSEUR" if annuel else "MANUEL"])
        ws.append([])
    wb.save(chemin)


if __name__ == "__main__":
    sortie = sys.argv[1]
    temp = temperatures()
    conso = simuler(temp)
    for langue, nom in NOMS.items():
        chemin = f"{sortie}/{nom}.xlsx"
        classeur(langue, temp, conso, chemin)
        print(chemin)
    an = {e: sum(v for j, v in c.items() if j.year == 2025) for e, c in conso.items()}
    print("2025 :", {e: round(v) for e, v in an.items()}, "jours de météo :", len(temp))
