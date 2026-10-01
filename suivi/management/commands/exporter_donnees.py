"""Exporte les données pour les transférer vers un autre serveur.

Produit un fichier JSON rechargeable par ``manage.py loaddata``. On exporte les
seules tables métier : les comptes, sessions et types de contenu sont propres à
chaque installation et les réimporter provoquerait des conflits de clés.
"""
from __future__ import annotations

import datetime as dt
from pathlib import Path

from django.core.management import call_command
from django.core.management.base import BaseCommand

from suivi.models import (
    Compteur,
    DegreJour,
    Evenement,
    Maison,
    Releve,
    StationMeteo,
    Tarif,
)

MODELES = [
    ("suivi.StationMeteo", StationMeteo),
    ("suivi.Maison", Maison),
    ("suivi.Compteur", Compteur),
    ("suivi.Releve", Releve),
    ("suivi.Evenement", Evenement),
    ("suivi.Tarif", Tarif),
    ("suivi.DegreJour", DegreJour),
]


class Command(BaseCommand):
    help = "Exporte maisons, compteurs, relevés, tarifs et degrés-jours en JSON."

    def add_arguments(self, parser):
        parser.add_argument(
            "fichier",
            nargs="?",
            default=f"donnees-{dt.date.today():%Y%m%d}.json",
            help="Fichier de sortie (par défaut : donnees-AAAAMMJJ.json).",
        )

    def handle(self, *args, **options):
        chemin = Path(options["fichier"])

        self.stdout.write("Contenu exporté :")
        for etiquette, modele in MODELES:
            self.stdout.write(f"  {etiquette.split('.')[1]:<14} {modele.objects.count():>6}")

        with open(chemin, "w", encoding="utf-8") as sortie:
            call_command(
                "dumpdata",
                *[etiquette for etiquette, _ in MODELES],
                indent=2,
                stdout=sortie,
            )

        taille = chemin.stat().st_size
        self.stdout.write(
            self.style.SUCCESS(
                f"\n{chemin} écrit ({taille / 1024:.0f} Ko)."
            )
        )
        self.stdout.write(
            "\nSur le serveur de destination, après « migrate » :\n"
            f"  python manage.py loaddata {chemin.name}\n"
            "\nLes photos de compteurs ne sont pas dans ce fichier : copiez le "
            "dossier « media » séparément si vous en avez."
        )
