"""Tests du contrôle d'accès.

L'application a d'abord vécu sur un réseau domestique, où « accessible à tous »
signifiait « à la maison ». Hébergée en ligne, elle expose douze ans de
consommation à qui connaît l'adresse. Ces tests vérifient que la fermeture est
complète — et qu'elle n'a pas fermé ce qui doit rester ouvert.
"""
from __future__ import annotations

import datetime as dt
from decimal import Decimal

from django.contrib.auth.models import User
from django.test import TestCase, override_settings
from django.urls import get_resolver, reverse

from suivi.models import Energie, Releve
from suivi.tests import fabrique as f

JETON = "jeton-de-test-pour-les-controles"


class AccesAnonymeTest(TestCase):
    """Aucune page de consultation ne doit répondre sans connexion."""

    @classmethod
    def setUpTestData(cls):
        station = f.station()
        f.climat(station, dt.date(2024, 1, 1), dt.date(2024, 12, 31))
        maison = f.maison(station, nom="Liserons")
        compteur = f.compteur(maison, Energie.GAZ)
        for rang, jour in enumerate(
            [dt.date(2024, 1, 1), dt.date(2024, 6, 1), dt.date(2024, 12, 1)]
        ):
            Releve.objects.create(
                compteur=compteur, date=jour, index=Decimal(1000 + 100 * rang)
            )
        cls.maison = maison

    def test_les_pages_de_consultation_renvoient_vers_la_connexion(self):
        pages = [
            reverse("suivi:tableau_de_bord"),
            reverse("suivi:comparaison"),
            reverse("suivi:previsions"),
            reverse("suivi:climat"),
            reverse("suivi:releve_liste"),
            reverse("suivi:releve_creer"),
            reverse("suivi:importer"),
            reverse("suivi:maison_detail", args=[self.maison.slug]),
            reverse("suivi:ligne_detail", args=[self.maison.slug, "gaz", "total"]),
        ]
        for page in pages:
            with self.subTest(page=page):
                reponse = self.client.get(page)
                self.assertEqual(reponse.status_code, 302, f"{page} répond sans connexion")
                self.assertIn(reverse("suivi:connexion"), reponse["Location"])

    def test_les_ecritures_sont_fermees_aussi(self):
        """Une redirection sur un POST vaut refus : rien n'est écrit."""
        avant = Releve.objects.count()
        reponse = self.client.post(
            reverse("suivi:releve_creer"),
            {"compteur": 1, "date": "2024-12-02", "index": "1300"},
        )
        self.assertEqual(reponse.status_code, 302)
        self.assertEqual(Releve.objects.count(), avant)

    def test_l_administration_reste_protegee(self):
        reponse = self.client.get("/admin/")
        self.assertIn(reponse.status_code, (302, 301))

    def test_toutes_les_vues_du_site_sont_couvertes(self):
        """Garde-fou contre l'oubli : aucune vue ne doit rester ouverte par mégarde.

        Si une page est ajoutée plus tard sans réflexion sur son accès, ce test
        la signale — c'est tout l'intérêt d'interdire par défaut.
        """
        exemptees = {
            "suivi:connexion",
            "suivi:deconnexion",
            "suivi:manifeste",
            "suivi:service_worker",
            "suivi:hors_ligne",
            "suivi:api_etat",
            "suivi:api_creer_releve",
            "suivi:api_instantane",
            "suivi:api_synchroniser",
        }
        resolveur = get_resolver()
        noms = {
            f"suivi:{nom}"
            for nom in resolveur.reverse_dict.keys()
            if isinstance(nom, str)
        }
        # On ne vérifie que les routes nommées de l'application.
        from suivi import urls as urls_suivi

        declarees = {f"suivi:{m.name}" for m in urls_suivi.urlpatterns if m.name}
        self.assertTrue(declarees)
        inattendues = exemptees - declarees
        self.assertEqual(
            inattendues,
            set(),
            f"Exemptions déclarées pour des routes inexistantes : {inattendues}",
        )


class AccesConnecteTest(TestCase):
    @classmethod
    def setUpTestData(cls):
        User.objects.create_user("stefano", password="mot-de-passe-de-test-long")

    def test_la_connexion_ouvre_le_tableau_de_bord(self):
        reussi = self.client.login(
            username="stefano", password="mot-de-passe-de-test-long"
        )
        self.assertTrue(reussi)
        reponse = self.client.get(reverse("suivi:tableau_de_bord"))
        self.assertEqual(reponse.status_code, 200)

    def test_la_page_de_connexion_est_atteignable_sans_connexion(self):
        """Évidence qu'il faut vérifier : une page de connexion protégée
        par la connexion rendrait le site inaccessible à tout jamais."""
        reponse = self.client.get(reverse("suivi:connexion"))
        self.assertEqual(reponse.status_code, 200)

    def test_un_mot_de_passe_errone_est_refuse(self):
        reponse = self.client.post(
            reverse("suivi:connexion"),
            {"username": "stefano", "password": "mauvais"},
        )
        self.assertEqual(reponse.status_code, 200)  # réaffiche le formulaire
        self.assertFalse(reponse.wsgi_request.user.is_authenticated)


@override_settings(JETON_API=JETON)
class AccesApiTest(TestCase):
    """Le téléphone s'identifie par jeton, pas par session : la fermeture du
    site ne doit pas l'avoir enfermé dehors."""

    @classmethod
    def setUpTestData(cls):
        station = f.station()
        f.climat(station, dt.date(2024, 1, 1), dt.date(2024, 12, 31))
        maison = f.maison(station, nom="Liserons")
        compteur = f.compteur(maison, Energie.GAZ)
        Releve.objects.create(
            compteur=compteur, date=dt.date(2024, 1, 1), index=Decimal("1000")
        )
        Releve.objects.create(
            compteur=compteur, date=dt.date(2024, 12, 1), index=Decimal("1200")
        )
        cls.compteur = compteur

    def test_l_api_repond_avec_le_jeton_et_sans_session(self):
        for nom in ("suivi:api_etat", "suivi:api_instantane"):
            with self.subTest(nom=nom):
                reponse = self.client.get(reverse(nom), headers={"x-jeton": JETON})
                self.assertEqual(reponse.status_code, 200)

    def test_l_api_refuse_sans_jeton(self):
        for nom in ("suivi:api_etat", "suivi:api_instantane"):
            with self.subTest(nom=nom):
                reponse = self.client.get(reverse(nom))
                self.assertEqual(reponse.status_code, 401)

    def test_l_envoi_d_un_releve_fonctionne_sans_session(self):
        reponse = self.client.post(
            reverse("suivi:api_creer_releve"),
            data={
                "compteur": self.compteur.pk,
                "date": "2024-12-15",
                "index": "1250",
            },
            headers={"x-jeton": JETON},
        )
        self.assertEqual(reponse.status_code, 201, reponse.content)
        self.assertTrue(
            Releve.objects.filter(date=dt.date(2024, 12, 15)).exists()
        )

    def test_l_envoi_d_un_releve_est_refuse_sans_jeton(self):
        reponse = self.client.post(
            reverse("suivi:api_creer_releve"),
            data={"compteur": self.compteur.pk, "date": "2024-12-16", "index": "1260"},
        )
        self.assertEqual(reponse.status_code, 401)
        self.assertFalse(Releve.objects.filter(date=dt.date(2024, 12, 16)).exists())


class ApplicationInstallableTest(TestCase):
    """Le manifeste et le service worker sont lus par le navigateur avant toute
    connexion ; les protéger empêcherait l'installation sur le téléphone."""

    def test_le_manifeste_et_le_service_worker_restent_accessibles(self):
        for nom in ("suivi:manifeste", "suivi:service_worker", "suivi:hors_ligne"):
            with self.subTest(nom=nom):
                reponse = self.client.get(reverse(nom))
                self.assertEqual(reponse.status_code, 200)

    def test_ils_ne_divulguent_aucune_consommation(self):
        """Ouverts, donc ils ne doivent rien révéler des données."""
        for nom in ("suivi:manifeste", "suivi:service_worker"):
            contenu = self.client.get(reverse(nom)).content.decode()
            for indice in ("m³", "kWh", "Liserons", "relevé"):
                self.assertNotIn(indice, contenu, f"{nom} divulgue « {indice} »")
