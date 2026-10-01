# Application Android

Client mobile du suivi de compteurs. Son rôle : **relever un compteur sans
dépendre du serveur**, puis transmettre quand il redevient joignable.

L'analyse n'est pas réimplémentée ici. Pas un degré-jour, pas un modèle
thermique, pas une prévision : tout cela reste en Python côté serveur. Le Kotlin
saisit, stocke, transmet et affiche.

## Ce qui fonctionne serveur éteint

- liste des compteurs et dernier index connu ;
- photographie du compteur ;
- **lecture automatique de l'index** — le modèle ML Kit est embarqué dans l'APK ;
- contrôle de cohérence (un index ne recule pas) ;
- enregistrement du relevé dans la base locale ;
- plusieurs relevés d'affilée, chacun tenant compte du précédent ;
- rappels de relevé ;
- consultation du dernier instantané d'analyse reçu.

Ce qui attend le serveur : l'arrivée du relevé dans l'archive, et le recalcul
des prévisions avec les nouvelles valeurs.

## Construire

Requis : JDK 17 ou plus, et le SDK Android (plateforme 35, build-tools 35+).

```bash
cd android
gradle assembleDebug          # ou ./gradlew si vous ajoutez le wrapper
gradle testDebugUnitTest
```

L'APK sort dans `app/build/outputs/apk/debug/app-debug.apk` (~48 Mo, dont
l'essentiel est le modèle de reconnaissance de texte).

`local.properties` indique où se trouve le SDK ; adaptez-le si vous changez de
machine.

## Installer sur un téléphone

Le plus simple, par USB, **sans passer par « sources inconnues »** :

1. Sur le téléphone : *Paramètres* → *À propos* → tapoter 7 fois sur **Numéro de
   build**, puis *Options pour les développeurs* → **Débogage USB**.
2. Brancher, autoriser l'ordinateur quand la fenêtre apparaît.
3. `adb install -r app/build/outputs/apk/debug/app-debug.apk`

Sinon, copiez `deploiement/suivi-compteurs.apk` sur le téléphone et ouvrez-le
depuis un gestionnaire de fichiers — Android demandera alors l'autorisation
d'installer depuis cette application.

## Premier démarrage

Menu **⋮** → **Réglages** :

| Champ | Serveur domestique | Hébergeur en ligne |
|---|---|---|
| Adresse | `192.168.0.10` | `https://VOTRENOM.pythonanywhere.com` |
| Jeton | `JETON_API` de `config/settings.py` | `SUIVI_JETON_API` du `.env` |

Une adresse IP nue reçoit automatiquement `http://` et le port 8000 ; un nom de
domaine reçoit `https://` sans port. **Tester la connexion** avant d'enregistrer.

## Organisation

```
app/src/main/java/be/suivicompteurs/app/
├── MainActivity.kt          liste des compteurs, état de synchronisation
├── SaisieActivity.kt        appareil photo, OCR, formulaire
├── ConsultationActivity.kt  WebView, et repli sur l'instantané hors ligne
├── ReglagesActivity.kt      adresse, jeton, rappels
├── Reglages.kt              préférences et normalisation de l'adresse
├── donnees/                 base locale (Room) : compteurs, file d'attente
├── ocr/
│   ├── LecteurIndex.kt      appel à ML Kit
│   └── SelecteurIndex.kt    choix de l'index — la logique faillible, testée
├── reseau/Api.kt            client HTTP
├── sync/Synchronisation.kt  vidage de la file, rapatriement de l'analyse
└── rappel/Rappels.kt        notifications locales
```

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
  Prévoyez de corriger à la main, ou utilisez *Saisir sans photo*.
- **La date du relevé est celle du jour**, non modifiable depuis le téléphone.
  Corrigez depuis l'interface web si vous relevez a posteriori.
- L'application n'affiche pas les graphiques hors ligne, seulement les chiffres
  clés de l'instantané.
