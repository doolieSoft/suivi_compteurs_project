from django.contrib.auth import views as vues_auth
from django.urls import path

from . import api, views

app_name = "suivi"

urlpatterns = [
    path("", views.tableau_de_bord, name="tableau_de_bord"),
    # --- Accès ---------------------------------------------------------
    path(
        "connexion/",
        vues_auth.LoginView.as_view(
            template_name="suivi/connexion.html",
            redirect_authenticated_user=True,
        ),
        name="connexion",
    ),
    path("deconnexion/", vues_auth.LogoutView.as_view(), name="deconnexion"),
    path("comparaison/", views.comparaison, name="comparaison"),
    path("previsions/", views.previsions, name="previsions"),
    path("climat/", views.climat, name="climat"),
    path("climat/synchroniser/", views.climat_synchroniser, name="climat_synchroniser"),
    path("releves/", views.releve_liste, name="releve_liste"),
    path("releves/nouveau/", views.releve_creer, name="releve_creer"),
    path("releves/<int:pk>/modifier/", views.releve_modifier, name="releve_modifier"),
    path("releves/<int:pk>/supprimer/", views.releve_supprimer, name="releve_supprimer"),
    path("importer/", views.importer, name="importer"),
    # --- API de l'application Android ---------------------------------
    path("api/etat/", api.etat, name="api_etat"),
    path("api/releves/", api.creer_releve, name="api_creer_releve"),
    path("api/instantane/", api.instantane, name="api_instantane"),
    path("api/synchroniser/", api.synchroniser, name="api_synchroniser"),
    # --- Application web installable (PWA) ----------------------------
    path("manifest.webmanifest", views.manifeste, name="manifeste"),
    path("sw.js", views.service_worker, name="service_worker"),
    path("hors-ligne/", views.hors_ligne, name="hors_ligne"),
    path("maison/<slug:slug>/", views.maison_detail, name="maison_detail"),
    path(
        "maison/<slug:slug>/<slug:energie>/<slug:plage>/",
        views.ligne_detail,
        name="ligne_detail",
    ),
]
