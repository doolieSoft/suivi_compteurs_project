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

## Façon de travailler

- Ne pas pousser ni publier sans l'accord du développeur. Quand il le
  demande, installer d'abord une construction d'essai sur son téléphone
  (libre, `Release`, signée avec la clé, `-PsuiviLienDons=…` pour garder le
  bouton de dons) avant de committer.
- Il rédige ses demandes en plusieurs messages courts : attendre la fin de
  l'idée avant d'agir, et revenir en arrière proprement s'il change d'avis.

## Structure

- `android/app/src/main/java/be/suivicompteurs/app/`
  - `moteur/` : calculs en Kotlin pur (ventilation, modèle thermique, prévisions,
    points à vérifier), sans Android ;
  - `analyse/` : écrans d'analyse (Compose) ; `gestion/` : maisons, compteurs,
    tarifs, événements (Compose) ; `classeur/` : import et export Excel ;
    `donnees/` : base Room ; `reseau/OpenMeteo.kt` : météo et recherche de ville.
  - Écrans classiques (XML) : `MainActivity` (accueil), `SaisieActivity`,
    `TableauDeBordActivity` (prévisions), `ReglagesActivity`.
- Textes en trois langues : `res/values`, `values-en`, `values-nl` (toujours
  les trois ; échapper les apostrophes `\'`).
- Icônes : Material Symbols (Apache 2.0) converties en vector drawables
  `res/drawable/ic_*.xml`, servant aux écrans XML comme à Compose.
- `fastlane/metadata/android/` : fiche des stores (titre, descriptions,
  captures, notes de version). `fdroid/` : recette F-Droid.
- `docs/` : page du projet (GitHub Pages) et politique de confidentialité.

## Tests

- `PariteReferenceTest` et `ImportClasseurTest` comparent le moteur à la
  référence figée du moteur Python d'origine (`src/test/resources/`) : un
  écart signifie que les chiffres changent, ce qui doit être délibéré.
- Émulateur : AVD `Medium_Phone` ; le clavier de l'émulateur intercepte parfois
  la saisie (`adb shell input text`), vérifier le champ après coup.

## Captures d'écran des stores

Refaire les 8 captures par langue (et les 4 de `docs/captures/`) quand
l'interface change. Données de démonstration : le script générateur dépend de
l'ancien site Django ; le lancer depuis une copie temporaire
(`git worktree add <tmp> v1.2.2`, avec `db.sqlite3` copié pour la météo),
importer les classeurs « Ma maison » / « My house » / « Mijn huis » sur
l'émulateur, recadrer les captures en 1080 × 2150 (sans barres système).

## Dons et réglages GitHub

- Variable GitHub `LIEN_DONS` = `https://dooliesoft.github.io/suivi_compteurs_project/#soutenir`
  (page qui propose Ko-fi en don unique et Liberapay en mensuel). L'AAB du
  Play Store n'a pas de bouton de dons (règles de Google).
- Secrets : `CLE_ANDROID_BASE64`, `CLE_ANDROID_MOTDEPASSE`, `CLE_ANDROID_ALIAS` ;
  envoi au Play Store seulement si `PLAY_SERVICE_ACCOUNT_JSON` existe.

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
