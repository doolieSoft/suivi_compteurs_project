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
6. **F-Droid** : rien à faire. L'application y est publiée depuis le 27 octobre
   2026 ; F-Droid reprend seul les étiquettes `vX.Y.Z`, sous quelques jours.
   Seul un changement de recette (`metadata/be.suivicompteurs.app.yml` dans
   `gitlab.com/fdroid/fdroiddata`) demande une merge request.
7. Le téléphone du développeur a la version **Play Store** (signée par la clé
   de Google) : aucun APK construit ici ne s'installe par-dessus. Ne jamais
   la désinstaller (ses relevés seraient perdus) ; elle se met à jour par le
   Play Store.

## Façon de travailler

- Ne pas pousser ni publier sans l'accord du développeur. Quand il le
  demande, installer d'abord les versions d'essai sur son téléphone avant de
  committer (voir « Tests »).
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
- Textes en trois langues : `res/values` (anglais, langue de secours des
  langues non prises en charge), `values-fr`, `values-nl` (toujours les trois ;
  échapper les apostrophes `\'`).
- Icônes : Material Symbols (Apache 2.0) converties en vector drawables
  `res/drawable/ic_*.xml`, servant aux écrans XML comme à Compose.
- `fastlane/metadata/android/` : fiche des stores (titre, descriptions,
  captures, notes de version), lue telle quelle par F-Droid.
  `fastlane/play/<langue>.txt` : la description complète à coller dans la Play
  Console, sans aucune mention de F-Droid ; la tenir à jour avec
  `full_description.txt`. `fdroid/` : recette F-Droid telle que fusionnée (la
  référence est dans `fdroiddata`).
- `docs/` : page du projet (GitHub Pages) et politique de confidentialité.

## Tests

- `PariteReferenceTest` et `ImportClasseurTest` comparent le moteur à la
  référence figée du moteur Python d'origine (`src/test/resources/`) : un
  écart signifie que les chiffres changent, ce qui doit être délibéré.
- Versions d'essai (type de construction `essai`) : comme une version publiée
  (R8 compris), clé de débogage, identifiant `be.suivicompteurs.app.essai.<variante>`,
  données à part. `assembleCompletEssai` (« Essai caméra », lecture ML Kit) et
  `assembleLibreEssai` (« Essai libre », comme F-Droid) ; elles s'installent à
  côté de la version Play Store (`adb install -r`, données d'essai conservées).
  Ajouter `-PsuiviLienDons=…` pour voir le bouton de dons.
- Téléphone (moto g86) : par Wi-Fi, débogage sans fil. Associé une fois
  (`adb pair`) ; à chaque séance, le développeur donne l'adresse IP:port à
  `adb connect`. N'y faire qu'installer les versions d'essai, puis
  `adb disconnect`.
- Utiliser l'adb du SDK (`~/Android/Sdk/platform-tools/adb`) : un autre adb
  sur la machine se dispute le serveur (« device offline »).
- Émulateur : AVD `Essai_API34` (Android 34, léger ; l'AVD Android 37 sature
  ce PC de 8 Go). KVM requis. Changer la langue du système :
  `adb shell cmd locale set-device-locale de-DE` (l'interface redémarre :
  attendre). Le clavier de l'émulateur intercepte parfois la saisie
  (`adb shell input text`), vérifier le champ après coup.

## Captures d'écran des stores

Refaire les 8 captures par langue (et les 4 de `docs/captures/`) quand
l'interface change. Données de démonstration : le script générateur dépend de
l'ancien site Django ; le lancer depuis une copie temporaire
(`git worktree add <tmp> v1.2.2`, avec `db.sqlite3` copié pour la météo),
importer les classeurs « Ma maison » / « My house » / « Mijn huis » sur
l'émulateur, recadrer les captures en 1080 × 2150 (sans barres système).
Le README affiche les huit captures françaises de `fastlane/`, légendées : il suit
de lui-même.

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
