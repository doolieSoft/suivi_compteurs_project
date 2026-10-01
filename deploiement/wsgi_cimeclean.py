"""Contenu à coller dans le fichier WSGI de PythonAnywhere.

Ce fichier ne va pas dans le projet. Son contenu remplace intégralement celui
de :

    /var/www/cimeclean_pythonanywhere_com_wsgi.py

éditable depuis l'onglet « Web » du tableau de bord, section « Code », lien
« WSGI configuration file ». Effacez tout ce qui s'y trouve avant de coller.

Aucun secret ici : la configuration vient du fichier « .env » placé à la racine
du projet, ce qui garantit que les consoles Bash lisent exactement les mêmes
réglages que le serveur web — donc, le cas échéant, la même base de données.
"""
import os
import sys

CHEMIN_PROJET = "/home/cimeclean/suivi_compteurs_project"

if CHEMIN_PROJET not in sys.path:
    sys.path.insert(0, CHEMIN_PROJET)

os.environ["DJANGO_SETTINGS_MODULE"] = "config.settings"

from django.core.wsgi import get_wsgi_application  # noqa: E402

application = get_wsgi_application()
