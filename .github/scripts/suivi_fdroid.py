#!/usr/bin/env python3
"""Prévient, par une issue, de ce que devient la dernière version chez F-Droid.

F-Droid publie seul et ne prévient de rien : ni d'une version publiée, ni d'une
construction échouée. Une issue par version et par événement : GitHub envoie
alors un e-mail. SUIVI_ESSAI=1 affiche ce qui serait fait, sans rien écrire.
"""

import datetime
import json
import os
import re
import sys
import urllib.parse
import urllib.request

DEPOT = os.environ.get("GITHUB_REPOSITORY", "doolieSoft/suivi_compteurs_project")
APPLI = "be.suivicompteurs.app"
JOURS_DE_RETARD = 14
ESSAI = os.environ.get("SUIVI_ESSAI") == "1"
JETON = os.environ.get("GH_TOKEN", "")


def demander(url, donnees=None):
    entetes = {"User-Agent": "suivi-fdroid", "Accept": "application/vnd.github+json"}
    if JETON and url.startswith("https://api.github.com"):
        entetes["Authorization"] = f"Bearer {JETON}"
    corps = json.dumps(donnees).encode() if donnees is not None else None
    with urllib.request.urlopen(urllib.request.Request(url, corps, entetes), timeout=60) as reponse:
        return reponse.read().decode()


def issue_existe(titre):
    requete = urllib.parse.quote(f'repo:{DEPOT} is:issue in:title "{titre}"')
    trouvees = json.loads(demander(f"https://api.github.com/search/issues?q={requete}"))["items"]
    return any(i["title"] == titre for i in trouvees)


def prevenir(titre, corps):
    if issue_existe(titre):
        print(f"Déjà signalé : {titre}")
    elif ESSAI:
        print(f"[essai] issue : {titre}\n{corps}")
    else:
        demander(f"https://api.github.com/repos/{DEPOT}/issues", {"title": titre, "body": corps})
        print(f"Issue ouverte : {titre}")


def main():
    version = json.loads(demander(f"https://api.github.com/repos/{DEPOT}/releases/latest"))
    etiquette = version["tag_name"]
    nom = etiquette.lstrip("v")
    gradle = demander(f"https://raw.githubusercontent.com/{DEPOT}/{etiquette}/android/app/build.gradle.kts")
    code = int(re.search(r"^\s*versionCode = (\d+)$", gradle, re.M).group(1))
    fiche = json.loads(demander(f"https://f-droid.org/api/v1/packages/{APPLI}"))
    publie = max(p["versionCode"] for p in fiche["packages"])
    print(f"Dernière version {nom} (code {code}), F-Droid en est au code {publie}.")

    if publie >= code:
        prevenir(
            f"F-Droid : {nom} disponible",
            f"La version {nom} est publiée : https://f-droid.org/packages/{APPLI}/\n\n"
            "Rien à faire : cette issue peut être fermée.",
        )
        return

    # Une étiquette sautée par F-Droid reste signalée par son propre code.
    echecs = sorted(
        c for a, c in json.loads(demander("https://f-droid.org/repo/status/build.json"))["failedBuilds"]
        if a == APPLI and publie < c <= code
    )
    for echec in echecs:
        prevenir(
            f"F-Droid : échec de construction (code {echec})",
            f"La construction a échoué chez F-Droid. Journal :\n"
            f"https://monitor.f-droid.org/builds/log/{APPLI}/{echec}\n\n"
            "Une version corrigée, ou un changement de recette dans fdroiddata, sera nécessaire.",
        )

    paru = datetime.datetime.fromisoformat(version["published_at"].replace("Z", "+00:00"))
    age = (datetime.datetime.now(datetime.timezone.utc) - paru).days
    if not echecs and age >= JOURS_DE_RETARD:
        prevenir(
            f"F-Droid : toujours pas de {nom}",
            f"La version {nom} a été publiée il y a {age} jours et F-Droid en est encore au code {publie}.\n\n"
            f"À regarder : https://monitor.f-droid.org/builds/log/{APPLI}/{code}",
        )


if __name__ == "__main__":
    sys.exit(main())
