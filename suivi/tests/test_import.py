"""Tests de l'import Excel et des utilitaires de conversion."""
from __future__ import annotations

import datetime as dt
from decimal import Decimal

from django.core.management import call_command
from django.test import TestCase

from suivi.management.commands.import_excel import (
    Command,
    LectureBrute,
    _vers_date,
    _vers_decimal,
)
from suivi.models import Compteur, Energie, Maison, Releve, Tarif


class ConversionDateTest(TestCase):
    def test_un_millesime_devient_le_31_decembre(self):
        self.assertEqual(_vers_date(2016), dt.date(2016, 12, 31))

    def test_un_numero_de_serie_excel_est_converti(self):
        # 44562 correspond au 1er janvier 2022 dans le calendrier d'Excel.
        self.assertEqual(_vers_date(44562), dt.date(2022, 1, 1))

    def test_un_datetime_est_ramene_a_sa_date(self):
        self.assertEqual(_vers_date(dt.datetime(2023, 5, 4, 12, 30)), dt.date(2023, 5, 4))

    def test_les_valeurs_parasites_sont_rejetees(self):
        """Les feuilles contiennent des lignes de calcul qu'il faut ignorer."""
        for valeur in (None, "index envoyé", 34, 10.73, "/", 1500, 0):
            self.assertIsNone(_vers_date(valeur), f"{valeur!r} aurait dû être rejeté")


class ConversionDecimalTest(TestCase):
    def test_les_nombres_sont_arrondis_au_millieme(self):
        self.assertEqual(_vers_decimal(620.91700000000003), Decimal("620.917"))

    def test_le_texte_et_le_negatif_sont_rejetes(self):
        self.assertIsNone(_vers_decimal("/"))
        self.assertIsNone(_vers_decimal(None))
        self.assertIsNone(_vers_decimal(-5))


class DecoupageCompteurTest(TestCase):
    def test_un_recul_d_index_ouvre_une_nouvelle_serie(self):
        lectures = [
            LectureBrute(dt.date(2023, 1, 1), Decimal("800")),
            LectureBrute(dt.date(2023, 6, 1), Decimal("851")),
            LectureBrute(dt.date(2023, 12, 1), Decimal("21.5")),  # compteur remplacé
            LectureBrute(dt.date(2024, 6, 1), Decimal("60")),
        ]
        series = Command._decouper(lectures)
        self.assertEqual(len(series), 2)
        self.assertEqual(len(series[0]), 2)
        self.assertEqual(len(series[1]), 2)

    def test_une_serie_croissante_reste_entiere(self):
        lectures = [
            LectureBrute(dt.date(2023, 1, 1), Decimal("10")),
            LectureBrute(dt.date(2023, 2, 1), Decimal("20")),
            LectureBrute(dt.date(2023, 3, 1), Decimal("30")),
        ]
        self.assertEqual(len(Command._decouper(lectures)), 1)


class ImportClasseurTest(TestCase):
    """Import du classeur réel, s'il est présent à la racine du projet."""

    @classmethod
    def setUpClass(cls):
        super().setUpClass()
        from django.conf import settings

        cls.classeur = settings.BASE_DIR / "compteur.xlsx"

    def setUp(self):
        if not self.classeur.exists():
            self.skipTest("compteur.xlsx absent de la racine du projet")

    def test_l_import_cree_les_deux_maisons_et_leurs_compteurs(self):
        call_command("import_excel", str(self.classeur), verbosity=0)
        self.assertEqual(Maison.objects.count(), 2)
        liserons = Maison.objects.get(slug="liserons")
        self.assertEqual(liserons.nb_facades, 4)
        self.assertEqual(Maison.objects.get(slug="collectivite").nb_facades, 2)

        # L'ancienne maison était en bi-horaire, l'actuelle en mono.
        plages = set(
            Compteur.objects.filter(
                maison__slug="collectivite", energie=Energie.ELECTRICITE
            ).values_list("plage", flat=True)
        )
        self.assertEqual(plages, {"HAUT", "BAS"})

    def test_le_compteur_d_eau_remplace_donne_deux_compteurs(self):
        call_command("import_excel", str(self.classeur), verbosity=0)
        compteurs = Compteur.objects.filter(
            maison__slug="liserons", energie=Energie.EAU
        ).order_by("date_pose")
        self.assertEqual(compteurs.count(), 2)
        self.assertIsNotNone(compteurs[0].date_depose)
        self.assertEqual(compteurs[1].remplace_id, compteurs[0].pk)

    def test_l_import_est_rejouable_sans_doublon(self):
        call_command("import_excel", str(self.classeur), verbosity=0)
        premier = Releve.objects.count()
        call_command("import_excel", str(self.classeur), verbosity=0)
        self.assertEqual(Releve.objects.count(), premier)

    def test_le_gaz_est_en_metres_cubes_avec_son_coefficient(self):
        call_command("import_excel", str(self.classeur), verbosity=0)
        gaz = Compteur.objects.filter(energie=Energie.GAZ).first()
        self.assertEqual(gaz.unite, "m³")
        self.assertEqual(gaz.coef_kwh, Decimal("11.000"))
        self.assertEqual(gaz.vers_kwh(100), Decimal("1100.000"))

    def test_les_tarifs_sont_importes_comme_acomptes(self):
        call_command("import_excel", str(self.classeur), verbosity=0)
        self.assertTrue(Tarif.objects.exists())
        for tarif in Tarif.objects.all():
            self.assertEqual(tarif.prix_unitaire, Decimal("0"))
            self.assertGreater(tarif.abonnement_mensuel, 0)
