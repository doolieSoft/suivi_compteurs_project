# Déployer sur PythonAnywhere

Écrit pour : vous, en autonomie, sans connaissance préalable de PythonAnywhere.

À la fin, votre application sera à l'adresse `https://cimeclean.pythonanywhere.com`,
accessible du PC comme du téléphone, où que vous soyez, PC éteint.

Comptez une quarantaine de minutes la première fois.

---

## Vos valeurs, déjà préparées

Votre compte est `cimeclean`, votre adresse sera
**https://cimeclean.pythonanywhere.com**.

Deux fichiers sont déjà renseignés **sur votre PC**, secrets générés compris.
Ils ne partent pas sur GitHub — c'est précisément leur raison d'être :

| Fichier, sur votre PC | Destination |
|---|---|
| `deploiement/env.cimeclean` | contenu à recopier dans `.env` sur le serveur |
| `deploiement/wsgi_cimeclean.py` | contenu à coller dans le fichier WSGI (onglet Web) |

Le jeton à saisir dans l'application Android se lit dans
`deploiement/env.cimeclean`, à la ligne `SUIVI_JETON_API`. **Il n'est
volontairement écrit nulle part ailleurs** : ce guide, lui, part sur GitHub.

```bash
grep SUIVI_JETON_API deploiement/env.cimeclean
```

Les étapes 4 et 6 ci-dessous s'en trouvent raccourcies ; elles restent décrites
au cas où vous voudriez regénérer vos secrets.

### Trois choses à régler d'abord

**1. Votre site est expiré.** Onglet **Web**, cliquez sur
*Run until 1 month from today* avant toute chose. Sans cela, rien ne répondra.

**2. Vous avez déjà une application web, et l'offre gratuite n'en autorise
qu'une.** Déployer le suivi de compteurs sur `cimeclean.pythonanywhere.com`
**remplacera** ce qui s'y trouve. Si ce site vous sert encore, il faudra
choisir : passer à l'offre payante, ou utiliser un autre compte.

**3. Vérifiez si votre compte a droit à MySQL et aux tâches planifiées.** Les
comptes gratuits créés **avant le 15 janvier 2026** les conservent. Regardez
l'onglet **Databases** : s'il propose de créer une base MySQL sans réclamer un
abonnement, vous les avez — et dans ce cas :

- décommentez le bloc MySQL de `.env` (plus robuste que SQLite sur le système
  de fichiers distribué de PythonAnywhere) ;
- onglet **Tasks**, programmez une tâche quotidienne, ce qui rend la mise à
  jour des degrés-jours automatique :

```
cd /home/cimeclean/suivi_compteurs_project && /home/cimeclean/.virtualenvs/compteurs/bin/python manage.py sync_degres_jours
```

---

## Ce que l'offre gratuite donne, et ce qu'elle ne donne pas

PythonAnywhere a modifié son offre gratuite le **15 janvier 2026**. Un compte
créé après cette date dispose de :

| | |
|---|---|
| Espace disque | 512 Mo — votre base en fait 1, les photos ~15 Ko pièce |
| Processeur | 100 secondes par jour — soit ~30 consultations complètes |
| Application web | 1, à l'adresse `cimeclean.pythonanywhere.com`, en HTTPS |
| Base de données | **SQLite seulement** (MySQL est passé en offre payante) |
| Tâches planifiées | **aucune** (également passées en offre payante) |
| Accès internet sortant | restreint à une liste blanche — **Open-Meteo y figure** |

Deux conséquences pratiques :

1. **Les degrés-jours ne se mettront pas à jour tout seuls.** Vous les
   actualiserez d'un bouton sur la page *Climat*. Ce n'est pas gênant : ils ne
   servent qu'au moment du calcul, et un retard de quelques jours ne change
   rien à une prévision annuelle.
2. **L'application web expire au bout d'un mois.** PythonAnywhere envoie un
   courriel ; un clic sur *Run until 3 months from today* dans l'onglet **Web**
   la prolonge. Si vous l'oubliez, l'application s'arrête mais **rien n'est
   perdu** : vos données restent, et un clic la relance.

Si l'une des deux vous pèse, l'offre payante la plus basse (environ 5 $/mois)
lève tout cela : internet libre, tâches planifiées, MySQL, pas d'expiration.

---

## Étape 1 — Le compte

Déjà fait : vous êtes `cimeclean`. Passez à l'étape 2.

> Si vous repartez un jour d'un autre compte, créez-le sur
> <https://www.pythonanywhere.com/registration/register/beginner/> — le nom
> d'utilisateur devient l'adresse web — puis remplacez `cimeclean` partout dans
> ce guide, ainsi que dans `env.cimeclean` et `wsgi_cimeclean.py`.

---

## Étape 2 — Publier le projet sur GitHub

Le dépôt ne transporte que du **code**. Trois choses en sont volontairement
exclues, et le resteront :

| Exclu | Pourquoi |
|---|---|
| `.env` | il porte vos secrets ; il se crée à la main sur chaque machine |
| `compteur.xlsx`, `donnees.json`, `db.sqlite3` | douze ans de consommation, ce sont des données personnelles |
| `deploiement/*.apk`, `.venv/`, `.outils/` | des binaires lourds, reconstructibles |

### Sur votre PC

Tout est déjà en place : dépôt initialisé, premier enregistrement fait, branche
`main`, et le distant pointe sur
`https://github.com/doolieSoft/suivi_compteurs_project.git`.

Il ne reste qu'à envoyer, **depuis votre propre terminal** — pas depuis une
console web, car le gestionnaire d'identifiants Windows a besoin d'ouvrir une
fenêtre de connexion GitHub :

```bash
cd C:\Users\c158492\ProjetPerso\suivi_compteurs_project
git push -u origin main
```

Une fenêtre s'ouvre, vous vous connectez à GitHub, et c'est fait. Les envois
suivants ne redemanderont rien.

> Si GitHub refuse en invoquant des historiques divergents, c'est que le dépôt
> a été créé avec un README ou un `.gitignore`. Le plus simple est alors de le
> supprimer et d'en recréer un **entièrement vide**.

### Sur PythonAnywhere — dépôt privé

Votre dépôt est privé : un clonage anonyme échoue avec
`Password authentication is not supported for Git operations`. GitHub a
supprimé l'authentification par mot de passe en 2021 ; il faut un **jeton
d'accès personnel**.

Et le SSH n'est pas une option : les comptes gratuits n'atteignent internet
qu'en HTTP(S), donc `git@github.com:…` ne fonctionnera jamais.

**1. Créez le jeton** sur
<https://github.com/settings/personal-access-tokens/new> :

| Champ | Valeur |
|---|---|
| Token name | `pythonanywhere-lecture` |
| Expiration | 1 an, ou *No expiration* si vous préférez ne pas y revenir |
| Repository access | *Only select repositories* → `suivi_compteurs_project` |
| Permissions → Repository permissions → **Contents** | **Read-only** |

Ne cochez rien d'autre. Un jeton qui ne sait que lire un seul dépôt ne peut
rien casser s'il fuite.

Copiez-le immédiatement : GitHub ne le réaffichera plus.

**2. Clonez**, en remplaçant `VOTRE_JETON` :

```bash
git clone https://doolieSoft:VOTRE_JETON@github.com/doolieSoft/suivi_compteurs_project.git suivi_compteurs_project
cd suivi_compteurs_project
ls
```

Vous devez voir `manage.py`, `config/`, `suivi/`, `requirements.txt`.

Les `git pull` ultérieurs ne redemanderont rien : le jeton est mémorisé dans
`.git/config`. C'est précisément pourquoi il doit être restreint à la lecture
de ce seul dépôt — il y est inscrit en clair.

> **Vous préférez éviter le jeton ?** Passez le dépôt en public : *Settings* →
> *General* → tout en bas, *Change repository visibility*. J'ai vérifié qu'il
> ne contient aucun secret — ni jeton, ni mot de passe, ni vos relevés. La
> seule information qu'il révèle est l'adresse `cimeclean.pythonanywhere.com`,
> qui est de toute façon une URL publique. Le clonage devient alors :
>
> ```bash
> git clone https://github.com/doolieSoft/suivi_compteurs_project.git suivi_compteurs_project
> ```

---

## Étape 3 — Installer les dépendances

Toujours dans la console Bash :

```bash
mkvirtualenv --python=/usr/bin/python3.11 compteurs
pip install -r requirements.txt
```

Si `python3.11` n'existe pas, listez ce qui est disponible avec
`ls /usr/bin/python3.*` et prenez la version la plus récente (3.10 minimum,
Django 5.2 l'exige).

L'invite de commande affiche désormais `(compteurs)` : l'environnement est
actif. Pour y revenir plus tard : `workon compteurs`.

---

## Étape 4 — Les réglages de production

Ce fichier ne vient pas du dépôt : il porte vos secrets, Git l'ignore
délibérément. Il se crée donc une fois sur le serveur.

Sur votre PC, ouvrez `deploiement/env.cimeclean` et copiez tout son contenu.
Puis, dans la console PythonAnywhere :

```bash
nano .env
```

Collez (`Ctrl+Maj+V` dans la console web), puis `Ctrl+O`, `Entrée`, `Ctrl+X`.

Vérifiez d'un coup d'œil :

```bash
cat .env
```

Vous devez y lire `SUIVI_DEBUG=0` et l'adresse `cimeclean.pythonanywhere.com`.

> `SUIVI_DEBUG=0` n'est pas optionnel. Avec `1`, la moindre erreur afficherait
> votre code et vos réglages à qui visite l'adresse.

<details>
<summary>Si vous préférez générer vos propres secrets</summary>

```bash
python -c "import secrets; print('SUIVI_CLE=' + secrets.token_urlsafe(50))"
python -c "import secrets; print('SUIVI_JETON_API=' + secrets.token_urlsafe(24))"
nano .env
```

Reportez les deux valeurs dans le fichier, puis le nouveau jeton dans les
Réglages de l'application Android. Enregistrez avec `Ctrl+O`, `Entrée`,
`Ctrl+X`.
</details>

> `SUIVI_DEBUG=0` n'est pas optionnel. Avec `1`, la moindre erreur afficherait
> votre code et vos réglages à qui visite l'adresse.

---

## Étape 5 — Créer la base et charger vos données

Vos relevés ne sont pas dans le dépôt : ce sont des données, pas du code, et
elles sont personnelles. Elles font donc le voyage **une seule fois**.

Sur votre PC, produisez le fichier :

```bash
python manage.py exporter_donnees
```

Il écrit `donnees-AAAAMMJJ.json` à la racine (environ 850 Ko). Dans
PythonAnywhere, onglet **Files**, naviguez jusqu'à `suivi_compteurs_project`
et téléversez-le avec *Upload a file*.

De retour dans la console :

```bash
python manage.py migrate
python manage.py loaddata donnees-AAAAMMJJ.json
python manage.py collectstatic --noinput
python manage.py createsuperuser
```

`loaddata` importe vos 2 maisons, 8 compteurs, 166 relevés, 12 tarifs,
4 événements et 4 657 jours de degrés-jours.

Le `createsuperuser` vous demande un identifiant et un mot de passe : ils
protègent `/admin/`, désormais exposé sur internet. **Prenez un vrai mot de
passe**, pas celui que vous utilisez ailleurs.

---

## Étape 6 — Déclarer l'application web

### D'abord, deux préalables

**Réactivez le site.** Onglet **Web**, bouton *Run until 1 month from today*.
Tant qu'il est expiré, rien ne répondra, quelle que soit la configuration.

**Créez le dossier des photos**, sinon la seconde règle de fichiers statiques
pointera dans le vide :

```bash
mkdir -p ~/suivi_compteurs_project/media
```

### Ensuite, la configuration

Vous avez **déjà** une application web à `cimeclean.pythonanywhere.com`, et
l'offre gratuite n'en autorise qu'une. Ne cliquez donc pas sur *Add a new web
app* : vous n'y arriveriez pas. Ouvrez l'application existante dans la colonne
de gauche et modifiez ses réglages — tous les champs ci-dessous sont éditables,
quelle qu'ait été sa configuration d'origine.

> Si quelque chose résiste, supprimez l'application (lien *Delete* en bas de sa
> page) puis recréez-la avec **Manual configuration** — surtout pas « Django »,
> qui créerait un projet vide par-dessus le vôtre. Vous ne perdez rien : vos
> données sont dans la base, pas dans cette configuration.

Renseignez :

| Champ | Valeur |
|---|---|
| **Source code** | `/home/cimeclean/suivi_compteurs_project` |
| **Working directory** | `/home/cimeclean/suivi_compteurs_project` |
| **Virtualenv** | `/home/cimeclean/.virtualenvs/compteurs` |

4. Section **Code**, cliquez sur le lien *WSGI configuration file*. Effacez tout
   son contenu et remplacez-le par celui de `deploiement/wsgi_cimeclean.py`.
   Ce fichier ne contient aucun secret, il est donc dans le dépôt : affichez-le
   directement depuis la console pour le copier.

   ```bash
   cat deploiement/wsgi_cimeclean.py
   ```

   Enregistrez.

5. Section **Static files**, ajoutez deux entrées :

| URL | Directory |
|---|---|
| `/static/` | `/home/cimeclean/suivi_compteurs_project/staticfiles` |
| `/media/` | `/home/cimeclean/suivi_compteurs_project/media` |

La première sert les feuilles de style et les graphiques, la seconde les photos
de compteurs.

6. Tout en haut, bouton vert **Reload**.

Ouvrez `https://cimeclean.pythonanywhere.com` : votre tableau de bord doit
s'afficher.

> **En cas d'erreur**, l'onglet **Web** propose un lien *Error log*. La cause
> est presque toujours dans les dernières lignes : chemin erroné dans le fichier
> WSGI, ou `.env` mal formé.

---

## Étape 7 — Raccorder le téléphone

Dans l'application Android, menu **⋮** → **Réglages** :

| Champ | Valeur |
|---|---|
| Adresse du serveur | `https://cimeclean.pythonanywhere.com` |
| Jeton | la valeur de `SUIVI_JETON_API` de l'étape 4 |

Appuyez sur **Tester la connexion**. Vous devez lire « Connexion établie,
3 compteurs trouvés ». Enregistrez.

> Tapez bien `https://` au début : sans schéma, l'application ajouterait `:8000`,
> qui ne vaut que pour le serveur domestique.

À partir de là, votre téléphone fonctionne **sans le PC**, de n'importe où.

---

## Étape 8 — Entretien

**Mettre à jour les degrés-jours** — une fois par mois suffit. Page *Climat*,
bouton *Mettre à jour depuis Open-Meteo*. Ou en console :

```bash
workon compteurs && cd suivi_compteurs_project
python manage.py sync_degres_jours
```

**Prolonger l'application** — onglet **Web**, bouton *Run until 3 months from
today*, quand PythonAnywhere vous le rappelle par courriel.

**Sauvegarder** — la base est un simple fichier. Depuis l'onglet **Files**,
téléchargez `db.sqlite3` de temps en temps. Ou en console, pour un export
lisible :

```bash
python manage.py exporter_donnees sauvegarde.json
```

**Mettre le code à jour** — c'est le gain du dépôt Git. Sur votre PC :

```bash
git add -A
git commit -m "ce que vous avez changé"
git push
```

Puis sur PythonAnywhere, console Bash :

```bash
workon compteurs && cd suivi_compteurs_project
git pull
pip install -r requirements.txt     # seulement si les dépendances ont bougé
python manage.py migrate
python manage.py collectstatic --noinput
```

et **Reload** dans l'onglet Web.

`.env` et vos données ne sont jamais touchés par un `git pull` : Git les ignore,
il ne peut donc ni les écraser ni les supprimer.

---

## Et le PC dans tout ça ?

Il garde son utilité : c'est là que vous importez le classeur Excel et que vous
gardez une copie de l'historique. Deux façons de le faire cohabiter :

- **Le plus simple** : le PC devient un client comme le téléphone. Vous
  consultez `https://cimeclean.pythonanywhere.com` dans votre navigateur, et
  l'installation locale ne sert plus qu'aux imports et aux sauvegardes.
- **Si vous importez un nouveau classeur** : importez-le en local, exportez avec
  `python manage.py exporter_donnees`, envoyez le JSON sur le serveur et
  rechargez-le. Attention, `loaddata` **remplace** les enregistrements de même
  identifiant : exportez depuis la source qui fait foi.

Pour éviter toute divergence, tenez-vous-en à une règle simple : **le serveur en
ligne fait foi**. Le PC importe et sauvegarde, il ne saisit pas.
