# F-Droid

L'application est publiée sur F-Droid depuis le 27 octobre 2026 :
<https://f-droid.org/packages/be.suivicompteurs.app/>.

F-Droid la compile lui-même depuis ce dépôt, avec la variante **libre** :
aucun composant propriétaire, l'index se tape à la main. La fiche (textes,
captures, icône) est lue dans `fastlane/metadata/android/`.

`be.suivicompteurs.app.yml` est la recette telle que fusionnée. La référence
est `metadata/be.suivicompteurs.app.yml` dans
<https://gitlab.com/fdroid/fdroiddata> : la modifier demande une merge request.

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
