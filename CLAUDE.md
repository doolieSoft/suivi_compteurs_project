# Consignes pour Claude

Application Android « Suivi compteurs » (Kotlin), seule partie du projet.
Le développeur est francophone : répondre en français.

## Publier une version

Chaque version publiée suit toutes ces étapes, sans attendre qu'on les demande :

1. **Numéros** dans `android/app/build.gradle.kts` : `versionCode` +1 et
   `versionName` (lignes littérales, lues par F-Droid et par le workflow).
2. **Notes de version** : `fastlane/metadata/android/{fr-FR,en-US,nl-NL}/changelogs/<versionCode>.txt`
   (moins de 500 caractères chacune).
3. **Vérifier** : `./gradlew testCompletDebugUnitTest lintCompletDebug lintLibreDebug`
   et la construction des deux variantes (lancer les tâches Gradle l'une après
   l'autre : en parallèle, le schéma Room ou les caches KSP peuvent se corrompre).
4. **Commit, étiquette `vX.Y.Z`, push** de `main` et de l'étiquette : le
   workflow GitHub publie les APK (« Suivi compteurs X.Y.Z »).
5. **Page GitHub Pages** (`docs/`) : la mettre à jour quand l'application
   change de façon visible — textes de `docs/index.html` (trois langues),
   captures `docs/captures/{fr,en,nl}/`, liens. Le numéro de version s'y
   affiche seul (API GitHub).
6. **Merge request F-Droid** (tant qu'elle n'est pas fusionnée) :
   https://gitlab.com/fdroid/fdroiddata/-/merge_requests/51053, fork
   `gitlab.com/doolieSoft/fdroiddata`, branche `compteurs`, fichier
   `metadata/be.suivicompteurs.app.yml`. Y reporter la recette de
   `fdroid/be.suivicompteurs.app.yml` (mise à jour elle aussi) :
   - `versionName`, `versionCode`, `CurrentVersion`, `CurrentVersionCode` ;
   - `commit` = **hash complet** de l'étiquette, jamais le nom de l'étiquette ;
   - fins de ligne **LF**, aucun commentaire `#`, champs dans l'ordre de
     `rewritemeta` (`Donate` avant `Liberapay`) ;
   - `AutoName` = nom de l'application dans le manifeste (`nom_lanceur`).
7. Si le développeur le demande : installer l'APK **libre** publié sur son
   téléphone (`adb install -r`, même clé : données conservées). Ne jamais
   toucher autrement au téléphone ; tester sur l'émulateur.

## Repères

- Variantes : `complet` (ML Kit, Play Store et GitHub) et `libre` (F-Droid,
  saisie manuelle). F-Droid retire les lignes de signature et la ligne ML Kit
  avant de compiler : garder `signingConfig = …` sur une seule ligne.
- R8 est actif sur les versions publiées : tester les écrans touchés sur
  l'émulateur avec une construction `Release`.
- Base Room : toute modification d'entité demande une migration écrite à la
  main et vérifiée contre `android/app/schemas/…/<version>.json`.
- Clé de publication (`android/cle-release.jks`, `android/keystore.properties`)
  hors du dépôt : ne jamais les afficher ni les committer.
