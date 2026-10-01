"""Configuration Django du suivi de compteurs.

Un seul fichier sert les deux usages — le PC à la maison et l'hébergement en
ligne — parce que deux fichiers de configuration divergent toujours : on corrige
un réglage d'un côté, on oublie l'autre, et le bogue ne se révèle qu'en
production.

Ce qui distingue les deux environnements passe donc par des variables
d'environnement, dont les valeurs par défaut sont celles du poste local :

    SUIVI_HOTES       hôtes autorisés, séparés par des virgules
                      (ex. « stefano.pythonanywhere.com »)
    SUIVI_DEBUG       « 0 » en production — obligatoire
    SUIVI_CLE         clé secrète Django, à générer pour la production
    SUIVI_JETON_API   jeton partagé avec l'application Android
    SUIVI_BASE        « mysql » pour utiliser MySQL, SQLite sinon
    SUIVI_MYSQL_*     coordonnées MySQL le cas échéant
"""
import os
import socket
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent


def _charger_env(chemin: Path) -> None:
    """Lit un fichier « .env » de lignes ``CLE=valeur``.

    Préféré à des variables posées dans le fichier WSGI : celles-ci ne valent
    que pour le serveur web, et les commandes lancées depuis une console
    (migrations, synchronisation des degrés-jours) s'exécuteraient alors avec
    une configuration différente — donc, le cas échéant, sur une autre base.

    Les variables déjà présentes dans l'environnement gagnent, ce qui permet de
    surcharger ponctuellement sans toucher au fichier.
    """
    if not chemin.is_file():
        return
    for ligne in chemin.read_text(encoding="utf-8").splitlines():
        ligne = ligne.strip()
        if not ligne or ligne.startswith("#") or "=" not in ligne:
            continue
        cle, _, valeur = ligne.partition("=")
        os.environ.setdefault(cle.strip(), valeur.strip().strip("\"'"))


_charger_env(BASE_DIR / ".env")


def _booleen(nom: str, defaut: bool) -> bool:
    valeur = os.environ.get(nom)
    if valeur is None:
        return defaut
    return valeur.strip().lower() in {"1", "true", "oui", "yes"}


DEBUG = _booleen("SUIVI_DEBUG", True)

# En local, la clé n'a pas vocation à être secrète : l'application n'est
# accessible que du réseau domestique. En ligne, elle protège les sessions et
# les jetons de formulaire, et doit donc être fournie par l'environnement.
SECRET_KEY = os.environ.get(
    "SUIVI_CLE", "django-insecure-suivi-compteurs-usage-local-uniquement"
)


def _adresses_locales() -> list[str]:
    """Adresses IPv4 de la machine, pour que le téléphone puisse joindre le serveur.

    Le PC reçoit généralement son adresse par DHCP : la recalculer au démarrage
    évite d'avoir à modifier ce fichier à chaque changement de box.
    """
    adresses = {"localhost", "127.0.0.1", "[::1]"}
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            adresses.add(info[4][0])
    except socket.gaierror:
        pass
    return sorted(adresses)


_hotes_declares = [
    hote.strip() for hote in os.environ.get("SUIVI_HOTES", "").split(",") if hote.strip()
]
ALLOWED_HOSTS = _hotes_declares or _adresses_locales()

# Django exige depuis la version 4 que l'origine des formulaires POST soit
# déclarée. En ligne le trafic est en HTTPS, à la maison en HTTP.
CSRF_TRUSTED_ORIGINS = (
    [f"https://{hote}" for hote in _hotes_declares]
    if _hotes_declares
    else [f"http://{a}:8000" for a in ALLOWED_HOSTS if not a.startswith("[")]
)

# Derrière le répartiteur de PythonAnywhere, Django ne voit que du HTTP : sans
# cet en-tête, il croirait la connexion non chiffrée et bouclerait sur les
# redirections.
if not DEBUG:
    SECURE_PROXY_SSL_HEADER = ("HTTP_X_FORWARDED_PROTO", "https")
    SESSION_COOKIE_SECURE = True
    CSRF_COOKIE_SECURE = True
    X_FRAME_OPTIONS = "DENY"

    # Redirige le trafic en clair vers HTTPS. Repose sur l'en-tête déclaré
    # ci-dessus : sans lui, Django ne verrait jamais la connexion comme
    # chiffrée et redirigerait en boucle.
    SECURE_SSL_REDIRECT = _booleen("SUIVI_FORCER_HTTPS", True)

    # Demande au navigateur de n'utiliser que HTTPS pour ce domaine pendant un
    # an. Volontairement sans « includeSubDomains » ni « preload » : sur un
    # sous-domaine mutualisé comme VOTRENOM.pythonanywhere.com, ces options
    # engageraient des domaines qui ne vous appartiennent pas.
    SECURE_HSTS_SECONDS = int(os.environ.get("SUIVI_HSTS_SECONDES", 31536000))
    SECURE_HSTS_INCLUDE_SUBDOMAINS = False
    SECURE_HSTS_PRELOAD = False

    # « check --deploy » réclame les deux options ci-dessus. Les activer serait
    # ici une faute : sur un sous-domaine mutualisé, « includeSubDomains »
    # imposerait HTTPS à des domaines voisins qui ne vous appartiennent pas, et
    # « preload » inscrirait durablement le domaine de l'hébergeur dans les
    # navigateurs. Taire ces avertissements vaut mieux que de les laisser
    # traîner : un rapport qui crie au loup finit par ne plus être lu.
    SILENCED_SYSTEM_CHECKS = ["security.W005", "security.W021"]

INSTALLED_APPS = [
    "django.contrib.admin",
    "django.contrib.auth",
    "django.contrib.contenttypes",
    "django.contrib.sessions",
    "django.contrib.messages",
    "django.contrib.staticfiles",
    "django.contrib.humanize",
    "suivi",
]

MIDDLEWARE = [
    "django.middleware.security.SecurityMiddleware",
    "django.contrib.sessions.middleware.SessionMiddleware",
    "django.middleware.common.CommonMiddleware",
    "django.middleware.csrf.CsrfViewMiddleware",
    "django.contrib.auth.middleware.AuthenticationMiddleware",
    "django.contrib.messages.middleware.MessageMiddleware",
    "django.middleware.clickjacking.XFrameOptionsMiddleware",
]

ROOT_URLCONF = "config.urls"

TEMPLATES = [
    {
        "BACKEND": "django.template.backends.django.DjangoTemplates",
        "DIRS": [BASE_DIR / "templates"],
        "APP_DIRS": True,
        "OPTIONS": {
            "context_processors": [
                "django.template.context_processors.debug",
                "django.template.context_processors.request",
                "django.contrib.auth.context_processors.auth",
                "django.contrib.messages.context_processors.messages",
            ],
        },
    },
]

WSGI_APPLICATION = "config.wsgi.application"

# SQLite suffit largement : la base pèse moins d'un mégaoctet et l'application
# n'a qu'un utilisateur. MySQL reste disponible pour les comptes PythonAnywhere
# qui y ont droit — leur système de fichiers distribué supporte mal les verrous
# de SQLite lorsque plusieurs processus écrivent en même temps.
if os.environ.get("SUIVI_BASE", "").lower() == "mysql":
    DATABASES = {
        "default": {
            "ENGINE": "django.db.backends.mysql",
            "NAME": os.environ["SUIVI_MYSQL_NOM"],
            "USER": os.environ["SUIVI_MYSQL_UTILISATEUR"],
            "PASSWORD": os.environ["SUIVI_MYSQL_MOTDEPASSE"],
            "HOST": os.environ["SUIVI_MYSQL_HOTE"],
            "OPTIONS": {"charset": "utf8mb4"},
            # Les connexions MySQL de PythonAnywhere sont coupées après cinq
            # minutes d'inactivité ; les recycler évite des erreurs sporadiques.
            "CONN_MAX_AGE": 60,
        }
    }
else:
    DATABASES = {
        "default": {
            "ENGINE": "django.db.backends.sqlite3",
            "NAME": os.environ.get("SUIVI_SQLITE", BASE_DIR / "db.sqlite3"),
            "OPTIONS": {
                # Attendre plutôt qu'échouer si un autre processus écrit.
                "timeout": 20,
            },
        }
    }

AUTH_PASSWORD_VALIDATORS = [
    {"NAME": "django.contrib.auth.password_validation.UserAttributeSimilarityValidator"},
    {"NAME": "django.contrib.auth.password_validation.MinimumLengthValidator"},
    {"NAME": "django.contrib.auth.password_validation.CommonPasswordValidator"},
    {"NAME": "django.contrib.auth.password_validation.NumericPasswordValidator"},
]

LANGUAGE_CODE = "fr-be"
TIME_ZONE = "Europe/Brussels"
USE_I18N = True
USE_TZ = True

STATIC_URL = "/static/"
STATICFILES_DIRS = [BASE_DIR / "static"] if (BASE_DIR / "static").exists() else []
# Destination de « collectstatic ». En ligne, c'est ce dossier que le serveur
# web sert directement, sans passer par Django.
STATIC_ROOT = BASE_DIR / "staticfiles"

STORAGES = {
    "default": {"BACKEND": "django.core.files.storage.FileSystemStorage"},
    "staticfiles": {
        # En production, « collectstatic » insère un condensat du contenu dans
        # le nom de chaque fichier : app.css devient app.a1b2c3d4.css. Sans
        # cela, navigateurs et WebView continueraient de servir l'ancienne
        # feuille de style après une mise à jour — parfois des heures durant,
        # puisqu'en l'absence d'en-tête explicite ils se donnent le droit de
        # deviner une durée de fraîcheur.
        "BACKEND": (
            "django.contrib.staticfiles.storage.StaticFilesStorage"
            if DEBUG
            else "django.contrib.staticfiles.storage.ManifestStaticFilesStorage"
        )
    },
}

DEFAULT_AUTO_FIELD = "django.db.models.BigAutoField"

# --- Paramètres métier -----------------------------------------------------

# Base de calcul des degrés-jours. 16.5 °C est la convention belge
# (degrés-jours 16.5/16.5) utilisée par la VMM et les fournisseurs.
BASE_DEGRES_JOURS = 16.5

# Nombre d'années glissantes servant à calculer les « degrés-jours normaux »
# (climat de référence) utilisés pour la normalisation et les prévisions.
ANNEES_NORMALE_CLIMATIQUE = 10

# Pouvoir calorifique supérieur moyen du gaz naturel distribué en Belgique,
# en kWh par m³. Sert à convertir les index gaz (relevés en m³) en kWh.
COEF_CONVERSION_GAZ_KWH = 11.0

# --- Médias (photos de compteurs) -----------------------------------------

MEDIA_URL = "/media/"
MEDIA_ROOT = Path(os.environ.get("SUIVI_MEDIA", BASE_DIR / "media"))

# Taille maximale d'une photo acceptée, en octets.
TAILLE_MAX_PHOTO = 8 * 1024 * 1024

# Côté le plus long auquel les photos sont réduites avant stockage : une photo
# de compteur n'a pas besoin de 12 mégapixels pour servir de preuve d'index.
PHOTO_COTE_MAX = 1600

# --- Application mobile ----------------------------------------------------

# Jeton partagé avec l'application Android.
#
# Aucune valeur par défaut, volontairement : un secret inscrit dans le code
# finit dans l'historique du dépôt, d'où il ne s'efface plus. Il se déclare
# dans le fichier « .env », que Git ignore.
#
# Pour en produire un :
#   python -c "import secrets; print('SUIVI_JETON_API=' + secrets.token_urlsafe(24))"
#
# Tant qu'il est vide, l'API refuse toute requête — voir le contrôle déclaré
# dans suivi/apps.py, qui le signale au démarrage.
JETON_API = os.environ.get("SUIVI_JETON_API", "")
