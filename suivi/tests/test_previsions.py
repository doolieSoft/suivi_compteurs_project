"""Tests des projections de fin d'année."""
from __future__ import annotations

import datetime as dt

from django.test import TestCase

from suivi.models import Energie, Plage
from suivi.services import consommation as cs
from suivi.services import previsions as pv
from suivi.tests import fabrique as f

BASE_VRAIE = 0.5
K_VRAI = 0.7


class PrevisionGazTest(TestCase):
    def setUp(self):
        self.station = f.station()
        self.djs = f.climat(self.station, dt.date(2019, 1, 1), dt.date(2024, 12, 31))
        self.maison = f.maison(self.station)
        self.compteur = f.compteur(self.maison, Energie.GAZ)
        conso = {j: BASE_VRAIE + K_VRAI * dj for j, dj in self.djs.items()}
        dates = [dt.date(2019, 1, 1)]
        while dates[-1] + dt.timedelta(days=30) <= dt.date(2024, 12, 31):
            dates.append(dates[-1] + dt.timedelta(days=30))
        f.releves_depuis_consommation(self.compteur, conso, dates)
        self.ligne = cs.ligne_unique(self.maison, Energie.GAZ, Plage.UNIQUE)

    def test_le_realise_n_est_pas_reecrit(self):
        prevision = pv.prevoir(self.ligne, annee=2024, aujourdhui=dt.date(2024, 6, 30))
        reel = self.ligne.serie.entre(dt.date(2024, 1, 1), dt.date(2024, 12, 31))
        # Seuls les jours postérieurs au dernier relevé sont estimés.
        couverts = [j for j in self.ligne.serie.jours if j.year == 2024]
        attendu = sum(self.ligne.serie.jours[j] for j in couverts)
        self.assertAlmostEqual(prevision.realise, attendu, places=4)
        self.assertLessEqual(prevision.realise, reel + 1e-6)

    def test_une_annee_entierement_couverte_n_a_rien_a_estimer(self):
        prevision = pv.prevoir(self.ligne, annee=2023)
        self.assertEqual(prevision.jours_restants, 0)
        self.assertEqual(prevision.estime_restant, 0.0)
        self.assertAlmostEqual(prevision.total_prevu, prevision.realise, places=6)

    def test_la_projection_de_mi_annee_approche_le_total_reel(self):
        """Le climat de fabrique se répète : la prévision doit tomber juste."""
        # On tronque la série au 30 juin 2024 pour simuler une année en cours.
        from suivi.models import Releve

        Releve.objects.filter(
            compteur=self.compteur, date__gt=dt.date(2024, 6, 30)
        ).delete()
        ligne = cs.ligne_unique(self.maison, Energie.GAZ, Plage.UNIQUE)
        prevision = pv.prevoir(ligne, annee=2024, aujourdhui=dt.date(2024, 6, 30))

        total_reel = sum(
            BASE_VRAIE + K_VRAI * dj
            for jour, dj in self.djs.items()
            if jour.year == 2024
        )
        self.assertAlmostEqual(prevision.total_prevu / total_reel, 1.0, delta=0.08)
        self.assertIn("thermique", prevision.methode)

    def test_la_fourchette_encadre_la_prevision(self):
        from suivi.models import Releve

        Releve.objects.filter(
            compteur=self.compteur, date__gt=dt.date(2024, 6, 30)
        ).delete()
        ligne = cs.ligne_unique(self.maison, Energie.GAZ, Plage.UNIQUE)
        prevision = pv.prevoir(ligne, annee=2024, aujourdhui=dt.date(2024, 6, 30))
        self.assertLessEqual(prevision.borne_basse, prevision.total_prevu)
        self.assertGreaterEqual(prevision.borne_haute, prevision.total_prevu)


class PrevisionSansModeleTest(TestCase):
    """Sans lien avec la météo, la projection s'appuie sur le profil saisonnier."""

    def setUp(self):
        self.station = f.station()
        f.climat(self.station, dt.date(2020, 1, 1), dt.date(2024, 12, 31))
        self.maison = f.maison(self.station)
        self.compteur = f.compteur(self.maison, Energie.EAU, unite="m³")
        # Consommation strictement constante : 0,2 m³/jour.
        conso = {
            jour: 0.2
            for jour in f.jours(dt.date(2020, 1, 1), dt.date(2024, 6, 30))
        }
        dates = [dt.date(2020, 1, 1)]
        while dates[-1] + dt.timedelta(days=30) <= dt.date(2024, 6, 30):
            dates.append(dates[-1] + dt.timedelta(days=30))
        f.releves_depuis_consommation(self.compteur, conso, dates)
        self.ligne = cs.ligne_unique(self.maison, Energie.EAU, Plage.UNIQUE)

    def test_la_projection_retrouve_le_total_annuel(self):
        prevision = pv.prevoir(self.ligne, annee=2024, aujourdhui=dt.date(2024, 6, 30))
        self.assertAlmostEqual(prevision.total_prevu, 0.2 * 366, delta=0.2 * 366 * 0.05)
        self.assertIn("profil saisonnier", prevision.methode)


class RejeuTest(TestCase):
    """Rejouer une année ne doit rien laisser filtrer de ce qui a suivi."""

    def setUp(self):
        self.station = f.station()
        self.djs = f.climat(self.station, dt.date(2019, 1, 1), dt.date(2024, 12, 31))
        self.maison = f.maison(self.station)
        self.compteur = f.compteur(self.maison, Energie.GAZ)
        conso = {j: BASE_VRAIE + K_VRAI * dj for j, dj in self.djs.items()}
        dates = [dt.date(2019, 1, 1)]
        while dates[-1] + dt.timedelta(days=30) <= dt.date(2024, 12, 31):
            dates.append(dates[-1] + dt.timedelta(days=30))
        f.releves_depuis_consommation(self.compteur, conso, dates)
        self.dates_2023 = [d for d in dates if d.year == 2023]

    def test_chaque_point_ignore_les_releves_posterieurs(self):
        from suivi.models import Releve

        (rejeu,) = pv.rejouer(self.maison, 2023)
        point = next(p for p in rejeu.points if p.coupe == self.dates_2023[5])

        # Référence : la même prévision, une fois l'avenir réellement effacé.
        Releve.objects.filter(compteur=self.compteur, date__gt=point.coupe).delete()
        ligne = cs.ligne_unique(self.maison, Energie.GAZ, Plage.UNIQUE)
        attendue = pv.prevoir(
            ligne,
            annee=2023,
            aujourdhui=point.coupe,
            normales=pv.dj_service.dj_normaux_par_jour_calendaire(
                self.station, jusqua=point.coupe
            ),
        )
        self.assertAlmostEqual(point.prevision.realise, attendue.realise, places=6)
        self.assertAlmostEqual(
            point.prevision.total_prevu, attendue.total_prevu, places=6
        )

    def test_le_reel_vient_de_l_historique_complet(self):
        (rejeu,) = pv.rejouer(self.maison, 2023)
        reel = sum(
            BASE_VRAIE + K_VRAI * dj for j, dj in self.djs.items() if j.year == 2023
        )
        self.assertAlmostEqual(rejeu.reel / reel, 1.0, delta=0.01)
        # Le climat de fabrique se répète : les prévisions rejouées tombent juste.
        self.assertLess(rejeu.ecart_moyen_pct, 8.0)

    def test_une_annee_sans_total_reel_n_est_pas_proposee(self):
        self.assertNotIn(2025, pv.annees_rejouables([self.maison]))
        self.assertIn(2023, pv.annees_rejouables([self.maison]))
        self.assertEqual(pv.rejouer(self.maison, 2025), [])

    def test_la_page_s_affiche(self):
        from django.contrib.auth.models import User
        from django.urls import reverse

        self.client.force_login(User.objects.create_user("essai"))
        reponse = self.client.get(reverse("suivi:previsions_rejeu"), {"annee": "2023"})
        self.assertEqual(reponse.status_code, 200)
        self.assertContains(reponse, "Justesse des prévisions 2023")


class ComparaisonGlissanteTest(TestCase):
    def setUp(self):
        self.station = f.station()
        self.djs = f.climat(self.station, dt.date(2022, 1, 1), dt.date(2024, 12, 31))
        self.maison = f.maison(self.station)
        self.compteur = f.compteur(self.maison, Energie.GAZ)
        conso = {j: BASE_VRAIE + K_VRAI * dj for j, dj in self.djs.items()}
        dates = [dt.date(2022, 1, 1)]
        while dates[-1] + dt.timedelta(days=15) <= dt.date(2024, 12, 31):
            dates.append(dates[-1] + dt.timedelta(days=15))
        f.releves_depuis_consommation(self.compteur, conso, dates)
        self.ligne = cs.ligne_unique(self.maison, Energie.GAZ, Plage.UNIQUE)

    def test_les_deux_cumuls_portent_sur_la_meme_portion_d_annee(self):
        comparaison = pv.comparer_a_annee_precedente(
            self.ligne, aujourdhui=dt.date(2024, 7, 1)
        )
        self.assertIsNotNone(comparaison)
        self.assertEqual(comparaison.annee, 2024)
        self.assertEqual(comparaison.annee_precedente, 2023)
        # Climat identique d'une année à l'autre : l'écart doit être minime.
        self.assertLess(abs(comparaison.evolution_pct), 5.0)
