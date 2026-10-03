# Suivi des compteurs

Application Django de suivi des consommations d'eau, de gaz et d'électricité de
deux maisons, alimentée par le classeur `compteur.xlsx`. Elle produit des
graphiques, une prévision de fin d'année et une comparaison inter-années
**corrigée des degrés-jours**, afin qu'un hiver rigoureux ne soit pas confondu
avec une dérive de consommation.

## Télécharger l'application Android

**[⬇ Dernière version](https://github.com/doolieSoft/suivi_compteurs_project/releases/latest)**
· **[Page du projet](https://dooliesoft.github.io/suivi_compteurs_project/)**

Deux fichiers sont attachés à chaque publication : `suivi-compteurs.apk`, avec
lecture automatique de l'index, et `suivi-compteurs-libre.apk`, sans composant
propriétaire, où l'index se saisit à la main. Les releases
stockent les binaires hors de l'historique Git : le dépôt reste léger, et
chaque version garde son lien.

> Le dépôt étant privé, ce lien ne fonctionne qu'une fois connecté à GitHub
> avec un compte qui y a accès.

Pour installer : ouvrez le fichier téléchargé depuis le gestionnaire de
fichiers du téléphone, et autorisez l'installation depuis cette application
quand Android le demande. Par USB, `adb install -r suivi-compteurs.apk` évite
cette autorisation.

Ce que l'application permet, serveur éteint : photographier un compteur, lire
l'index automatiquement, l'enregistrer, et consulter la dernière analyse reçue.
Détails dans [android/README.md](android/README.md).

## Démarrage

```bash
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt

python manage.py migrate
python manage.py import_excel          # lit compteur.xlsx à la racine
python manage.py sync_degres_jours     # télécharge l'historique météo
python manage.py runserver
```

L'application est alors sur <http://127.0.0.1:8000/>.

Pour accéder à l'administration : `python manage.py createsuperuser`.

## Les pages

| Page | Ce qu'on y trouve |
|---|---|
| **Tableau de bord** | Une tuile par énergie et par maison : prévision de fin d'année, évolution vs l'an dernier, alertes. |
| **Détail d'une énergie** | Consommation annuelle brute et corrigée, cumul depuis le 1ᵉʳ janvier, consommation mensuelle, signature énergétique, index bruts, coûts. |
| **Comparaison** | Toutes les énergies côte à côte, avec la rigueur climatique de chaque année. |
| **Prévisions** | Projection au 31 décembre, méthode employée et fourchette. |
| **Climat** | Degrés-jours par année et écart à la normale. Bouton de mise à jour. |
| **Relevés** | Liste filtrable, saisie, correction, suppression. |

## Comment les chiffres sont calculés

### Des index aux consommations

Un relevé enregistre un **index**, pas une consommation. Entre deux index
peuvent s'écouler sept jours comme trois cents. Pour comparer des années, chaque
écart d'index est donc réparti sur les jours qu'il couvre — c'est la
*ventilation* (`services/consommation.py`).

Répartir uniformément suffit pour l'eau. Pour le gaz, c'est faux : 300 m³
consommés entre octobre et mars ne se répartissent pas à parts égales, ils
suivent le froid.

### Le modèle thermique

L'application ajuste donc, compteur par compteur, une droite :

```
consommation du jour = base + k × degrés-jours du jour
```

- **`base`** couvre ce qui ne dépend pas de la météo : eau chaude sanitaire,
  cuisson, veilles.
- **`k`** est la sensibilité au froid, en m³ (ou kWh) par degré-jour. C'est,
  en substance, le coût thermique du bâtiment.

L'ajustement se fait par moindres carrés pondérés par la durée des périodes :
une période de 300 jours pèse davantage qu'une de 7. Il n'est retenu que si
R² ≥ 0,5 ; sinon l'application retombe sur une répartition uniforme et le dit
explicitement dans l'interface.

Le modèle n'est ajusté **que sur le gaz et l'électricité**. La consommation
d'eau suit l'occupation du logement, pas la météo : une corrélation apparente
sur quelques années y serait fortuite, et la « corriger » fausserait tout.

### La correction climatique

Une fois `k` connu, chaque année est ramenée à un hiver moyen :

```
consommation corrigée = consommation mesurée + k × (DJ normal − DJ réel)
```

Une année plus froide que la normale voit donc sa consommation revue à la
baisse, et inversement. C'est cette valeur corrigée qu'il faut comparer d'une
année à l'autre.

Le **DJ normal** est la moyenne des dix dernières années pour chaque jour
calendaire (`ANNEES_NORMALE_CLIMATIQUE` dans `config/settings.py`).

### Les prévisions

Le réalisé n'est jamais réécrit : seuls les jours postérieurs au dernier relevé
sont estimés.

- **Modèle thermique** (quand il est fiable) : les jours restants sont estimés
  avec les degrés-jours *normaux* de la saison. Un automne doux n'est donc pas
  prolongé dans le mois de janvier suivant.
- **Profil saisonnier** (sinon) : on mesure, sur les années complètes passées,
  quelle fraction de l'année est déjà écoulée en consommation, et on divise le
  réalisé par cette fraction.

La fourchette affichée reflète l'écart entre années de référence, non
l'incertitude météorologique à venir.

### Les degrés-jours

Un degré-jour vaut `max(0, base − température moyenne du jour)`. La base est de
**16,5 °C**, convention belge (DJ 16.5/16.5).

Les températures viennent d'[Open-Meteo](https://open-meteo.com/), gratuit et
sans clé d'API : l'historique (réanalyse ERA5, depuis 1940) complété par
l'endpoint de prévision pour les jours récents, que l'archive publie avec
environ cinq jours de retard.

```bash
python manage.py sync_degres_jours                      # complète les manques
python manage.py sync_degres_jours --ecraser            # recalcule tout
python manage.py sync_degres_jours --debut 2014-01-01
```

Changer `BASE_DEGRES_JOURS` ou la base d'une station impose un `--ecraser`.

## L'import Excel

Le classeur mêle, dans une même feuille, des index annuels, des relevés
détaillés et des calculs intermédiaires. La constante `BLOCS` de
`suivi/management/commands/import_excel.py` décrit donc explicitement où lire
quoi. **Si vous réorganisez le classeur, ajustez cette constante.**

Deux automatismes évitent d'avoir à décrire les cas particuliers :

- un index qui **recule** signale un remplacement de compteur : la série est
  découpée en deux `Compteur` distincts, ce qui empêche de calculer un écart
  absurde à la charnière — c'est ce qui s'est produit sur un compteur d'eau
  remplacé en cours de suivi, l'index passant de 851,1 m³ à 21,5 m³ ;
- les annotations texte voisines d'un relevé (« index envoyé », « changement de
  douche ») deviennent des commentaires, et celles qui évoquent des travaux
  deviennent des **événements** tracés dans l'interface.

L'import est **rejouable** : un relevé déjà connu est mis à jour, jamais
dupliqué.

```bash
python manage.py import_excel                  # compteur.xlsx à la racine
python manage.py import_excel chemin/vers.xlsx
python manage.py import_excel --purger         # repart de zéro
```

## Points d'attention sur les données importées

Ces éléments viennent du classeur tel quel et méritent une vérification dans
l'administration :

1. **Événements datés automatiquement.** Certaines annotations du classeur
   servent d'en-tête à une colonne de calcul plutôt que de commentaire sur une
   ligne. C'est le cas d'« isolation du toit », à qui l'import attribue la date
   de la dernière ligne de la feuille Gaz. Chaque événement importé porte une
   description rappelant la cellule d'origine — corrigez la date au besoin.
2. **Index 2014 et 2015 identiques** pour l'eau et le gaz de l'ancienne maison : la
   consommation 2015 ressort donc à 0. L'application le signale comme « index
   inchangé », et exclut ce point de l'ajustement du modèle.
3. **Tarifs.** La feuille « Prix par année » contient des *acomptes mensuels*,
   pas des prix unitaires. Ils sont importés dans le champ « abonnement
   mensuel », et le prix unitaire reste à 0. Renseignez le prix au m³ / au kWh
   de chaque contrat dans l'administration pour que les coûts soient exploitables.
4. **Gaz en m³.** Les index gaz sont traités comme des mètres cubes, avec un
   coefficient de conversion de 11 kWh/m³ (`COEF_CONVERSION_GAZ_KWH`) modifiable
   par compteur dans l'administration.
5. **Compteur « parents ».** La feuille Gaz contient aussi les index du compteur
   des parents ; ils ne sont pas importés.

## Téléphone et hébergement

Deux ajouts permettent d'utiliser l'application depuis un téléphone.

### Application Android

Un client mobile (`android/`) qui **relève un compteur sans dépendre du
serveur** : photo, lecture automatique de l'index, contrôle de cohérence et
enregistrement se font sur l'appareil, puis le relevé part dès que le serveur
redevient joignable. Voir [android/README.md](android/README.md).

L'APK prêt à installer est dans `deploiement/suivi-compteurs.apk`.

Elle s'appuie sur quatre points d'entrée JSON, protégés par un jeton partagé
transmis dans l'en-tête `X-Jeton` :

| Adresse | Rôle |
|---|---|
| `GET /api/etat/` | compteurs à relever et derniers index |
| `POST /api/releves/` | un relevé, avec photo éventuelle |
| `POST /api/synchroniser/` | la file d'attente en un seul envoi |
| `GET /api/instantane/` | l'analyse complète, à garder hors ligne |

Le jeton se règle par `SUIVI_JETON_API`. L'instantané est mis en cache tant que
les données n'ont pas changé : sans cela, chaque synchronisation du téléphone
recalculerait douze ans de ventilation.

### Installation depuis le navigateur

L'application web est installable telle quelle sur un téléphone : ouvrez-la dans
Chrome et choisissez *Installer l'application*. Les pages déjà consultées
restent lisibles quand le serveur ne répond plus.

### Hébergement en ligne

Pour s'affranchir du PC, l'application peut être hébergée. La marche à suivre
complète pour PythonAnywhere — compte gratuit compris — est dans
[deploiement/GUIDE.md](deploiement/GUIDE.md).

La configuration distingue les deux environnements par des variables
d'environnement, lues depuis un fichier `.env` placé à la racine (modèle dans
`deploiement/env.exemple`). Les valeurs par défaut sont celles du poste local :
sans `.env`, rien ne change.

Pour fabriquer l'archive à téléverser, données comprises :

```bash
python deploiement/preparer_archive.py
```

## Organisation du code

```
config/            paramètres Django (locaux et production)
android/           application mobile (Kotlin)
deploiement/       guide d'hébergement, modèle .env, archive, APK
suivi/
  models.py        Maison, Compteur, Relevé, Événement, Tarif, StationMétéo, DegréJour
  services/
    degres_jours.py  Open-Meteo, calcul et normales climatiques
    consommation.py  ventilation, modèle thermique, normalisation, anomalies
    previsions.py    projection de fin d'année
    couts.py         valorisation en euros
  management/commands/
    import_excel.py
    sync_degres_jours.py
  templates/suivi/
  static/suivi/    app.css, charts.js, Chart.js (embarqué)
  tests/
```

Chart.js est **embarqué** plutôt que chargé d'un CDN : l'application doit rester
utilisable hors ligne, et sa version ne doit pas changer sous les pieds.

## Tests

```bash
python manage.py test suivi
```

Les tests fabriquent un climat déterministe et une consommation journalière
connue, en déduisent des relevés d'index, puis vérifient que l'application
retrouve la vérité de départ : conservation des volumes, paramètres du modèle,
neutralité de la correction climatique, absence de faux positifs saisonniers.
Aucun appel réseau n'est nécessaire.

## Saisir un nouveau relevé

Le plus simple est la page **Saisir**. Le formulaire refuse un index inférieur
au relevé précédent ou supérieur au suivant — sauf s'il s'agit d'un compteur
remplacé, auquel cas il faut d'abord créer le nouveau compteur dans
l'administration, avec sa date de pose.

## Licence

© doolieSoft. Distribué sous licence GNU GPL version 3 ou ultérieure : voir
[LICENSE](LICENSE). Vous pouvez utiliser, modifier et redistribuer
ce logiciel, y compris le vendre, à condition de publier sous la même licence
le code de toute version modifiée que vous distribuez.
