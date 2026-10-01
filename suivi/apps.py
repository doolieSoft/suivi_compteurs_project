from django.apps import AppConfig
from django.core.checks import Warning as AvertissementDjango
from django.core.checks import register


class SuiviConfig(AppConfig):
    default_auto_field = "django.db.models.BigAutoField"
    name = "suivi"
    verbose_name = "Suivi des compteurs"

    def ready(self):
        register(_verifier_jeton_api)


def _verifier_jeton_api(app_configs, **kwargs):
    """Signale un jeton d'API absent.

    Sans lui l'application web fonctionne normalement, mais le téléphone se
    voit refuser chaque requête. Le symptôme — « Jeton refusé » côté mobile
    alors que rien n'a changé — est déroutant si l'on ne sait pas où chercher ;
    autant le dire au démarrage.
    """
    from django.conf import settings

    if getattr(settings, "JETON_API", ""):
        return []

    return [
        AvertissementDjango(
            "Aucun jeton d'API : l'application Android sera refusée.",
            hint=(
                "Ajoutez SUIVI_JETON_API au fichier .env à la racine du projet.\n"
                "        Pour en produire un :\n"
                "        python -c \"import secrets; "
                "print('SUIVI_JETON_API=' + secrets.token_urlsafe(24))\""
            ),
            id="suivi.W001",
        )
    ]
