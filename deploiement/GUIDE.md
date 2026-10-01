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

Le dépôt local est déjà initialisé et le premier enregistrement est fait. Il
reste à le relier à GitHub :

1. Sur <https://github.com/new>, créez un dépôt **vide** — ni README, ni
   `.gitignore`, ni licence, sinon le premier envoi sera refusé pour cause
   d'historiques divergents.
2. Nommez-le par exemple `suivi-compteurs`.
3. **Public ou privé ?** Aucun secret ne part dans le dépôt, un dépôt public est
   donc sans danger — et c'est le plus simple à cloner sur PythonAnywhere. Un
   dépôt privé reste préférable si vous ne souhaitez pas exposer l'adresse de
   votre serveur ; il demande alors une étape d'authentification, décrite plus
   bas.
4. De retour dans votre console, en remplaçant `VOTRECOMPTE` :

```bash
git remote add origin https://github.com/VOTRECOMPTE/suivi-compteurs.git
git branch -M main
git push -u origin main
```

### Sur PythonAnywhere

Onglet **Consoles** → *Bash*, puis :

```bash
git clone https://github.com/VOTRECOMPTE/suivi-compteurs.git suivi_compteurs_project
cd suivi_compteurs_project
ls
```

Vous devez voir `manage.py`, `config/`, `suivi/`, `requirements.txt`.

> **Dépôt privé ?** Les comptes gratuits n'atteignent internet qu'en HTTP(S) :
> le SSH (`git@github.com:…`) ne fonctionnera pas. Utilisez un jeton d'accès
> personnel, créé sur
> <https://github.com/settings/personal-access-tokens> avec la seule
> permission *Contents : read-only* sur ce dépôt, puis :
>
> ```bash
> git clone https://VOTRECOMPTE:VOTRE_JETON@github.com/VOTRECOMPTE/suivi-compteurs.git suivi_compteurs_project
> ```
>
> Le jeton reste alors inscrit dans `.git/config` sur le serveur. C'est
> acceptable sur un compte qui n'est qu'à vous, et c'est la raison pour
> laquelle il doit être restreint à la lecture de ce seul dépôt.

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

Onglet **Web** → *Add a new web app* → *Next*.

1. Framework : choisissez **Manual configuration** (surtout pas « Django », qui
   créerait un projet vide par-dessus le vôtre).
2. Version de Python : la même qu'à l'étape 3.
3. Sur la page de configuration qui s'ouvre, renseignez :

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
