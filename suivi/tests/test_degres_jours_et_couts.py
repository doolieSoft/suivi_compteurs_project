"""Tests des degrés-jours et de la valorisation en euros."""
from __future__ import annotations

import datetime as dt
from decimal import Decimal
from unittest import mock

from django.test import TestCase

from suivi.models import DegreJour, Energie, Plage, Tarif
from suivi.services import consommation as cs
from suivi.services import couts as ct
from suivi.services import degres_jours as djs
from suivi.tests import fabrique as f


class CalculDegresJoursTest(TestCase):
    def setUp(self):
        self.station = f.station(base=16.5)

    def test_la_synchronisation_applique_la_formule_de_base(self):
        releves = [
            djs.ReleveMeteo(dt.date(2024, 1, 10), 2.5),  # froid  -> 14.0
            djs.ReleveMeteo(dt.date(2024, 1, 11), 16.5),  # pile la base -> 0
            djs.ReleveMeteo(dt.date(2024, 1, 12), 24.0),  # chaud -> 0, jamais négatif
            djs.ReleveMeteo(dt.date(2024, 1, 13), None),  # donnée manquante
        ]
        with mock.patch.object(djs, "recuperer_temperatures", return_value=releves):
            bilan = djs.synchroniser(
                self.station, dt.date(2024, 1, 10), dt.date(2024, 1, 13)
            )

        self.assertEqual(bilan["crees"], 3)
        self.assertEqual(bilan["ignores"], 1)
        valeurs = dict(
            DegreJour.objects.filter(station=self.station).values_list("date", "dj")
        )
        self.assertEqual(valeurs[dt.date(2024, 1, 10)], Decimal("14.000"))
        self.assertEqual(valeurs[dt.date(2024, 1, 11)], Decimal("0.000"))
        self.assertEqual(valeurs[dt.date(2024, 1, 12)], Decimal("0.000"))
        self.assertNotIn(dt.date(2024, 1, 13), valeurs)

    def test_la_synchronisation_ne_recrée_pas_les_jours_connus(self):
        releves = [djs.ReleveMeteo(dt.date(2024, 1, 10), 2.5)]
        with mock.patch.object(djs, "recuperer_temperatures", return_value=releves):
            djs.synchroniser(self.station, dt.date(2024, 1, 10), dt.date(2024, 1, 10))
            bilan = djs.synchroniser(
                self.station, dt.date(2024, 1, 10), dt.date(2024, 1, 10)
            )
        self.assertEqual(bilan["crees"], 0)
        self.assertEqual(DegreJour.objects.count(), 1)

    def test_le_cumul_annuel_et_la_normale_sont_coherents(self):
        f.climat(self.station, dt.date(2020, 1, 1), dt.date(2024, 12, 31))
        annuels = djs.dj_annuels(self.station)
        self.assertEqual(set(annuels), {2020, 2021, 2022, 2023, 2024})

        normales = djs.dj_normaux_par_jour_calendaire(
            self.station, jusqua=dt.date(2024, 12, 31)
        )
        normale = djs.dj_normal_annuel(normales)
        # Le climat de fabrique se répète : la normale doit coller aux années.
        for annee, total in annuels.items():
            if annee == 2020:  # bissextile : un jour de plus
                continue
            self.assertAlmostEqual(total / normale, 1.0, delta=0.02)

    def test_le_29_fevrier_est_rattache_au_28(self):
        f.climat(self.station, dt.date(2020, 1, 1), dt.date(2020, 12, 31))
        normales = djs.dj_normaux_par_jour_calendaire(
            self.station, jusqua=dt.date(2020, 12, 31)
        )
        self.assertNotIn((2, 29), normales)
        self.assertIn((2, 28), normales)


class CoutTest(TestCase):
    def setUp(self):
        self.station = f.station()
        f.climat(self.station, dt.date(2023, 1, 1), dt.date(2023, 12, 31))
        self.maison = f.maison(self.station)
        self.compteur = f.compteur(self.maison, Energie.EAU)
        conso = {j: 0.25 for j in f.jours(dt.date(2023, 1, 1), dt.date(2023, 12, 31))}
        dates = [dt.date(2023, 1, 1) + dt.timedelta(days=30 * i) for i in range(13)]
        dates = [d for d in dates if d <= dt.date(2023, 12, 31)]
        f.releves_depuis_consommation(self.compteur, conso, dates)
        self.ligne = cs.ligne_unique(self.maison, Energie.EAU, Plage.UNIQUE)

    def test_sans_tarif_aucun_cout_n_est_produit(self):
        self.assertEqual(ct.couts_par_annee(self.ligne), [])

    def test_le_cout_variable_suit_le_prix_unitaire(self):
        Tarif.objects.create(
            maison=self.maison,
            energie=Energie.EAU,
            date_debut=dt.date(2023, 1, 1),
            date_fin=dt.date(2023, 12, 31),
            prix_unitaire=Decimal("5.00000"),
            abonnement_mensuel=Decimal("0"),
        )
        couts = ct.couts_par_annee(self.ligne)
        self.assertEqual(len(couts), 1)
        cout = couts[0]
        self.assertAlmostEqual(
            cout.cout_variable, cout.consommation * 5.0, places=4
        )
        self.assertAlmostEqual(cout.prix_moyen, 5.0, places=6)

    def test_l_abonnement_est_reparti_au_prorata_des_jours_suivis(self):
        Tarif.objects.create(
            maison=self.maison,
            energie=Energie.EAU,
            date_debut=dt.date(2023, 1, 1),
            date_fin=dt.date(2023, 12, 31),
            prix_unitaire=Decimal("0"),
            abonnement_mensuel=Decimal("10.00"),
        )
        cout = ct.couts_par_annee(self.ligne)[0]
        # Onze mois pleins environ : jamais plus de douze mensualités.
        self.assertLessEqual(cout.cout_abonnement, 120.0)
        self.assertGreater(cout.cout_abonnement, 90.0)

    def test_un_tarif_propre_a_la_maison_prime_sur_un_tarif_generique(self):
        Tarif.objects.create(
            maison=None,
            energie=Energie.EAU,
            date_debut=dt.date(2023, 1, 1),
            prix_unitaire=Decimal("1.00000"),
        )
        Tarif.objects.create(
            maison=self.maison,
            energie=Energie.EAU,
            date_debut=dt.date(2023, 1, 1),
            prix_unitaire=Decimal("9.00000"),
        )
        cout = ct.couts_par_annee(self.ligne)[0]
        self.assertAlmostEqual(cout.prix_moyen, 9.0, places=6)
