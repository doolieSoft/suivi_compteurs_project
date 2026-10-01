"""Tests du calcul des consommations.

Le fil conducteur : on fabrique une consommation journalière connue, on en
déduit des relevés d'index, et on vérifie que l'application la retrouve.
"""
from __future__ import annotations

import datetime as dt
from decimal import Decimal

from django.test import TestCase

from suivi.models import Energie, Plage, Releve
from suivi.services import consommation as cs
from suivi.tests import fabrique as f

BASE_VRAIE = 0.5  # m³/jour indépendants du climat
K_VRAI = 0.7  # m³ par degré-jour


class VentilationTest(TestCase):
    """La ventilation doit conserver exactement les volumes mesurés."""

    def setUp(self):
        self.debut = dt.date(2022, 1, 1)
        self.fin = dt.date(2024, 12, 31)
        self.station = f.station()
        self.djs = f.climat(self.station, self.debut, self.fin)
        self.maison = f.maison(self.station)
        self.compteur = f.compteur(self.maison, Energie.GAZ)

        self.conso = {
            jour: BASE_VRAIE + K_VRAI * dj for jour, dj in self.djs.items()
        }
        # Relevés espacés de 40 jours : assez irréguliers pour que la
        # ventilation ait un vrai travail à faire.
        self.dates = [self.debut]
        while self.dates[-1] + dt.timedelta(days=40) <= self.fin:
            self.dates.append(self.dates[-1] + dt.timedelta(days=40))
        f.releves_depuis_consommation(self.compteur, self.conso, self.dates)

    def test_le_total_ventile_egale_la_somme_des_ecarts_d_index(self):
        serie = cs.ventiler([self.compteur], station=self.station)
        premier = Releve.objects.order_by("date").first()
        dernier = Releve.objects.order_by("-date").first()
        attendu = float(dernier.index) - float(premier.index)
        self.assertAlmostEqual(serie.total, attendu, places=4)

    def test_chaque_periode_conserve_son_volume(self):
        serie = cs.ventiler([self.compteur], station=self.station)
        for periode in cs.periodes([self.compteur]):
            ventile = sum(serie.jours[j] for j in periode.jours)
            self.assertAlmostEqual(ventile, periode.volume, places=5)

    def test_aucun_jour_hors_des_periodes_couvertes(self):
        serie = cs.ventiler([self.compteur], station=self.station)
        self.assertEqual(serie.premier_jour, self.dates[0] + dt.timedelta(days=1))
        self.assertEqual(serie.dernier_jour, self.dates[-1])

    def test_le_modele_retrouve_les_parametres_qui_ont_genere_les_donnees(self):
        serie = cs.ventiler([self.compteur], station=self.station)
        self.assertTrue(serie.modele.fiable)
        self.assertAlmostEqual(serie.modele.base, BASE_VRAIE, places=1)
        self.assertAlmostEqual(serie.modele.k, K_VRAI, places=2)
        self.assertGreater(serie.modele.r2, 0.95)

    def test_la_ventilation_thermique_restitue_la_saisonnalite(self):
        """Janvier doit recevoir bien plus qu'une répartition uniforme."""
        serie = cs.ventiler([self.compteur], station=self.station)
        janvier = serie.entre(dt.date(2023, 1, 1), dt.date(2023, 1, 31))
        juillet = serie.entre(dt.date(2023, 7, 1), dt.date(2023, 7, 31))
        self.assertGreater(janvier, 3 * juillet)

        vrai_janvier = sum(
            v
            for j, v in self.conso.items()
            if dt.date(2023, 1, 1) <= j <= dt.date(2023, 1, 31)
        )
        # Tolérance de 10 % : la ventilation lisse à l'intérieur des périodes.
        self.assertAlmostEqual(janvier / vrai_janvier, 1.0, delta=0.10)


class RemplacementDeCompteurTest(TestCase):
    """Un index qui repart de zéro ne doit jamais produire d'écart aberrant."""

    def setUp(self):
        self.station = f.station()
        f.climat(self.station, dt.date(2023, 1, 1), dt.date(2023, 12, 31))
        self.maison = f.maison(self.station)

        self.ancien = f.compteur(self.maison, Energie.EAU, libelle="")
        for rang, jour in enumerate(
            [dt.date(2023, 1, 1), dt.date(2023, 3, 1), dt.date(2023, 5, 1)]
        ):
            Releve.objects.create(
                compteur=self.ancien, date=jour, index=Decimal(800 + 20 * rang)
            )

        self.nouveau = f.compteur(self.maison, Energie.EAU, libelle="compteur 2")
        for rang, jour in enumerate(
            [dt.date(2023, 6, 1), dt.date(2023, 8, 1), dt.date(2023, 10, 1)]
        ):
            Releve.objects.create(
                compteur=self.nouveau, date=jour, index=Decimal(0 + 20 * rang)
            )

    def test_aucune_periode_ne_chevauche_deux_compteurs(self):
        for periode in cs.periodes([self.ancien, self.nouveau]):
            self.assertGreaterEqual(periode.volume, 0)
            self.assertLess(periode.volume, 100)

    def test_le_total_ignore_le_saut_d_index(self):
        serie = cs.ventiler([self.ancien, self.nouveau], station=self.station)
        # 40 m³ sur l'ancien compteur + 40 sur le nouveau, jamais -840.
        self.assertAlmostEqual(serie.total, 80.0, places=3)

    def test_la_periode_a_cheval_est_signalee_comme_lacune(self):
        serie = cs.ventiler([self.ancien, self.nouveau], station=self.station)
        self.assertEqual(len(serie.lacunes), 1)
        debut, fin = serie.lacunes[0]
        self.assertEqual(debut, dt.date(2023, 5, 2))
        self.assertEqual(fin, dt.date(2023, 6, 1))


class NormalisationTest(TestCase):
    """La correction climatique doit neutraliser la rigueur de l'année."""

    def setUp(self):
        self.station = f.station()
        self.djs = f.climat(self.station, dt.date(2018, 1, 1), dt.date(2024, 12, 31))
        self.maison = f.maison(self.station)
        self.compteur = f.compteur(self.maison, Energie.GAZ)
        conso = {j: BASE_VRAIE + K_VRAI * dj for j, dj in self.djs.items()}
        dates = [dt.date(2018, 1, 1)]
        while dates[-1] + dt.timedelta(days=30) <= dt.date(2024, 12, 31):
            dates.append(dates[-1] + dt.timedelta(days=30))
        f.releves_depuis_consommation(self.compteur, conso, dates)
        self.ligne = cs.ligne_unique(self.maison, Energie.GAZ, Plage.UNIQUE)

    def test_les_annees_normalisees_sont_quasi_identiques(self):
        """Le climat de fabrique est le même chaque année : la correction ne doit rien casser."""
        annees = [a for a in cs.comparer_annees(self.ligne) if a.complete]
        self.assertGreaterEqual(len(annees), 5)
        valeurs = [a.consommation_normalisee for a in annees]
        self.assertTrue(all(v is not None for v in valeurs))
        ecart = (max(valeurs) - min(valeurs)) / (sum(valeurs) / len(valeurs))
        self.assertLess(ecart, 0.05)

    def test_une_annee_plus_froide_voit_sa_consommation_revue_a_la_baisse(self):
        annee = next(a for a in cs.comparer_annees(self.ligne) if a.complete)
        modele = self.ligne.serie.modele
        attendu = annee.consommation + modele.k * (annee.dj_normal - annee.dj_reel)
        self.assertAlmostEqual(annee.consommation_normalisee, max(0.0, attendu), places=3)


class ThermosensibiliteTest(TestCase):
    """L'eau ne doit jamais être corrigée du climat, même si elle corrèle."""

    def setUp(self):
        self.station = f.station()
        self.djs = f.climat(self.station, dt.date(2020, 1, 1), dt.date(2024, 12, 31))
        self.maison = f.maison(self.station)
        self.compteur = f.compteur(self.maison, Energie.EAU)
        # Consommation d'eau délibérément corrélée au froid.
        conso = {j: 0.2 + 0.05 * dj for j, dj in self.djs.items()}
        dates = [dt.date(2020, 1, 1)]
        while dates[-1] + dt.timedelta(days=30) <= dt.date(2024, 12, 31):
            dates.append(dates[-1] + dt.timedelta(days=30))
        f.releves_depuis_consommation(self.compteur, conso, dates)

    def test_le_modele_thermique_n_est_pas_ajuste_sur_l_eau(self):
        ligne = cs.ligne_unique(self.maison, Energie.EAU, Plage.UNIQUE)
        self.assertFalse(ligne.serie.modele.fiable)
        self.assertEqual(ligne.serie.modele.k, 0.0)

    def test_aucune_consommation_normalisee_n_est_produite(self):
        ligne = cs.ligne_unique(self.maison, Energie.EAU, Plage.UNIQUE)
        for annee in cs.comparer_annees(ligne):
            self.assertIsNone(annee.consommation_normalisee)


class AnomaliesTest(TestCase):
    def setUp(self):
        self.station = f.station()
        self.djs = f.climat(self.station, dt.date(2023, 1, 1), dt.date(2023, 12, 31))
        self.maison = f.maison(self.station)

    def test_un_index_fige_longtemps_est_signale(self):
        compteur = f.compteur(self.maison, Energie.EAU)
        Releve.objects.create(
            compteur=compteur, date=dt.date(2023, 1, 1), index=Decimal("100")
        )
        Releve.objects.create(
            compteur=compteur, date=dt.date(2023, 6, 1), index=Decimal("100")
        )
        ligne = cs.ligne_unique(self.maison, Energie.EAU, Plage.UNIQUE)
        anomalies = cs.detecter_anomalies(ligne)
        self.assertTrue(
            any("Index inchangé" in a.message and a.niveau == "alerte" for a in anomalies)
        )

    def test_une_surconsommation_franche_est_signalee(self):
        compteur = f.compteur(self.maison, Energie.EAU)
        index, jour = 100.0, dt.date(2023, 1, 1)
        Releve.objects.create(compteur=compteur, date=jour, index=Decimal("100"))
        for semaine in range(20):
            jour += dt.timedelta(days=14)
            # Une fuite au milieu de la série : dix fois le débit ordinaire.
            index += 140.0 if semaine == 10 else 14.0
            Releve.objects.create(
                compteur=compteur, date=jour, index=Decimal(f"{index:.3f}")
            )
        ligne = cs.ligne_unique(self.maison, Energie.EAU, Plage.UNIQUE)
        anomalies = cs.detecter_anomalies(ligne)
        self.assertTrue(any("habituelle" in a.message for a in anomalies))

    def test_l_hiver_n_est_pas_pris_pour_une_anomalie(self):
        """Sur le gaz, le chauffage saisonnier ne doit jamais lever d'alerte."""
        f.climat(self.station, dt.date(2024, 1, 1), dt.date(2024, 12, 31))
        compteur = f.compteur(self.maison, Energie.GAZ)
        djs = dict(self.djs)
        djs.update(
            {
                j: v
                for j, v in cs.dj_service.dj_par_jour(
                    self.station, dt.date(2024, 1, 1), dt.date(2024, 12, 31)
                ).items()
            }
        )
        conso = {j: BASE_VRAIE + K_VRAI * dj for j, dj in djs.items()}
        dates = [dt.date(2023, 1, 1)]
        while dates[-1] + dt.timedelta(days=14) <= dt.date(2024, 12, 31):
            dates.append(dates[-1] + dt.timedelta(days=14))
        f.releves_depuis_consommation(compteur, conso, dates)

        ligne = cs.ligne_unique(self.maison, Energie.GAZ, Plage.UNIQUE)
        self.assertTrue(ligne.serie.modele.fiable)
        alertes = [a for a in cs.detecter_anomalies(ligne) if a.niveau == "alerte"]
        self.assertEqual(alertes, [])
