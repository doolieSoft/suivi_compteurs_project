# Publier sur F-Droid

F-Droid compile lui-même l'application depuis ce dépôt, avec la variante
**libre** : aucun composant propriétaire, l'index se tape à la main. La fiche
(textes, captures, icône) est lue dans `fastlane/metadata/android/`.

## Proposer l'application

1. Créer un compte sur <https://gitlab.com> et dupliquer (fork)
   <https://gitlab.com/fdroid/fdroiddata>.
2. Y ajouter `metadata/be.suivicompteurs.app.yml`, copié de
   `be.suivicompteurs.app.yml` ici. Ajuster `versionName`, `versionCode` et
   `commit` sur la dernière étiquette publiée. Le lien de dons (champ
   `Liberapay` et `suiviLienDons`) est déjà renseigné.
3. Ouvrir une demande de fusion (merge request) avec le modèle « New App ».
   Les relecteurs de F-Droid compilent, signalent ce qui manque, puis
   publient. Comptez de quelques jours à quelques semaines.

## Points d'attention

- **Signature** : F-Droid signe avec sa propre clé. Un téléphone qui a la
  version GitHub ou Play Store doit désinstaller pour passer à celle de
  F-Droid, et inversement : exporter ses données en Excel avant.
- **Nouvelles versions** : F-Droid les trouve seul, d'après les étiquettes
  `v…` et les numéros écrits dans `android/app/build.gradle.kts`. Rien à
  refaire chez F-Droid ; comptez quelques jours entre l'étiquette et la
  publication.
- **ML Kit** : la recette retire sa ligne de `build.gradle.kts` avant de
  compiler (`prebuild`). La variante libre ne s'en sert pas.
- **Dons** : F-Droid accepte un lien de dons, dans l'application comme sur la
  fiche (champ `Liberapay`, `OpenCollective`, `Donate`…).
