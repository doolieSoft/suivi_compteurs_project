"""Vues du suivi de compteurs.

Chaque vue prépare deux choses : des objets d'analyse pour le corps de la page,
et des structures JSON sérialisées via ``json_script`` pour les graphiques.
"""
from __future__ import annotations

import calendar
import datetime as dt
import hashlib
import tempfile
from pathlib import Path

from django.contrib import messages
from django.contrib.auth.decorators import login_not_required
from django.contrib.staticfiles.finders import find
from django.core.management import call_command
from django.db.models import Count, Max, Min
from django.http import Http404, JsonResponse
from django.shortcuts import get_object_or_404, redirect, render
from django.templatetags.static import static
from django.urls import reverse
from django.views.decorators.http import require_http_methods

from .forms import ImportExcelForm, ReleveForm
from .models import Compteur, DegreJour, Energie, Maison, Releve, StationMeteo
from .services import consommation as cs
from .services import couts as ct
from .services import degres_jours as djs
from .services import previsions as pv

MOIS_COURTS = [
    "janv.", "févr.", "mars", "avr.", "mai", "juin",
    "juil.", "août", "sept.", "oct.", "nov.", "déc.",
]


# ---------------------------------------------------------------------------
# Utilitaires
# ---------------------------------------------------------------------------


def _resoudre_ligne(slug: str, slug_energie: str, slug_plage: str) -> cs.Ligne:
    maison = get_object_or_404(Maison, slug=slug)
    energie = cs.ENERGIES_PAR_SLUG.get(slug_energie)
    plage = cs.PLAGES_PAR_SLUG.get(slug_plage)
    if energie is None or plage is None:
        raise Http404("Énergie ou plage tarifaire inconnue.")
    ligne = cs.ligne_unique(maison, energie, plage)
    if ligne is None:
        raise Http404("Aucun compteur pour cette énergie.")
    return ligne


def _resume(ligne: cs.Ligne, normales: dict) -> dict:
    """Bloc de synthèse réutilisé par le tableau de bord et les pages maison."""
    prevision = pv.prevoir(ligne, normales=normales)
    return {
        "ligne": ligne,
        "prevision": prevision,
        "comparaison": pv.comparer_a_annee_precedente(ligne),
        "annees": cs.comparer_annees(ligne, normales=normales),
        "anomalies": cs.detecter_anomalies(ligne),
    }


def _serie_annuelle_pour_graphique(annees: list[cs.AnneeComparee]) -> dict:
    """Barres groupées : consommation mesurée et consommation corrigée du climat."""
    retenues = [a for a in annees if a.jours_couverts >= 30]
    return {
        "labels": [str(a.annee) for a in retenues],
        "brut": [round(a.consommation, 1) for a in retenues],
        "normalise": [
            round(a.consommation_normalisee, 1)
            if a.consommation_normalisee is not None
            else None
            for a in retenues
        ],
        "complete": [a.complete for a in retenues],
        "dj": [round(a.dj_reel) for a in retenues],
    }


def _climat_annuel(station) -> dict:
    """Degrés-jours par année, années tronquées mises à part.

    Le premier et le dernier millésime de l'historique ne comptent souvent que
    quelques jours : les afficher comme des années fausserait la lecture.
    """
    vide = {"annuels": [], "normale": 0.0, "graphique": {"labels": [], "valeurs": [], "normale": 0}}
    if station is None:
        return vide

    annuels = djs.dj_annuels(station)
    normales = djs.dj_normaux_par_jour_calendaire(station)
    normale = djs.dj_normal_annuel(normales)
    annee_courante = dt.date.today().year

    jours_par_annee = dict(
        DegreJour.objects.filter(station=station)
        .values_list("date__year")
        .annotate(n=Count("id"))
    )
    completes = {
        annee: total
        for annee, total in annuels.items()
        if jours_par_annee.get(annee, 0) >= 360
    }
    # L'année en cours figure au tableau malgré sa couverture partielle, mais
    # reste hors du graphique : une barre tronquée se compare mal aux autres.
    affichees = {
        annee: total
        for annee, total in annuels.items()
        if annee in completes or annee == annee_courante
    }

    return {
        "normale": normale,
        "annuels": [
            {
                "annee": annee,
                "dj": total,
                "jours": jours_par_annee.get(annee, 0),
                "ecart": (
                    (100 * (total - normale) / normale)
                    if normale and annee in completes
                    else None
                ),
                "complet": annee in completes,
            }
            for annee, total in affichees.items()
        ],
        "graphique": {
            "labels": [str(a) for a in completes],
            "valeurs": [round(v) for v in completes.values()],
            "normale": round(normale),
        },
    }


def _graphique_annuel(ligne: cs.Ligne, annees: list[cs.AnneeComparee]) -> dict:
    """Enveloppe prête pour le gabarit : identifiant, titre, unité et données."""
    return {
        "cle": ligne.cle,
        "id_canvas": f"g-{ligne.cle}",
        "id_donnees": f"d-{ligne.cle}",
        "titre": ligne.libelle_energie,
        "unite": ligne.unite,
        "url": ligne.get_absolute_url(),
        "classe": {
            Energie.EAU: "eau",
            Energie.GAZ: "gaz",
            Energie.ELECTRICITE: "elec",
            Energie.MAZOUT: "mazout",
        }.get(ligne.energie, ""),
        "donnees": _serie_annuelle_pour_graphique(annees),
    }


# ---------------------------------------------------------------------------
# Tableau de bord
# ---------------------------------------------------------------------------


def tableau_de_bord(request):
    maisons = list(Maison.objects.select_related("station").all())
    if not maisons:
        return render(request, "suivi/vide.html")

    blocs = []
    for maison in maisons:
        normales = djs.dj_normaux_par_jour_calendaire(maison.station)
        resumes = [
            _resume(ligne, normales)
            for ligne in cs.lignes_de(maison)
            if ligne.serie.jours
        ]
        if resumes:
            blocs.append({"maison": maison, "resumes": resumes})

    maison_actuelle = next((m for m in maisons if m.est_actuelle), maisons[0])
    graphiques = [
        _graphique_annuel(r["ligne"], r["annees"])
        for bloc in blocs
        if bloc["maison"] == maison_actuelle
        for r in bloc["resumes"]
    ]

    return render(
        request,
        "suivi/tableau_de_bord.html",
        {
            "rubrique": "accueil",
            "blocs": blocs,
            "maison_actuelle": maison_actuelle,
            "graphiques": graphiques,
            "annee": dt.date.today().year,
            "aujourdhui": dt.date.today(),
        },
    )


# ---------------------------------------------------------------------------
# Maison
# ---------------------------------------------------------------------------


def maison_detail(request, slug):
    maison = get_object_or_404(Maison.objects.select_related("station"), slug=slug)
    normales = djs.dj_normaux_par_jour_calendaire(maison.station)
    resumes = [
        _resume(ligne, normales)
        for ligne in cs.lignes_de(maison)
        if ligne.serie.jours
    ]
    graphiques = [_graphique_annuel(r["ligne"], r["annees"]) for r in resumes]
    return render(
        request,
        "suivi/maison_detail.html",
        {
            "rubrique": "accueil",
            "maison": maison,
            "resumes": resumes,
            "graphiques": graphiques,
            "evenements": maison.evenements.all(),
        },
    )


# ---------------------------------------------------------------------------
# Détail d'une ligne (énergie d'une maison)
# ---------------------------------------------------------------------------


def ligne_detail(request, slug, energie, plage):
    ligne = _resoudre_ligne(slug, energie, plage)
    maison = ligne.maison
    normales = djs.dj_normaux_par_jour_calendaire(maison.station)
    serie = ligne.serie

    annees = cs.comparer_annees(ligne, normales=normales)
    prevision = pv.prevoir(ligne, normales=normales)
    comparaison = pv.comparer_a_annee_precedente(ligne)
    anomalies = cs.detecter_anomalies(ligne)

    # --- Cumul depuis le 1er janvier, une courbe par année --------------
    annees_tracees = [a.annee for a in annees if a.jours_couverts >= 30][-6:]
    cumuls = {
        "annees": annees_tracees,
        "series": {
            str(annee): [
                {"j": jour.timetuple().tm_yday, "v": round(valeur, 2)}
                for jour, valeur in serie.cumul_annuel(annee)
            ]
            for annee in annees_tracees
        },
    }

    # --- Consommation mensuelle -----------------------------------------
    par_mois = serie.par_mois()
    mensuel = {
        "labels": [f"{MOIS_COURTS[m - 1]} {a}" for (a, m) in par_mois],
        "valeurs": [round(v, 2) for v in par_mois.values()],
        "annees": [a for (a, _) in par_mois],
    }

    # --- Signature énergétique : conso/jour en fonction des DJ/jour ------
    signature = None
    if ligne.thermosensible:
        points = []
        for periode in cs.periodes(ligne.compteurs):
            jours = list(periode.jours)
            if periode.volume <= 0 or not all(j in serie.djs for j in jours):
                continue
            points.append(
                {
                    "x": round(sum(serie.djs[j] for j in jours) / periode.nb_jours, 2),
                    "y": round(periode.volume_journalier, 3),
                    "d": f"{periode.debut:%d/%m/%Y} → {periode.fin:%d/%m/%Y}",
                    "n": periode.nb_jours,
                }
            )
        if points:
            modele = serie.modele
            x_min = min(p["x"] for p in points)
            x_max = max(p["x"] for p in points)
            signature = {
                "points": points,
                "droite": (
                    [
                        {"x": x_min, "y": round(modele.attendu(x_min), 3)},
                        {"x": x_max, "y": round(modele.attendu(x_max), 3)},
                    ]
                    if modele.fiable
                    else []
                ),
            }

    # --- Index bruts ------------------------------------------------------
    index = [
        {
            "compteur": str(compteur),
            "points": [
                {"t": r.date.isoformat(), "v": float(r.index)}
                for r in compteur.releves.order_by("date")
            ],
        }
        for compteur in ligne.compteurs
    ]

    return render(
        request,
        "suivi/ligne_detail.html",
        {
            "rubrique": "accueil",
            "ligne": ligne,
            "maison": maison,
            "serie": serie,
            "modele": serie.modele,
            "annees": annees,
            "prevision": prevision,
            "comparaison": comparaison,
            "anomalies": anomalies,
            "graphique_annuel": _serie_annuelle_pour_graphique(annees),
            "cumuls": cumuls,
            "mensuel": mensuel,
            "signature": signature,
            "index": index,
            "couts": ct.couts_par_annee(ligne),
            "evenements": maison.evenements.filter(energie__in=[ligne.energie, ""]),
            "autres_lignes": [
                autre
                for autre in cs.lignes_de(maison)
                if autre.cle != ligne.cle and autre.serie.jours
            ],
        },
    )


# ---------------------------------------------------------------------------
# Comparaison inter-années
# ---------------------------------------------------------------------------


def comparaison(request):
    maisons = list(Maison.objects.select_related("station").all())
    tableaux = []
    for maison in maisons:
        normales = djs.dj_normaux_par_jour_calendaire(maison.station)
        for ligne in cs.lignes_de(maison):
            if not ligne.serie.jours:
                continue
            annees = cs.comparer_annees(ligne, normales=normales)
            tableaux.append(
                {
                    "ligne": ligne,
                    "annees": annees,
                    "graphique": _graphique_annuel(ligne, annees),
                    "modele": ligne.serie.modele,
                }
            )

    station = maisons[0].station if maisons else None
    climat = _climat_annuel(station)["graphique"]

    return render(
        request,
        "suivi/comparaison.html",
        {
            "rubrique": "comparaison",
            "tableaux": tableaux,
            "climat": climat,
            "station": station,
        },
    )


# ---------------------------------------------------------------------------
# Prévisions
# ---------------------------------------------------------------------------


def previsions(request):
    lignes = []
    for maison in Maison.objects.select_related("station").all():
        normales = djs.dj_normaux_par_jour_calendaire(maison.station)
        for ligne in cs.lignes_de(maison):
            if not ligne.serie.jours:
                continue
            prevision = pv.prevoir(ligne, normales=normales)
            if prevision is None:
                continue
            lignes.append(
                {
                    "ligne": ligne,
                    "prevision": prevision,
                    "cout": ct.valoriser(ligne, prevision.total_prevu),
                }
            )
    return render(
        request,
        "suivi/previsions.html",
        {
            "rubrique": "previsions",
            "lignes": lignes,
            "annee": dt.date.today().year,
            "aujourdhui": dt.date.today(),
        },
    )


def previsions_rejeu(request):
    """Prévisions d'une année passée, rejouées puis confrontées au total réel."""
    maisons = list(Maison.objects.select_related("station").all())
    annees = pv.annees_rejouables(maisons)
    try:
        annee = int(request.GET.get("annee", ""))
    except ValueError:
        annee = None
    if annee not in annees:
        annee = annees[0] if annees else None

    rejeux = []
    if annee is not None:
        for maison in maisons:
            rejeux.extend(pv.rejouer(maison, annee))

    return render(
        request,
        "suivi/previsions_rejeu.html",
        {
            "rubrique": "previsions",
            "annee": annee,
            "annees": annees,
            "rejeux": rejeux,
        },
    )


# ---------------------------------------------------------------------------
# Climat
# ---------------------------------------------------------------------------


def climat(request):
    stations = StationMeteo.objects.annotate(
        jours=Count("degres_jours"),
        premier=Min("degres_jours__date"),
        dernier=Max("degres_jours__date"),
    )
    donnees = []
    for station in stations:
        donnees.append(
            _climat_annuel(station)
            | {
                "station": station,
                "id_canvas": f"g-station-{station.pk}",
                "id_donnees": f"d-station-{station.pk}",
            }
        )
    return render(
        request, "suivi/climat.html", {"rubrique": "climat", "donnees": donnees}
    )


@require_http_methods(["POST"])
def climat_synchroniser(request):
    try:
        call_command("sync_degres_jours")
    except Exception as exc:  # noqa: BLE001 — remonté tel quel à l'utilisateur
        messages.error(request, f"Synchronisation impossible : {exc}")
    else:
        messages.success(request, "Degrés-jours mis à jour depuis Open-Meteo.")
    return redirect("suivi:climat")


# ---------------------------------------------------------------------------
# Relevés
# ---------------------------------------------------------------------------


def releve_liste(request):
    releves = Releve.objects.select_related("compteur", "compteur__maison").order_by(
        "-date", "compteur"
    )
    maison = request.GET.get("maison")
    energie = request.GET.get("energie")
    if maison:
        releves = releves.filter(compteur__maison__slug=maison)
    if energie in cs.ENERGIES_PAR_SLUG:
        releves = releves.filter(compteur__energie=cs.ENERGIES_PAR_SLUG[energie])

    return render(
        request,
        "suivi/releve_liste.html",
        {
            "rubrique": "releves",
            "releves": releves[:300],
            "total": releves.count(),
            "maisons": Maison.objects.all(),
            "energies": Energie.choices,
            "filtre_maison": maison or "",
            "filtre_energie": energie or "",
            "slugs_energie": cs.SLUGS_ENERGIE,
        },
    )


def releve_creer(request):
    initial = {"date": dt.date.today()}
    if compteur_id := request.GET.get("compteur"):
        initial["compteur"] = compteur_id
    form = ReleveForm(request.POST or None, initial=initial)
    if request.method == "POST" and form.is_valid():
        releve = form.save()
        messages.success(
            request,
            f"Relevé du {releve.date:%d/%m/%Y} enregistré pour {releve.compteur}.",
        )
        return redirect("suivi:releve_creer")
    return render(
        request,
        "suivi/releve_form.html",
        {"rubrique": "saisie", "form": form, "titre": "Nouveau relevé"},
    )


def releve_modifier(request, pk):
    releve = get_object_or_404(Releve, pk=pk)
    form = ReleveForm(request.POST or None, instance=releve)
    if request.method == "POST" and form.is_valid():
        form.save()
        messages.success(request, "Relevé mis à jour.")
        return redirect("suivi:releve_liste")
    return render(
        request,
        "suivi/releve_form.html",
        {
            "rubrique": "releves",
            "form": form,
            "titre": "Modifier le relevé",
            "releve": releve,
        },
    )


@require_http_methods(["POST"])
def releve_supprimer(request, pk):
    releve = get_object_or_404(Releve, pk=pk)
    releve.delete()
    messages.success(request, f"Relevé du {releve.date:%d/%m/%Y} supprimé.")
    return redirect("suivi:releve_liste")


# ---------------------------------------------------------------------------
# Import
# ---------------------------------------------------------------------------


def importer(request):
    form = ImportExcelForm(request.POST or None, request.FILES or None)
    if request.method == "POST" and form.is_valid():
        televerse = form.cleaned_data["fichier"]
        with tempfile.TemporaryDirectory() as dossier:
            chemin = Path(dossier) / "compteur.xlsx"
            with open(chemin, "wb") as cible:
                for morceau in televerse.chunks():
                    cible.write(morceau)
            try:
                call_command(
                    "import_excel", str(chemin), purger=form.cleaned_data["purger"]
                )
            except Exception as exc:  # noqa: BLE001
                messages.error(request, f"Import impossible : {exc}")
            else:
                messages.success(
                    request,
                    "Import terminé. Pensez à synchroniser les degrés-jours si la "
                    "période couverte a changé.",
                )
                return redirect("suivi:tableau_de_bord")

    return render(
        request,
        "suivi/importer.html",
        {
            "form": form,
            "compteurs": Compteur.objects.select_related("maison").annotate(
                nb=Count("releves")
            ),
            "nb_releves": Releve.objects.count(),
            "nb_dj": DegreJour.objects.count(),
        },
    )


# ---------------------------------------------------------------------------
# Application web installable (PWA)
# ---------------------------------------------------------------------------


@login_not_required
def manifeste(request):
    """Décrit l'application pour qu'Android propose de l'installer.

    Servi par une vue plutôt que comme fichier statique : les URL des icônes
    passent ainsi par ``static``, qui reste valable quelle que soit l'adresse
    du serveur sur le réseau.
    """
    return JsonResponse(
        {
            "name": "Suivi des compteurs",
            "short_name": "Compteurs",
            "description": "Relevés d'eau, de gaz et d'électricité, prévisions et comparaison climatique.",
            "lang": "fr-BE",
            "dir": "ltr",
            "start_url": reverse("suivi:tableau_de_bord"),
            "scope": "/",
            "display": "standalone",
            "orientation": "portrait-primary",
            "background_color": "#f9f9f7",
            "theme_color": "#2a78d6",
            "icons": [
                {
                    "src": static(f"suivi/icons/icone-{taille}.png"),
                    "sizes": f"{taille}x{taille}",
                    "type": "image/png",
                    "purpose": "any",
                }
                for taille in (192, 512)
            ]
            + [
                {
                    "src": static("suivi/icons/icone-maskable-512.png"),
                    "sizes": "512x512",
                    "type": "image/png",
                    # Icône « masquable » : Android la recadre selon la forme
                    # choisie par le lanceur, d'où la marge de sécurité.
                    "purpose": "maskable",
                }
            ],
            "shortcuts": [
                {
                    "name": "Saisir un relevé",
                    "short_name": "Saisir",
                    "url": reverse("suivi:releve_creer"),
                }
            ],
        },
        content_type="application/manifest+json",
    )


def _version_ressources(noms: list[str]) -> str:
    """Empreinte des fichiers mis en cache par le service worker.

    Sans elle, le nom du cache ne changerait jamais et une feuille de style
    corrigée resterait invisible : le service worker continuerait à servir
    l'ancienne version, indéfiniment.
    """
    empreinte = hashlib.sha256()
    for nom in noms:
        chemin = find(nom)
        if chemin:
            etat = Path(chemin).stat()
            empreinte.update(f"{nom}:{etat.st_mtime_ns}:{etat.st_size}".encode())
    return empreinte.hexdigest()[:12]


@login_not_required
def service_worker(request):
    """Sert le service worker depuis la racine, seule portée qui couvre tout le site.

    Un service worker ne peut contrôler que les URL situées sous son propre
    chemin : placé dans ``/static/``, il ne verrait pas les pages.
    """
    noms = ["suivi/app.css", "suivi/charts.js", "suivi/chart.umd.min.js"]
    reponse = render(
        request,
        "suivi/sw.js",
        {
            "version": _version_ressources(noms),
            "ressources": [static(nom) for nom in noms],
            "hors_ligne": reverse("suivi:hors_ligne"),
        },
        content_type="application/javascript",
    )
    # Sans cela, le navigateur peut servir longtemps une version périmée — et
    # ne découvrirait donc jamais qu'un nouveau cache l'attend.
    reponse["Cache-Control"] = "no-cache"
    return reponse


@login_not_required
def hors_ligne(request):
    """Page affichée quand le serveur est injoignable et la page non mise en cache."""
    return render(request, "suivi/hors_ligne.html", {"rubrique": ""})
