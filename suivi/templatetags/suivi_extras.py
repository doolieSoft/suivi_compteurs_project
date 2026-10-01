"""Filtres de présentation."""
from __future__ import annotations

from django import template
from django.utils.formats import number_format
from django.utils.safestring import mark_safe

from suivi.models import Energie

register = template.Library()

CLASSES_ENERGIE = {
    Energie.EAU: "eau",
    Energie.GAZ: "gaz",
    Energie.ELECTRICITE: "elec",
}

# En deçà, une variation est du bruit de mesure plutôt qu'une tendance.
SEUIL_STABILITE = 1.0


@register.filter
def nb(valeur, decimales=0):
    """Nombre formaté à la belge : espace fine, virgule décimale."""
    if valeur is None:
        return "—"
    try:
        return number_format(round(float(valeur), int(decimales)), int(decimales), force_grouping=True)
    except (TypeError, ValueError):
        return "—"


@register.filter
def pourcent(valeur, decimales=1):
    """Pourcentage signé, avec le signe explicite y compris pour les hausses."""
    if valeur is None:
        return "—"
    try:
        nombre = float(valeur)
    except (TypeError, ValueError):
        return "—"
    signe = "+" if nombre > 0 else ""
    return f"{signe}{number_format(round(nombre, int(decimales)), int(decimales))} %"


@register.filter
def classe_delta(valeur):
    """Classe CSS d'une variation : une baisse de consommation est une bonne nouvelle."""
    if valeur is None:
        return "stable"
    try:
        nombre = float(valeur)
    except (TypeError, ValueError):
        return "stable"
    if abs(nombre) < SEUIL_STABILITE:
        return "stable"
    return "hausse" if nombre > 0 else "baisse"


@register.filter
def fleche_delta(valeur):
    """Flèche accompagnant la variation : la couleur n'est jamais seule porteuse de sens."""
    classe = classe_delta(valeur)
    return mark_safe({"hausse": "▲", "baisse": "▼", "stable": "▬"}[classe])


@register.filter
def classe_energie(code):
    return CLASSES_ENERGIE.get(code, "")
