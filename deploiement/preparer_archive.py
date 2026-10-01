"""Fabrique l'archive à téléverser sur PythonAnywhere.

Exclut tout ce qui n'a pas sa place sur le serveur : environnement virtuel,
base locale, projet Android, outils de compilation. Les 512 Mo de l'offre
gratuite se remplissent vite, et le dossier .venv pèse à lui seul davantage que
le quota entier.

    python deploiement/preparer_archive.py
"""
from __future__ import annotations

import subprocess
import sys
import zipfile
from pathlib import Path

RACINE = Path(__file__).resolve().parent.parent
ARCHIVE = RACINE / "deploiement" / "suivi_compteurs_pour_serveur.zip"
EXPORT = RACINE / "donnees.json"

# Dossiers entiers à ne jamais embarquer.
DOSSIERS_EXCLUS = {
    ".venv", ".outils", ".git", "__pycache__", "android", "media",
    "staticfiles", "deploiement", ".idea", ".vscode",
}

# Fichiers à ne jamais embarquer, par motif.
MOTIFS_EXCLUS = (
    "db.sqlite3", ".env", "compteur.xlsx", "~$compteur.xlsx",
)
SUFFIXES_EXCLUS = {".pyc", ".pyo", ".zip", ".log"}


def inclure(chemin: Path) -> bool:
    relatif = chemin.relative_to(RACINE)
    if any(partie in DOSSIERS_EXCLUS for partie in relatif.parts):
        return False
    if chemin.name in MOTIFS_EXCLUS:
        return False
    if chemin.suffix in SUFFIXES_EXCLUS:
        return False
    return True


def main() -> int:
    python = RACINE / ".venv" / "Scripts" / "python.exe"
    if not python.exists():
        python = Path(sys.executable)

    print("Export des données…")
    resultat = subprocess.run(
        [str(python), "manage.py", "exporter_donnees", str(EXPORT)],
        cwd=RACINE,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    if resultat.returncode != 0:
        print(resultat.stdout)
        print(resultat.stderr, file=sys.stderr)
        return 1
    print(resultat.stdout.strip().splitlines()[-1] if resultat.stdout else "")

    ARCHIVE.parent.mkdir(parents=True, exist_ok=True)
    fichiers = sorted(c for c in RACINE.rglob("*") if c.is_file() and inclure(c))

    print(f"\nArchivage de {len(fichiers)} fichiers…")
    with zipfile.ZipFile(ARCHIVE, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zip_:
        for fichier in fichiers:
            zip_.write(fichier, fichier.relative_to(RACINE).as_posix())
        # Le guide et le modèle de configuration voyagent avec le code.
        extras = (
            "GUIDE.md", "env.exemple", "wsgi_pythonanywhere.py",
            # Fichiers déjà renseignés pour le compte visé : ils évitent de
            # retaper des secrets de cinquante caractères dans une console web.
            "env.cimeclean", "wsgi_cimeclean.py",
        )
        for extra in extras:
            source = RACINE / "deploiement" / extra
            if source.exists():
                zip_.write(source, f"deploiement/{extra}")

    taille = ARCHIVE.stat().st_size
    print(f"\n{ARCHIVE.name} — {taille / 1024 / 1024:.1f} Mo")

    # Contrôle : le projet est inutilisable s'il manque l'un de ces fichiers.
    with zipfile.ZipFile(ARCHIVE) as zip_:
        noms = set(zip_.namelist())
    attendus = [
        "manage.py",
        "requirements.txt",
        "donnees.json",
        "config/settings.py",
        "config/wsgi.py",
        "suivi/models.py",
        "suivi/api.py",
        "suivi/static/suivi/chart.umd.min.js",
        "suivi/templates/suivi/base.html",
        "suivi/migrations/0001_initial.py",
    ]
    manquants = [nom for nom in attendus if nom not in noms]
    if manquants:
        print("\nATTENTION, fichiers absents de l'archive :")
        for nom in manquants:
            print(f"  - {nom}")
        return 1

    indesirables = [
        nom for nom in noms
        if nom.startswith((".venv/", "android/", ".outils/")) or nom.endswith("db.sqlite3")
    ]
    if indesirables:
        print(f"\nATTENTION, {len(indesirables)} fichiers indésirables embarqués.")
        return 1

    print("Contrôles passés : archive complète et sans superflu.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
