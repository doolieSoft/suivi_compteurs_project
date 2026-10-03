"""Écrit le jeu de référence du moteur Kotlin de l'application Android.

Le moteur de calcul est porté en Kotlin pour que l'application puisse se
passer du serveur. Cette commande fige, sur les données réelles, ce que
calcule la version Python — entrées et résultats — dans un fichier JSON que
le test ``ParitePythonTest`` relit pour exiger les mêmes chiffres.

Le fichier contient vos relevés : il est écrit par défaut dans le dossier de
compilation Android, qui n'est pas versionné.
"""
from __future__ import annotations

import datetime as dt
import json
from pathlib import Path

from django.conf import settings
from django.core.management.base import BaseCommand
from django.db.models import Q

from suivi.models import DegreJour, Maison, Tarif
from suivi.services import consommation as cs
from suivi.services import couts as ct
from suivi.services import degres_jours as djs
from suivi.services import previsions as pv

CHEMIN_DEFAUT = settings.BASE_DIR / "android" / "app" / "build" / "reference-moteur.json"


def _date(jour):
    return jour.isoformat() if jour else None


def _prevision(p: pv.Prevision | None):
    if p is None:
        return None
    return {
        "annee": p.annee,
        "realise": p.realise,
        "jours_realises": p.jours_realises,
        "estime_restant": p.estime_restant,
        "jours_restants": p.jours_restants,
        "total_prevu": p.total_prevu,
        "borne_basse": p.borne_basse,
        "borne_haute": p.borne_haute,
        "methode": p.methode,
        "reference": p.reference,
        "reference_annee": p.reference_annee,
        "reference_normalisee": p.reference_normalisee,
        "debut": _date(p.debut),
        "fin": _date(p.fin),
        "sur_releve_annuel": p.sur_releve_annuel,
    }


def _anomalie(a: cs.Anomalie) -> dict:
    if a.message.startswith("Aucun relevé"):
        genre = "lacune"
    elif a.message.startswith("Index inchangé"):
        genre = "index_fige"
    else:
        genre = "surconsommation_climat" if "climat" in a.message else "surconsommation"
    return {"genre": genre, "niveau": a.niveau, "debut": _date(a.debut), "fin": _date(a.fin)}


class Command(BaseCommand):
    help = "Fige les résultats du moteur Python pour le test de parité du moteur Kotlin."

    def add_arguments(self, parser):
        parser.add_argument("fichier", nargs="?", default=str(CHEMIN_DEFAUT))
        parser.add_argument(
            "--dates",
            nargs="*",
            default=None,
            help="Dates (AAAA-MM-JJ) auxquelles calculer prévisions et comparaisons.",
        )

    def handle(self, *args, **options):
        chemin = Path(options["fichier"])
        aujourdhui = dt.date.today()
        dates = [dt.date.fromisoformat(d) for d in options["dates"]] if options["dates"] else [
            aujourdhui,
            aujourdhui.replace(month=3, day=1),
            aujourdhui.replace(year=aujourdhui.year - 1, month=6, day=15),
            aujourdhui.replace(year=aujourdhui.year - 1, month=12, day=20),
        ]

        maisons = []
        for maison in Maison.objects.select_related("station").order_by("nom"):
            station_djs = {
                d.isoformat(): float(v)
                for d, v in DegreJour.objects.filter(station=maison.station)
                .order_by("date")
                .values_list("date", "dj")
            } if maison.station else {}

            compteurs = [
                {
                    "id": c.pk,
                    "energie": c.energie,
                    "plage": c.plage,
                    "unite": c.unite,
                    "date_pose": _date(c.date_pose),
                    "releves": [
                        {"date": _date(r.date), "index": float(r.index), "annuel": r.annuel}
                        for r in c.releves.order_by("date")
                    ],
                }
                for c in maison.compteurs.all().order_by("id")
            ]
            tarifs = [
                {
                    "energie": t.energie,
                    "debut": _date(t.date_debut),
                    "fin": _date(t.date_fin),
                    "prix": float(t.prix_unitaire),
                    "abonnement": float(t.abonnement_mensuel),
                    "propre": t.maison_id is not None,
                }
                for t in Tarif.objects.filter(Q(maison=maison) | Q(maison__isnull=True))
                .order_by("date_debut", "maison_id")
            ]

            lignes = []
            normales_par_date = {
                d: djs.dj_normaux_par_jour_calendaire(maison.station, jusqua=d) for d in dates
            }
            normales_ref = normales_par_date[dates[0]]
            for ligne in cs.lignes_de(maison):
                serie = ligne.serie
                modele = serie.modele
                lignes.append(
                    {
                        "energie": ligne.energie,
                        "plage": ligne.plage,
                        "total": serie.total,
                        "nb_jours": len(serie.jours),
                        "premier_jour": _date(serie.premier_jour),
                        "dernier_jour": _date(serie.dernier_jour),
                        "par_annee": {str(a): v for a, v in serie.par_annee().items()},
                        "lacunes": [[_date(a), _date(b)] for a, b in serie.lacunes],
                        "modele": {
                            "base": modele.base,
                            "k": modele.k,
                            "r2": modele.r2,
                            "nb_periodes": modele.nb_periodes,
                            "fiable": modele.fiable,
                            "part_chauffage_pct": modele.part_chauffage_pct,
                        },
                        "annees": [
                            {
                                "annee": a.annee,
                                "consommation": a.consommation,
                                "jours_couverts": a.jours_couverts,
                                "dj_reel": a.dj_reel,
                                "dj_normal": a.dj_normal,
                                "consommation_normalisee": a.consommation_normalisee,
                                "complete": a.complete,
                            }
                            for a in cs.comparer_annees(ligne, normales=normales_ref)
                        ],
                        "anomalies": [_anomalie(a) for a in cs.detecter_anomalies(ligne)],
                        "couts": [
                            {
                                "annee": c.annee,
                                "consommation": c.consommation,
                                "variable": c.cout_variable,
                                "abonnement": c.cout_abonnement,
                                "jours_tarifes": c.jours_tarifes,
                                "jours_couverts": c.jours_couverts,
                            }
                            for c in ct.couts_par_annee(ligne)
                        ],
                        "previsions": {
                            d.isoformat(): _prevision(
                                pv.prevoir(ligne, aujourdhui=d, normales=normales_par_date[d])
                            )
                            for d in dates
                        },
                        "comparaisons": {
                            d.isoformat(): (
                                lambda c: None
                                if c is None
                                else {
                                    "jusqua": _date(c.jusqua),
                                    "valeur": c.valeur,
                                    "valeur_precedente": c.valeur_precedente,
                                    "dj": c.dj,
                                    "dj_precedent": c.dj_precedent,
                                }
                            )(pv.comparer_a_annee_precedente(ligne, aujourdhui=d))
                            for d in dates
                        },
                    }
                )

            annees = pv.annees_rejouables([maison])
            rejeux = {}
            for annee in annees:
                rejeux[str(annee)] = [
                    {
                        "energie": r.ligne.energie,
                        "plage": r.ligne.plage,
                        "reel": r.reel,
                        "jours_reels": r.jours_reels,
                        "debut": _date(r.debut),
                        "fin": _date(r.fin),
                        "bornes": [
                            {
                                "date": _date(b.date),
                                "releve": _date(b.releve),
                                "volume_estime": b.volume_estime,
                            }
                            for _, b in r.bornes
                        ],
                        "points": [
                            {"coupe": _date(p.coupe), "prevision": _prevision(p.prevision)}
                            for p in r.points
                        ],
                    }
                    for r in pv.rejouer(maison, annee)
                ]

            maisons.append(
                {
                    "nom": maison.slug,
                    "libelle": maison.nom,
                    "djs": station_djs,
                    "compteurs": compteurs,
                    "tarifs": tarifs,
                    "dates": [d.isoformat() for d in dates],
                    "lignes": lignes,
                    "annees_rejouables": annees,
                    "rejeux": rejeux,
                }
            )

        chemin.parent.mkdir(parents=True, exist_ok=True)
        chemin.write_text(json.dumps({"maisons": maisons}), encoding="utf-8")
        # L'export Excel des mêmes données : le téléphone doit, après l'avoir
        # importé, retrouver exactement les mêmes chiffres.
        from suivi.services import export_excel

        chemin.with_name("export-reference.xlsx").write_bytes(export_excel.classeur())
        self.stdout.write(
            self.style.SUCCESS(f"{chemin} écrit ({chemin.stat().st_size / 1024:.0f} Ko).")
        )
