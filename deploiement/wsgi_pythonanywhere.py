"""Contenu à coller dans le fichier WSGI de PythonAnywhere.

Ce fichier ne se copie pas tel quel dans le projet : son contenu remplace celui
de /var/www/VOTRENOM_pythonanywhere_com_wsgi.py, éditable depuis l'onglet
« Web » du tableau de bord.

Remplacez VOTRENOM par votre nom d'utilisateur PythonAnywhere.

Aucun secret ici : la configuration vient du fichier « .env » placé à la racine
du projet, ce qui garantit que les consoles Bash lisent exactement les mêmes
réglages que le serveur web.
"""
import os
import sys

CHEMIN_PROJET = "/home/VOTRENOM/suivi_compteurs_project"

if CHEMIN_PROJET not in sys.path:
    sys.path.insert(0, CHEMIN_PROJET)

os.environ["DJANGO_SETTINGS_MODULE"] = "config.settings"

from django.core.wsgi import get_wsgi_application  # noqa: E402

application = get_wsgi_application()
