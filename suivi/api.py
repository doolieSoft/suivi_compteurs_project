"""API consommée par l'application Android.

Volontairement minimale : deux points d'entrée suffisent à l'usage visé —
savoir quoi relever, et enregistrer un relevé. Tout le reste de l'application
est consulté dans la WebView, qui affiche les pages HTML habituelles.

L'authentification repose sur un jeton partagé transmis dans l'en-tête
``X-Jeton``. Ce n'est pas un système de comptes : cela empêche simplement un
autre appareil du réseau d'écrire dans la base par mégarde.
"""
from __future__ import annotations

import datetime as dt
import io
import json
from decimal import Decimal, InvalidOperation

from django.conf import settings
from django.contrib.auth.decorators import login_not_required
from django.core.cache import cache
from django.core.files.uploadedfile import UploadedFile
from django.db.models import Count, Max, Sum
from django.http import HttpRequest, JsonResponse
from django.views.decorators.csrf import csrf_exempt
from django.views.decorators.http import require_GET, require_POST

from .models import Compteur, DegreJour, Energie, Releve, SourceReleve

# Types d'images acceptés. Le format HEIC d'Android est converti en JPEG par
# l'application avant envoi : inutile de l'accepter ici.
TYPES_IMAGE = {"image/jpeg", "image/png", "image/webp"}


class ErreurApi(Exception):
    def __init__(self, message: str, code: int = 400):
        super().__init__(message)
        self.message = message
        self.code = code


def _verifier_jeton(request: HttpRequest) -> None:
    attendu = getattr(settings, "JETON_API", "")
    fourni = request.headers.get("X-Jeton", "")
    if not attendu or fourni != attendu:
        raise ErreurApi("Jeton absent ou invalide.", 401)


def _json_erreur(exc: ErreurApi) -> JsonResponse:
    return JsonResponse({"erreur": exc.message}, status=exc.code)


# ---------------------------------------------------------------------------
# État : que faut-il relever ?
# ---------------------------------------------------------------------------


# Plancher du débit journalier : un compteur neuf, ou relevé une seule fois,
# n'offre pas encore d'historique exploitable.
DEBIT_JOURNALIER_PLANCHER = 1.0


def _debit_journalier_max(compteur: Compteur) -> float:
    """Consommation journalière la plus forte observée sur ce compteur."""
    from .services import consommation as cs

    periodes = cs.periodes([compteur])
    debits = [p.volume_journalier for p in periodes if p.nb_jours > 0 and p.volume > 0]
    if not debits:
        return DEBIT_JOURNALIER_PLANCHER
    return round(max(max(debits), DEBIT_JOURNALIER_PLANCHER), 3)


@login_not_required
@require_GET
def etat(request: HttpRequest) -> JsonResponse:
    """Liste des compteurs actifs, avec leur dernier index connu.

    L'application s'en sert pour pré-remplir le formulaire et pour prévenir
    l'utilisateur quand une lecture automatique est incohérente.
    """
    try:
        _verifier_jeton(request)
    except ErreurApi as exc:
        return _json_erreur(exc)

    compteurs = []
    for compteur in (
        # Un compteur n'est proposé à la saisie que s'il est encore posé *et*
        # dans une maison encore occupée : inutile d'offrir de relever
        # l'ancienne adresse au pied du compteur.
        Compteur.objects.filter(
            date_depose__isnull=True, maison__date_sortie__isnull=True
        )
        .select_related("maison")
        .order_by("maison__date_entree", "energie", "plage")
    ):
        dernier = compteur.releves.order_by("-date").first()
        compteurs.append(
            {
                "id": compteur.pk,
                "maison": compteur.maison.nom,
                "maison_slug": compteur.maison.slug,
                "energie": compteur.energie,
                "energie_libelle": compteur.get_energie_display(),
                "plage": compteur.plage,
                "libelle": str(compteur),
                "unite": compteur.unite,
                # Nombre de décimales attendu : l'OCR s'en sert pour placer la
                # virgule, que les compteurs n'affichent pas toujours.
                "decimales": 3 if compteur.energie != Energie.ELECTRICITE else 1,
                "dernier_index": float(dernier.index) if dernier else None,
                "dernier_releve": dernier.date.isoformat() if dernier else None,
                # Débit journalier le plus fort jamais relevé sur ce compteur.
                # Le téléphone s'en sert pour borner ce qu'une lecture
                # automatique peut proposer : sans cette borne, le numéro de
                # série imprimé sur la plaque passe pour un index crédible.
                "conso_journaliere_max": _debit_journalier_max(compteur),
            }
        )

    return JsonResponse(
        {
            "version": 1,
            "aujourdhui": dt.date.today().isoformat(),
            "compteurs": compteurs,
        }
    )


# ---------------------------------------------------------------------------
# Création d'un relevé
# ---------------------------------------------------------------------------


def _reduire_photo(fichier: UploadedFile):
    """Réduit la photo avant stockage, en corrigeant son orientation.

    Les appareils photo inscrivent l'orientation dans les métadonnées EXIF
    plutôt que de pivoter les pixels ; sans correction, une photo prise en
    portrait s'affiche couchée.
    """
    try:
        from PIL import Image, ImageOps
    except ImportError:
        return fichier

    try:
        image = Image.open(fichier)
        image = ImageOps.exif_transpose(image)
        image = image.convert("RGB")
        image.thumbnail(
            (settings.PHOTO_COTE_MAX, settings.PHOTO_COTE_MAX), Image.LANCZOS
        )
        tampon = io.BytesIO()
        image.save(tampon, format="JPEG", quality=85, optimize=True)
        tampon.seek(0)
    except Exception:
        # Une photo illisible ne doit pas faire échouer l'enregistrement de
        # l'index, qui est la donnée vraiment importante.
        fichier.seek(0)
        return fichier

    from django.core.files.base import ContentFile

    return ContentFile(tampon.read(), name="photo.jpg")


def _champ_booleen(valeur) -> bool:
    """Le JSON envoie un booléen, le multipart sa forme écrite."""
    if isinstance(valeur, bool):
        return valeur
    return str(valeur).strip().lower() in ("1", "true", "vrai", "oui", "on")


def _marque_annuelle(donnees) -> dict:
    """Champ « annuel » à enregistrer, seulement s'il a été transmis.

    L'envoi d'une photo repasse par la création du relevé : sans cette
    précaution, une ancienne version de l'application effacerait la marque.
    """
    if "annuel" not in donnees:
        return {}
    return {"annuel": _champ_booleen(donnees.get("annuel"))}


def _champ_decimal(valeur, nom: str, *, obligatoire: bool = True) -> Decimal | None:
    if valeur in (None, ""):
        if obligatoire:
            raise ErreurApi(f"Champ « {nom} » manquant.")
        return None
    try:
        nombre = Decimal(str(valeur).replace(",", "."))
    except (InvalidOperation, ValueError):
        raise ErreurApi(f"Champ « {nom} » illisible : {valeur!r}.") from None
    if nombre < 0:
        raise ErreurApi(f"Champ « {nom} » négatif.")
    return nombre


@csrf_exempt
@login_not_required
@require_POST
def creer_releve(request: HttpRequest) -> JsonResponse:
    """Enregistre un relevé envoyé par le téléphone.

    Accepte du multipart (avec photo) comme du JSON (sans). Le contrôle de
    cohérence est le même que dans le formulaire web : un index ne peut pas
    reculer, ce qui rattrape l'essentiel des erreurs de lecture automatique.
    """
    try:
        _verifier_jeton(request)

        if request.content_type and request.content_type.startswith("application/json"):
            try:
                donnees = json.loads(request.body or b"{}")
            except json.JSONDecodeError:
                raise ErreurApi("Corps de requête JSON illisible.") from None
            photo = None
        else:
            donnees = request.POST
            photo = request.FILES.get("photo")

        compteur_id = donnees.get("compteur")
        if not compteur_id:
            raise ErreurApi("Champ « compteur » manquant.")
        try:
            compteur = Compteur.objects.select_related("maison").get(pk=compteur_id)
        except (Compteur.DoesNotExist, ValueError, TypeError):
            raise ErreurApi(f"Compteur {compteur_id!r} inconnu.", 404) from None

        index = _champ_decimal(donnees.get("index"), "index")
        index_ocr = _champ_decimal(
            donnees.get("index_ocr"), "index_ocr", obligatoire=False
        )

        brut = donnees.get("date") or dt.date.today().isoformat()
        try:
            jour = dt.date.fromisoformat(str(brut)[:10])
        except ValueError:
            raise ErreurApi(f"Date illisible : {brut!r} (attendu AAAA-MM-JJ).") from None

        if jour > dt.date.today():
            raise ErreurApi("Un relevé ne peut pas être daté dans le futur.")

        # --- cohérence de l'index, comme dans le formulaire web -----------
        precedent = compteur.releves.filter(date__lt=jour).order_by("-date").first()
        if precedent and index < precedent.index:
            raise ErreurApi(
                f"Index {index} inférieur au relevé du "
                f"{precedent.date:%d/%m/%Y} ({precedent.index}). "
                "S'agit-il d'un compteur remplacé ?",
                409,
            )
        suivant = compteur.releves.filter(date__gt=jour).order_by("date").first()
        if suivant and index > suivant.index:
            raise ErreurApi(
                f"Index {index} supérieur au relevé du "
                f"{suivant.date:%d/%m/%Y} ({suivant.index}).",
                409,
            )

        if photo is not None:
            if photo.size > settings.TAILLE_MAX_PHOTO:
                raise ErreurApi("Photo trop volumineuse.", 413)
            if photo.content_type not in TYPES_IMAGE:
                raise ErreurApi(f"Type d'image non accepté : {photo.content_type}.")
            photo = _reduire_photo(photo)

        releve, cree = Releve.objects.update_or_create(
            compteur=compteur,
            date=jour,
            defaults={
                "index": index,
                "source": SourceReleve.MANUEL,
                "commentaire": str(donnees.get("commentaire", ""))[:200],
                "index_lu_automatiquement": index_ocr,
                **_marque_annuelle(donnees),
            },
        )
        if photo is not None:
            releve.photo.save(photo.name, photo, save=True)

    except ErreurApi as exc:
        return _json_erreur(exc)

    consommation = None
    if precedent:
        ecart_jours = (jour - precedent.date).days
        if ecart_jours > 0:
            consommation = {
                "volume": float(index - precedent.index),
                "jours": ecart_jours,
                "par_jour": float(index - precedent.index) / ecart_jours,
                "unite": compteur.unite,
            }

    return JsonResponse(
        {
            "id": releve.pk,
            "cree": cree,
            "date": releve.date.isoformat(),
            "index": float(releve.index),
            "compteur": str(compteur),
            "photo": bool(releve.photo),
            # Renvoyé pour que l'application confirme utilement : « 18 m³ en
            # 24 jours » est plus parlant qu'un simple « enregistré ».
            "consommation_depuis_le_precedent": consommation,
        },
        status=201 if cree else 200,
    )


# ---------------------------------------------------------------------------
# Instantané : ce que le téléphone garde pour fonctionner hors ligne
# ---------------------------------------------------------------------------


def _signature_donnees() -> str:
    """Empreinte des données dont dépend l'instantané.

    Sert à savoir si le cache est encore valable. La somme des index est
    délibérément incluse : sans elle, la correction d'un relevé existant ne
    changerait ni le nombre de lignes ni la date la plus récente, et l'on
    servirait une analyse périmée.
    """
    releves = Releve.objects.aggregate(
        nombre=Count("id"), somme=Sum("index"), dernier=Max("date")
    )
    # Marquer un relevé « annuel » déplace les bornes des prévisions sans
    # toucher à aucun index.
    annuels = Releve.objects.filter(annuel=True).aggregate(
        nombre=Count("id"), dernier=Max("date")
    )
    degres = DegreJour.objects.aggregate(nombre=Count("id"), dernier=Max("date"))
    return "|".join(
        str(v) for v in (*releves.values(), *annuels.values(), *degres.values())
    )


@login_not_required
@require_GET
def instantane(request: HttpRequest) -> JsonResponse:
    """Photographie de l'analyse, destinée à être conservée sur le téléphone.

    Le calcul complet (ventilation, modèle thermique, degrés-jours) reste ici :
    il suppose douze ans d'historique et un accès à Open-Meteo. Le téléphone en
    reçoit le résultat, qu'il affiche sans réseau jusqu'au prochain contact.

    Le résultat est mis en cache tant que les données n'ont pas bougé : le
    téléphone interroge cette adresse à chaque synchronisation, et recalculer
    douze ans de ventilation à chaque fois gaspillerait le quota de processeur
    d'un hébergement gratuit.
    """
    try:
        _verifier_jeton(request)
    except ErreurApi as exc:
        return _json_erreur(exc)

    cle = f"instantane:{_signature_donnees()}"
    en_cache = cache.get(cle)
    if en_cache is not None:
        return JsonResponse(en_cache)

    from .models import Maison
    from .services import consommation as cs
    from .services import degres_jours as djs
    from .services import previsions as pv

    def _arrondi(valeur, decimales=1):
        return None if valeur is None else round(valeur, decimales)

    lignes = []
    for maison in Maison.objects.select_related("station").all():
        normales = djs.dj_normaux_par_jour_calendaire(maison.station)
        for ligne in cs.lignes_de(maison):
            if not ligne.serie.jours:
                continue
            prevision = pv.prevoir(ligne, normales=normales)
            modele = ligne.serie.modele
            lignes.append(
                {
                    "cle": ligne.cle,
                    "maison": maison.nom,
                    "maison_actuelle": maison.est_actuelle,
                    "libelle": ligne.libelle_energie,
                    "energie": ligne.energie,
                    "unite": ligne.unite,
                    "url": ligne.get_absolute_url(),
                    "modele": {
                        "fiable": modele.fiable,
                        "base": _arrondi(modele.base, 4),
                        "k": _arrondi(modele.k, 5),
                        "r2": _arrondi(modele.r2, 3),
                    },
                    "prevision": (
                        {
                            "annee": prevision.annee,
                            "total_prevu": _arrondi(prevision.total_prevu),
                            "realise": _arrondi(prevision.realise),
                            "jours_realises": prevision.jours_realises,
                            "jours_restants": prevision.jours_restants,
                            "methode": prevision.methode,
                            "reference_annee": prevision.reference_annee,
                            "reference": _arrondi(prevision.reference),
                            "evolution_pct": _arrondi(prevision.evolution_pct),
                            "evolution_normalisee_pct": _arrondi(
                                prevision.evolution_normalisee_pct
                            ),
                        }
                        if prevision
                        else None
                    ),
                    "annees": [
                        {
                            "annee": a.annee,
                            "consommation": _arrondi(a.consommation),
                            "normalisee": _arrondi(a.consommation_normalisee),
                            "dj": round(a.dj_reel),
                            "complete": a.complete,
                        }
                        for a in cs.comparer_annees(ligne, normales=normales)
                        if a.jours_couverts >= 30
                    ],
                    "anomalies": [
                        {
                            "niveau": a.niveau,
                            "debut": a.debut.isoformat(),
                            "message": a.message,
                        }
                        for a in cs.detecter_anomalies(ligne)
                    ],
                }
            )

    charge = {
        "version": 1,
        "genere_le": dt.datetime.now().isoformat(timespec="seconds"),
        "lignes": lignes,
    }
    # Une heure suffit : la signature invalide déjà le cache dès qu'un relevé
    # change, et cette durée borne la dérive de l'horodatage affiché.
    cache.set(cle, charge, 3600)
    return JsonResponse(charge)


# ---------------------------------------------------------------------------
# Envoi groupé : vidage de la file d'attente du téléphone
# ---------------------------------------------------------------------------

# Au-delà, c'est que quelque chose ne tourne pas rond côté téléphone.
MAX_RELEVES_PAR_ENVOI = 200


@csrf_exempt
@login_not_required
@require_POST
def synchroniser(request: HttpRequest) -> JsonResponse:
    """Reçoit en un seul appel les relevés accumulés hors ligne.

    Chaque entrée est traitée indépendamment : un relevé refusé (index
    incohérent, compteur inconnu) n'empêche pas les autres de passer. La
    réponse détaille le sort de chacun, pour que le téléphone sache lesquels
    retirer de sa file et lesquels soumettre à l'utilisateur.

    Les photos ne transitent pas ici : elles partent une par une via
    ``creer_releve``, afin de ne pas bâtir une requête de plusieurs mégaoctets
    sur une connexion domestique capricieuse.
    """
    try:
        _verifier_jeton(request)
        try:
            charge = json.loads(request.body or b"{}")
        except json.JSONDecodeError:
            raise ErreurApi("Corps de requete JSON illisible.") from None
    except ErreurApi as exc:
        return _json_erreur(exc)

    entrees = charge.get("releves")
    if not isinstance(entrees, list):
        return _json_erreur(ErreurApi("Champ « releves » attendu : une liste."))
    if len(entrees) > MAX_RELEVES_PAR_ENVOI:
        return _json_erreur(
            ErreurApi(f"Maximum {MAX_RELEVES_PAR_ENVOI} relevés par envoi.", 413)
        )

    resultats = []
    for entree in entrees:
        reference = entree.get("reference") if isinstance(entree, dict) else None
        try:
            if not isinstance(entree, dict):
                raise ErreurApi("Entrée mal formée.")
            resultat = _enregistrer_une_entree(entree)
        except ErreurApi as exc:
            resultats.append(
                {"reference": reference, "accepte": False, "erreur": exc.message}
            )
        else:
            resultats.append({"reference": reference, "accepte": True, **resultat})

    return JsonResponse(
        {
            "traites": len(resultats),
            "acceptes": sum(1 for r in resultats if r["accepte"]),
            "resultats": resultats,
        }
    )


def _enregistrer_une_entree(entree: dict) -> dict:
    """Enregistre un relevé de la file d'attente. Lève ``ErreurApi`` s'il est refusé."""
    compteur_id = entree.get("compteur")
    try:
        compteur = Compteur.objects.select_related("maison").get(pk=compteur_id)
    except (Compteur.DoesNotExist, ValueError, TypeError):
        raise ErreurApi(f"Compteur {compteur_id!r} inconnu.") from None

    index = _champ_decimal(entree.get("index"), "index")
    index_ocr = _champ_decimal(entree.get("index_ocr"), "index_ocr", obligatoire=False)

    try:
        jour = dt.date.fromisoformat(str(entree.get("date", ""))[:10])
    except ValueError:
        raise ErreurApi("Date illisible (attendu AAAA-MM-JJ).") from None

    if jour > dt.date.today():
        raise ErreurApi("Un relevé ne peut pas être daté dans le futur.")

    precedent = compteur.releves.filter(date__lt=jour).order_by("-date").first()
    if precedent and index < precedent.index:
        raise ErreurApi(
            f"Index {index} inférieur au relevé du {precedent.date:%d/%m/%Y} "
            f"({precedent.index})."
        )
    suivant = compteur.releves.filter(date__gt=jour).order_by("date").first()
    if suivant and index > suivant.index:
        raise ErreurApi(
            f"Index {index} supérieur au relevé du {suivant.date:%d/%m/%Y} "
            f"({suivant.index})."
        )

    releve, cree = Releve.objects.update_or_create(
        compteur=compteur,
        date=jour,
        defaults={
            "index": index,
            "source": SourceReleve.MANUEL,
            "commentaire": str(entree.get("commentaire", ""))[:200],
            "index_lu_automatiquement": index_ocr,
            **_marque_annuelle(entree),
        },
    )
    return {"id": releve.pk, "cree": cree, "attend_photo": bool(entree.get("a_photo"))}
