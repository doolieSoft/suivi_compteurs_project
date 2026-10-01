"""Télécharge les températures et met à jour les degrés-jours.

Par défaut, la commande couvre la période utile : du premier relevé enregistré
(moins un an, pour disposer d'une base de comparaison) jusqu'à aujourd'hui.
"""
from __future__ import annotations

import datetime as dt

from django.core.management.base import BaseCommand, CommandError

from suivi.models import Releve, StationMeteo
from suivi.services import degres_jours as service


class Command(BaseCommand):
    help = "Synchronise les degrés-jours depuis Open-Meteo."

    def add_arguments(self, parser):
        parser.add_argument("--station", help="Nom de la station (toutes par défaut).")
        parser.add_argument("--debut", help="Date de début au format AAAA-MM-JJ.")
        parser.add_argument("--fin", help="Date de fin au format AAAA-MM-JJ.")
        parser.add_argument(
            "--ecraser",
            action="store_true",
            help="Recalcule les jours déjà en base (utile après un changement de base DJ).",
        )

    def handle(self, *args, **options):
        stations = StationMeteo.objects.all()
        if options["station"]:
            stations = stations.filter(nom__iexact=options["station"])
        stations = list(stations)
        if not stations:
            raise CommandError(
                "Aucune station météo. Lancez d'abord : python manage.py import_excel"
            )

        aujourdhui = dt.date.today()
        fin = self._date(options["fin"]) or aujourdhui

        for station in stations:
            debut = self._date(options["debut"]) or self._debut_utile(station)
            if debut is None:
                self.stdout.write(
                    self.style.WARNING(
                        f"{station} : aucun relevé en base, période indéterminable. "
                        "Précisez --debut."
                    )
                )
                continue

            self.stdout.write(
                f"{station} : {debut:%d/%m/%Y} → {fin:%d/%m/%Y} (base {station.base_dj} °C)…"
            )
            try:
                bilan = service.synchroniser(
                    station, debut, fin, ecraser=options["ecraser"]
                )
            except service.ErreurMeteo as exc:
                raise CommandError(str(exc)) from exc

            self.stdout.write(
                self.style.SUCCESS(
                    f"  {bilan['crees']} jour(s) créé(s), "
                    f"{bilan['mis_a_jour']} mis à jour, {bilan['ignores']} ignoré(s)."
                )
            )
            couverture = service.couverture(station)
            if couverture:
                self.stdout.write(
                    f"  Couverture : {couverture[0]:%d/%m/%Y} → {couverture[1]:%d/%m/%Y} "
                    f"({station.degres_jours.count()} jours)."
                )

    @staticmethod
    def _date(valeur: str | None) -> dt.date | None:
        if not valeur:
            return None
        try:
            return dt.date.fromisoformat(valeur)
        except ValueError as exc:
            raise CommandError(f"Date invalide : {valeur} (attendu AAAA-MM-JJ)") from exc

    @staticmethod
    def _debut_utile(station: StationMeteo) -> dt.date | None:
        premier = (
            Releve.objects.filter(compteur__maison__station=station)
            .order_by("date")
            .values_list("date", flat=True)
            .first()
        )
        if premier is None:
            return None
        return premier.replace(year=premier.year - 1)
