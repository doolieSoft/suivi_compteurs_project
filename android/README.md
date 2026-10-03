# Application Android

Application **autonome** de suivi des compteurs d'eau, de gaz, d'électricité et
de mazout. Toutes les données vivent sur le téléphone ; seule la météo
(degrés-jours) vient d'Internet, d'Open-Meteo. Aucun compte, aucun serveur.

Le moteur de calcul — ventilation des consommations, modèle thermique,
correction climatique, prévisions, coûts — est expliqué dans le
[README principal](../README.md). C'est le portage en Kotlin du moteur Python
du site d'origine, retiré depuis : voir « Parité avec la référence » plus bas.

## Ce que fait l'application

- relevé d'un compteur, avec **lecture automatique de l'index** sur la photo
  (le modèle ML Kit est embarqué : aucun réseau nécessaire) ;
- contrôle de cohérence : un index ne recule pas, une date ne porte qu'un relevé ;
- prévisions de l'année, d'un relevé annuel au suivant ou en année civile ;
- détail de chaque énergie : cumul, consommation mensuelle, signature
  énergétique, index, années comparées à climat normal, coûts ;
- comparaison des années, climat, justesse des prévisions passées ;
- gestion des maisons, compteurs (remplacement compris), relevés, tarifs et
  événements ;
- import et export Excel : l'export, enregistré où l'on veut (Google Drive
  compris), se réimporte tel quel et tient lieu de sauvegarde ;
- rappels de relevé ;
- interface en français, anglais et néerlandais.

Seule la mise à jour de la météo demande du réseau ; elle se fait d'elle-même
une fois par jour, ou en tirant l'écran d'accueil vers le bas.

## Construire

Requis : JDK 17 ou plus, et le SDK Android (plateforme 36, build-tools 35+).

```bash
cd android
./gradlew assembleCompletDebug      # avec lecture automatique de l'index (ML Kit)
./gradlew assembleLibreDebug        # entièrement libre, saisie à la main (F-Droid)
./gradlew testCompletDebugUnitTest
```

Le wrapper télécharge lui-même la version de Gradle attendue : rien à
installer au préalable.

Les APK sortent dans `app/build/outputs/apk/complet/debug/` (~50 Mo, dont
l'essentiel est le modèle de reconnaissance de texte) et
`app/build/outputs/apk/libre/debug/` (~11 Mo).

`local.properties` indique où se trouve le SDK ; adaptez-le si vous changez de
machine.

## Publier une version sur GitHub

### La façon normale : numéroter, puis pousser une étiquette

Dans `app/build.gradle.kts`, relever `versionCode` (+1) et `versionName`, puis :

```bash
git commit -am "Version 1.3"
git tag v1.3
git push origin main v1.3
```

GitHub Actions lance les tests, construit l'APK signé, le vérifie
et crée la publication avec l'APK attaché et des notes déduites des commits.
Comptez cinq minutes ; le déroulement est visible dans l'onglet **Actions**.

Les numéros ne sont écrits qu'à un endroit, `app/build.gradle.kts` :

| | Règle |
|---|---|
| `versionName` | l'étiquette sans son « v » : `v1.3` donne `1.3` |
| `versionCode` | +1 à chaque version : Android refuse d'installer un numéro qui recule |

F-Droid lit ces deux lignes pour repérer les nouvelles versions. Le workflow
refuse une étiquette qui ne correspond pas à `versionName`, plutôt que de
publier une version mal numérotée.

Pour les notes de version affichées par F-Droid, ajouter un fichier
`fastlane/metadata/android/<langue>/changelogs/<versionCode>.txt` par langue.

Le workflow se déclenche aussi à la main depuis l'onglet **Actions**, utile
pour rejouer une publication qui aurait échoué.

### Préparer les secrets, une fois pour toutes

La clé de signature ne peut pas vivre dans le dépôt. Le workflow la reçoit par
les secrets, à déposer sur
<https://github.com/doolieSoft/suivi_compteurs_project/settings/secrets/actions> :

| Secret | Valeur |
|---|---|
| `CLE_ANDROID_BASE64` | tout le contenu de `deploiement/cle-android-base64.txt` |
| `CLE_ANDROID_MOTDEPASSE` | la ligne `motDePasseDepot` de `android/keystore.properties` |
| `CLE_ANDROID_ALIAS` | `suivi-compteurs` |

Ces trois valeurs sont sur votre PC et **hors du dépôt**. GitHub les chiffre et
ne les réaffiche jamais ; elles n'apparaissent pas non plus dans les journaux
d'exécution.

Si `CLE_ANDROID_BASE64` manque, le workflow **s'arrête** au lieu de continuer.
C'est délibéré : sans la clé, la compilation retomberait silencieusement sur
celle de débogage et produirait un APK incapable de mettre à jour
l'application installée. Une vérification de signature, après la construction,
refuse d'ailleurs de publier un APK portant `CN=Android Debug`.

### Construire à la main, si besoin

```bash
cd android
./gradlew assembleCompletRelease assembleLibreRelease
```

Les fichiers sortent dans `app/build/outputs/apk/complet/release/` et `…/libre/release/`, signés
avec `cle-release.jks` dont le mot de passe est dans `keystore.properties`.

**Sauvegardez ces deux fichiers ailleurs que sur ce PC.** Les perdre interdit
définitivement toute mise à jour de l'application déjà installée : Android
refuse une signature différente, il faudrait désinstaller — donc perdre les
relevés et les réglages, sauf export préalable.

Sans eux, la compilation retombe sur la clé de débogage. C'est volontaire : le
dépôt reste constructible par qui le clone, sans pouvoir publier par mégarde un
APK mal signé.

## Installer sur un téléphone

Le plus simple, par USB, **sans passer par « sources inconnues »** :

1. Sur le téléphone : *Paramètres* → *À propos* → tapoter 7 fois sur **Numéro de
   build**, puis *Options pour les développeurs* → **Débogage USB**.
2. Brancher, autoriser l'ordinateur quand la fenêtre apparaît.
3. `adb install -r app/build/outputs/apk/complet/debug/app-complet-debug.apk`

Sinon, copiez `deploiement/suivi-compteurs.apk` sur le téléphone et ouvrez-le
depuis un gestionnaire de fichiers — Android demandera alors l'autorisation
d'installer depuis cette application.

## Premier démarrage

L'écran d'accueil propose deux départs :

- **Importer un classeur Excel** — un export de l'application, pour
  reprendre un historique existant ;
- **Créer ma maison** — puis ses compteurs, dans *Gérer maisons et compteurs*.
  La position de la maison (latitude, longitude) sert à récupérer sa météo.

## Organisation

```
app/src/main/java/be/suivicompteurs/app/
├── MainActivity.kt          liste des compteurs, import, export
├── SaisieActivity.kt        appareil photo, OCR, formulaire
├── TableauDeBordActivity.kt prévisions de chaque énergie
├── ReglagesActivity.kt      rappels et langue
├── moteur/                  calculs — sans Android
├── analyse/                 écrans d'analyse (Compose) et leurs graphiques
├── gestion/                 maisons, compteurs, relevés, tarifs (Compose)
├── classeur/                lecture et écriture des classeurs Excel
├── donnees/                 base locale (Room)
├── ocr/SelecteurIndex.kt    choix de l'index — la logique faillible, testée
├── reseau/OpenMeteo.kt      températures journalières
├── sync/Synchronisation.kt  mise à jour quotidienne de la météo
└── rappel/Rappels.kt        notifications locales

app/src/complet/…/ocr/LecteurIndex.kt   lecture de l'index avec ML Kit
app/src/libre/…/ocr/LecteurIndex.kt     sans lecture : saisie à la main
```

## Parité avec la référence

Le moteur Kotlin a été vérifié contre le moteur Python d'origine, à
l'identique sur des milliers de valeurs. Avant le retrait du Python, ses
résultats ont été figés sur des données fabriquées (aucune donnée personnelle) :
`app/src/test/resources/export-synthetique.xlsx` en entrée,
`reference-synthetique.json` en sortie.

`PariteReferenceTest` et `ImportClasseurTest` les relisent et exigent les mêmes
valeurs au milliardième près. Une modification du moteur qui fait échouer ces
tests change donc les chiffres : elle doit être délibérée, et la référence
régénérée en connaissance de cause.

## Pourquoi le choix de l'index est séparé du reste

Une photo de compteur contient un numéro de série, une année, un numéro
d'agrément. Et chaque suite de chiffres se lit de plusieurs façons, le compteur
n'imprimant pas toujours la virgule : « 24981625 » vaut aussi bien 24 981 625
que 24 981,625.

`SelecteurIndex` tranche avec deux connaissances, dans cet ordre :

1. **un index ne recule pas et progresse peu** — ce qui élimine les numéros de
   série, dont l'interprétation exigerait un bond démesuré ;
2. **à vraisemblance égale, la lecture la plus complète gagne** — entre
   « 24981 » et « 24981,625 », la seconde exploite tous les chiffres vus.

Cette logique est isolée de ML Kit pour être testable sans téléphone ni appareil
photo : voir `app/src/test/.../SelecteurIndexTest.kt`, qui reproduit les pièges
réels (numéro de série plausible, chiffre lu en trop, décimales manquantes,
compteur fraîchement posé).

La proposition reste **toujours** soumise à confirmation. Rien ne s'enregistre
sans que vous ayez vu la valeur.

## Limites connues

- **Afficheurs à sept segments** (compteurs électriques à cristaux liquides) :
  mal reconnus par les OCR génériques, entraînés sur des polices de caractères.
  Prévoyez de corriger à la main, ou utilisez *Sans photo*.
- **La date d'un nouveau relevé est celle du jour.** Pour un relevé fait
  a posteriori, corrigez sa date dans *Gérer maisons et compteurs*, sur la fiche
  du compteur.
- **Mazout** : traité comme un compteur dont l'index monte. Le suivi par niveau
  de cuve et livraisons, comme un réservoir de voiture, n'est pas encore géré.
- Le classeur Excel reste en français, quelle que soit la langue : son format
  ne doit pas dépendre du téléphone qui l'a écrit.

## Licence

© doolieSoft. Distribué sous licence GNU GPL version 3 ou ultérieure : voir
[LICENSE](../LICENSE). Vous pouvez utiliser, modifier et redistribuer
ce logiciel, y compris le vendre, à condition de publier sous la même licence
le code de toute version modifiée que vous distribuez.
