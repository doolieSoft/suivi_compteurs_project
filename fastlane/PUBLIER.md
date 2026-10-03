# Publier sur le Play Store

Tout ce qui se prépare dans le dépôt l'est déjà : textes de la fiche, captures,
icône, bannière, politique de confidentialité, et un workflow qui envoie chaque
version au Play Store. Restent les démarches qu'un compte Google seul peut
faire, une fois pour toutes.

## Ce que contient ce dossier

```
fastlane/
├── PUBLIER.md                    ce mémo
└── metadata/android/             une fiche par langue, lue par fastlane et par F-Droid
    ├── fr-FR/   title.txt  short_description.txt  full_description.txt
    │            images/icon.png             512 × 512
    │            images/featureGraphic.png   1024 × 500
    │            images/phoneScreenshots/    8 captures, 1080 × 2150
    ├── en-US/   …
    └── nl-NL/   …
```

Les captures montrent une maison de démonstration (« Ma maison », « My house »,
« Mijn huis ») : météo réelle de Liège, consommations simulées. Aucun relevé
personnel.

## 1. Politique de confidentialité

`docs/confidentialite.html`, en trois langues.

1. Remplacer `[adresse de contact]`, `[contact address]` et `[contactadres]`
   par l'adresse que vous voulez rendre publique, puis commiter.
2. GitHub → dépôt → **Settings › Pages** → *Source* : **Deploy from a branch**,
   branche `main`, dossier **`/docs`**.
3. Quelques minutes plus tard, la page est en ligne à
   <https://doolieSoft.github.io/suivi_compteurs_project/confidentialite.html> :
   c'est l'adresse à donner à la Play Console.

## 2. Créer l'application dans la Play Console

<https://play.google.com/console> → **Créer une application**.

| Champ | Valeur |
|---|---|
| Nom | Suivi compteurs |
| Langue par défaut | Français (France) – fr-FR |
| Application ou jeu | Application |
| Gratuite ou payante | Gratuite |

## 3. Signature : fournir votre propre clé

À la première version, la Console propose **Play App Signing**. Choisir
**d'utiliser votre propre clé** (« Use a different key » / « Exporter et
importer une clé ») plutôt que celle générée par Google : sans cela, un
téléphone qui a l'application installée depuis GitHub ne pourrait pas passer à
la version du Play Store sans désinstaller, donc sans perdre ses données.

La Console fournit un outil (`pepk.jar`) et la commande à lancer sur
`android/cle-release.jks` ; le mot de passe et l'alias (`suivi-compteurs`) sont
dans `android/keystore.properties`. Gardez la même clé comme clé d'importation.

## 4. Premier envoi, à la main

L'API de Google ne sait pas créer une application, seulement la mettre à jour.

```bash
cd android
./gradlew bundleCompletRelease
```

Envoyer `app/build/outputs/bundle/completRelease/app-complet-release.aab` dans
**Tests › Test interne › Créer une version**. Les versions suivantes partiront
d'elles-mêmes (étape 8).

## 5. Fiche du Play Store

**Présence sur le Play Store › Fiche principale** : copier les textes de
`metadata/android/fr-FR/`, puis **Ajouter des traductions** pour `en-US` et `nl-NL`
avec les textes de leurs dossiers. Chaque langue a son icône, sa bannière et
ses captures.

- **Catégorie** : Outils (ou Maison et décoration).
- **Adresse de contact** : celle de la politique de confidentialité.
- **Site web** (Paramètres de la fiche › Coordonnées) : la page du projet,
  <https://dooliesoft.github.io/suivi_compteurs_project/>. Les textes de la
  fiche n'ont pas de champ pour elle : elle se saisit dans la Console.

## 6. Questionnaires (« Contenu de l'application »)

**Politique de confidentialité** : l'adresse de l'étape 1.

**Annonces** : l'application ne contient pas d'annonces.

**Accès à l'application** : toutes les fonctionnalités sont disponibles sans
identifiants.

**Classification du contenu** : catégorie *Utilitaires / productivité* ;
répondre **non** à tout (violence, sexualité, langage, substances, jeux
d'argent, interactions entre utilisateurs, partage de la position…). Résultat
attendu : PEGI 3 / Tout public.

**Public cible** : 18 ans et plus (ou 13+). L'application ne vise pas les
enfants.

**Sécurité des données** :

| Question | Réponse |
|---|---|
| L'application collecte-t-elle ou partage-t-elle des données ? | **Oui** |
| Les données sont-elles chiffrées en transit ? | **Oui** (HTTPS uniquement) |
| Les utilisateurs peuvent-ils demander la suppression ? | **Oui** : suppression dans l'application ou désinstallation |

| Type de données | Collectée | Partagée | Raison | Facultative |
|---|---|---|---|---|
| Position approximative | Oui | **Oui**, avec Open-Meteo | Fonctionnalités de l'application (météo) | Oui |
| Plantages, diagnostics, autres données de performance | Oui (ML Kit) | Non | Analyse | Non |

Précisions utiles :
- La « position » est celle de la maison, saisie par l'utilisateur, pas celle du
  téléphone : l'application ne demande pas l'autorisation de localisation. Elle
  part chez Open-Meteo sans aucun identifiant.
- Les photos de compteurs ne quittent pas le téléphone : **ne pas** les
  déclarer.
- ML Kit (reconnaissance de texte) tourne sur le téléphone ; Google indique
  qu'il peut recevoir des diagnostics de performance, non liés à l'identité.

**Applications gouvernementales, financières, santé** : non.

## 7. Test fermé, puis production

Pour un compte personnel récent, Google exige un **test fermé avec au moins
12 testeurs pendant 14 jours consécutifs** avant d'ouvrir la production :

1. **Tests › Test fermé** : créer une piste, y promouvoir la version du test
   interne, ajouter les adresses e-mail des testeurs.
2. Les testeurs acceptent l'invitation et installent l'application.
3. Après 14 jours, **Demander l'accès à la production**.

## 8. Envoi automatique depuis GitHub

Une fois l'application créée, chaque étiquette `v…` envoie aussi l'AAB au
Play Store :

1. **Google Cloud Console** : créer un projet, y activer l'API
   **Google Play Android Developer API**.
2. Dans ce projet : **IAM › Comptes de service › Créer**, puis **Clés ›
   Ajouter une clé › JSON** : un fichier se télécharge.
3. **Play Console › Utilisateurs et autorisations › Inviter** l'adresse du
   compte de service, avec la permission **Publier en test** (et
   **en production** plus tard) sur cette application.
4. **GitHub › Settings › Secrets and variables › Actions** :
   - secret `PLAY_SERVICE_ACCOUNT_JSON` = tout le contenu du fichier JSON ;
   - variable `PLAY_STATUT` = `draft` tant que l'application n'a jamais été
     publiée, puis `completed` ;
   - variable `PLAY_PISTE` = `internal` (défaut), `alpha` pour le test fermé,
     `production` le moment venu.

Ensuite, comme aujourd'hui :

```bash
git tag v1.2.2
git push origin v1.2.2
```

Le workflow teste, construit l'APK (publié sur GitHub) et l'AAB (envoyé au Play
Store). Sans le secret, l'envoi au Play Store est simplement sauté.

## Numéros de version

`versionCode` et `versionName` sont écrits dans `android/app/build.gradle.kts`,
à relever avant chaque étiquette (voir `android/README.md`). Le Play Store
refuse un `versionCode` déjà envoyé : +1 à chaque version suffit.

## À surveiller

- **Niveau d'API** : Google relève chaque année le minimum (API 36 depuis le
  31 août 2026). L'application cible l'API 36.
- **Open-Meteo** : gratuit pour un usage non commercial. Une application
  gratuite sans publicité convient ; payante ou avec publicité, il faudrait leur
  abonnement. La mention « Données météo : Open-Meteo.com (CC BY 4.0) » figure
  dans les réglages et la fiche.
