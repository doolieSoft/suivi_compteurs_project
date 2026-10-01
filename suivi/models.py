"""Modèle de données du suivi de compteurs.

Structure générale :

    StationMeteo --< DegreJour
         ^
         |
       Maison --< Compteur --< Releve
         |
         +------< Evenement
         +------< Tarif

Un ``Compteur`` représente un appareil physique. Quand un compteur est
remplacé (l'index repart de zéro), on crée un *nouveau* ``Compteur`` plutôt
que de fausser les écarts d'index : les consommations restent cumulables au
niveau de la maison, jamais au niveau de l'index brut.
"""
from __future__ import annotations

from datetime import date
from decimal import Decimal
from pathlib import Path

from django.core.validators import MinValueValidator
from django.db import models
from django.urls import reverse


class Energie(models.TextChoices):
    EAU = "EAU", "Eau"
    GAZ = "GAZ", "Gaz"
    ELECTRICITE = "ELEC", "Électricité"


class Plage(models.TextChoices):
    """Plage tarifaire, pour les compteurs électriques bi-horaires."""

    UNIQUE = "UNIQUE", "Mono-horaire"
    HAUT = "HAUT", "Heures pleines"
    BAS = "BAS", "Heures creuses"


class SourceReleve(models.TextChoices):
    MANUEL = "MANUEL", "Relevé manuel"
    FOURNISSEUR = "FOURNISSEUR", "Index fournisseur"
    ESTIME = "ESTIME", "Estimation"
    IMPORT = "IMPORT", "Import Excel"


# ---------------------------------------------------------------------------
# Météo
# ---------------------------------------------------------------------------


class StationMeteo(models.Model):
    """Point géographique servant au calcul des degrés-jours.

    Les températures sont récupérées sur l'API historique d'Open-Meteo
    (``archive-api.open-meteo.com``), gratuite et sans clé.
    """

    nom = models.CharField(max_length=100, unique=True)
    latitude = models.DecimalField(max_digits=8, decimal_places=5)
    longitude = models.DecimalField(max_digits=8, decimal_places=5)
    base_dj = models.DecimalField(
        "base des degrés-jours (°C)",
        max_digits=4,
        decimal_places=1,
        default=Decimal("16.5"),
        help_text="16.5 = convention belge (DJ 16.5/16.5).",
    )

    class Meta:
        verbose_name = "station météo"
        verbose_name_plural = "stations météo"
        ordering = ["nom"]

    def __str__(self) -> str:
        return self.nom


class DegreJour(models.Model):
    """Degrés-jours d'une journée pour une station.

    ``dj = max(0, base - temperature_moyenne)``. C'est la mesure de rigueur
    climatique qui rend deux années comparables entre elles.
    """

    station = models.ForeignKey(
        StationMeteo, on_delete=models.CASCADE, related_name="degres_jours"
    )
    date = models.DateField()
    temperature_moyenne = models.DecimalField(
        max_digits=5, decimal_places=2, null=True, blank=True
    )
    dj = models.DecimalField("degrés-jours", max_digits=6, decimal_places=3)

    class Meta:
        verbose_name = "degré-jour"
        verbose_name_plural = "degrés-jours"
        ordering = ["station", "date"]
        constraints = [
            models.UniqueConstraint(
                fields=["station", "date"], name="dj_unique_par_station_et_date"
            )
        ]
        indexes = [models.Index(fields=["station", "date"])]

    def __str__(self) -> str:
        return f"{self.station} {self.date:%d/%m/%Y} : {self.dj} DJ"


# ---------------------------------------------------------------------------
# Patrimoine
# ---------------------------------------------------------------------------


class Maison(models.Model):
    nom = models.CharField(max_length=100, unique=True)
    slug = models.SlugField(max_length=100, unique=True)
    nb_facades = models.PositiveSmallIntegerField(
        "nombre de façades", null=True, blank=True
    )
    adresse = models.CharField(max_length=200, blank=True)
    date_entree = models.DateField("occupée depuis", null=True, blank=True)
    date_sortie = models.DateField("occupée jusqu'au", null=True, blank=True)
    surface_m2 = models.PositiveIntegerField(
        "surface chauffée (m²)", null=True, blank=True
    )
    station = models.ForeignKey(
        StationMeteo,
        on_delete=models.PROTECT,
        related_name="maisons",
        null=True,
        blank=True,
    )
    notes = models.TextField(blank=True)

    class Meta:
        verbose_name = "maison"
        verbose_name_plural = "maisons"
        ordering = ["-date_entree"]

    def __str__(self) -> str:
        return self.nom

    def get_absolute_url(self) -> str:
        return reverse("suivi:maison_detail", args=[self.slug])

    @property
    def est_actuelle(self) -> bool:
        return self.date_sortie is None

    @property
    def libelle_facades(self) -> str:
        return f"{self.nb_facades} façades" if self.nb_facades else ""


class Compteur(models.Model):
    maison = models.ForeignKey(
        Maison, on_delete=models.CASCADE, related_name="compteurs"
    )
    energie = models.CharField(max_length=5, choices=Energie.choices)
    plage = models.CharField(
        max_length=6, choices=Plage.choices, default=Plage.UNIQUE
    )
    libelle = models.CharField(max_length=100, blank=True)
    numero = models.CharField("numéro de compteur", max_length=50, blank=True)
    unite = models.CharField(max_length=10, default="m³")
    coef_kwh = models.DecimalField(
        "coefficient de conversion en kWh",
        max_digits=6,
        decimal_places=3,
        default=Decimal("1"),
        help_text="11 pour un compteur gaz en m³, 1 pour l'électricité.",
    )
    date_pose = models.DateField(null=True, blank=True)
    date_depose = models.DateField(null=True, blank=True)
    remplace = models.ForeignKey(
        "self",
        on_delete=models.SET_NULL,
        null=True,
        blank=True,
        related_name="remplace_par",
        verbose_name="remplace le compteur",
    )

    class Meta:
        verbose_name = "compteur"
        verbose_name_plural = "compteurs"
        ordering = ["maison", "energie", "plage", "date_pose"]

    def __str__(self) -> str:
        parts = [self.maison.nom, self.get_energie_display()]
        if self.plage != Plage.UNIQUE:
            parts.append(self.get_plage_display())
        if self.libelle:
            parts.append(f"({self.libelle})")
        return " – ".join(parts)

    def get_absolute_url(self) -> str:
        return reverse("suivi:compteur_detail", args=[self.pk])

    @property
    def est_actif(self) -> bool:
        return self.date_depose is None

    def vers_kwh(self, valeur: Decimal | float) -> Decimal:
        """Convertit une quantité exprimée dans l'unité du compteur en kWh."""
        return Decimal(str(valeur)) * self.coef_kwh


def chemin_photo(instance: "Releve", nom_fichier: str) -> str:
    """Range les photos par maison et par année : media/compteurs/<maison>/2026/…"""
    extension = Path(nom_fichier).suffix.lower() or ".jpg"
    return (
        f"compteurs/{instance.compteur.maison.slug}/{instance.date:%Y}/"
        f"{instance.compteur.energie.lower()}-{instance.date:%Y%m%d}{extension}"
    )


class Releve(models.Model):
    compteur = models.ForeignKey(
        Compteur, on_delete=models.CASCADE, related_name="releves"
    )
    date = models.DateField()
    index = models.DecimalField(
        max_digits=12, decimal_places=3, validators=[MinValueValidator(Decimal("0"))]
    )
    source = models.CharField(
        max_length=12, choices=SourceReleve.choices, default=SourceReleve.MANUEL
    )
    commentaire = models.CharField(max_length=200, blank=True)
    annuel = models.BooleanField(
        "relevé annuel",
        default=False,
        help_text=(
            "À cocher pour le relevé qui clôt l'année. Les prévisions et les "
            "comparaisons d'une année sur l'autre partent alors de cette date "
            "plutôt que du 1er janvier."
        ),
    )
    photo = models.ImageField(
        "photo du compteur",
        upload_to=chemin_photo,
        blank=True,
        null=True,
        help_text="Preuve de l'index, utile en cas de contestation.",
    )
    index_lu_automatiquement = models.DecimalField(
        "index proposé par l'OCR",
        max_digits=12,
        decimal_places=3,
        null=True,
        blank=True,
        help_text=(
            "Valeur reconnue sur la photo avant correction éventuelle. "
            "Conservée pour mesurer la fiabilité de la lecture automatique."
        ),
    )

    class Meta:
        verbose_name = "relevé"
        verbose_name_plural = "relevés"
        ordering = ["compteur", "date"]
        constraints = [
            models.UniqueConstraint(
                fields=["compteur", "date"], name="releve_unique_par_compteur_et_date"
            )
        ]
        indexes = [models.Index(fields=["compteur", "date"])]

    def __str__(self) -> str:
        return f"{self.compteur} – {self.date:%d/%m/%Y} : {self.index}"


class Evenement(models.Model):
    """Fait marquant expliquant une rupture de consommation.

    Isolation du toit, changement de douche, remplacement de compteur…
    Ces repères sont tracés sur les graphiques.
    """

    maison = models.ForeignKey(
        Maison, on_delete=models.CASCADE, related_name="evenements"
    )
    energie = models.CharField(
        max_length=5,
        choices=Energie.choices,
        blank=True,
        help_text="Laisser vide si l'événement concerne toutes les énergies.",
    )
    date = models.DateField()
    libelle = models.CharField(max_length=150)
    description = models.TextField(blank=True)

    class Meta:
        verbose_name = "événement"
        verbose_name_plural = "événements"
        ordering = ["date"]

    def __str__(self) -> str:
        return f"{self.date:%d/%m/%Y} – {self.libelle}"


class Tarif(models.Model):
    """Prix applicable à une énergie sur une période.

    ``prix_unitaire`` est exprimé par unité du compteur (€/m³, €/kWh) et
    ``abonnement_mensuel`` couvre la redevance fixe.
    """

    maison = models.ForeignKey(
        Maison,
        on_delete=models.CASCADE,
        related_name="tarifs",
        null=True,
        blank=True,
        help_text="Laisser vide pour un tarif applicable à toutes les maisons.",
    )
    energie = models.CharField(max_length=5, choices=Energie.choices)
    fournisseur = models.CharField(max_length=100, blank=True)
    date_debut = models.DateField()
    date_fin = models.DateField(null=True, blank=True)
    prix_unitaire = models.DecimalField(
        "prix unitaire (€)",
        max_digits=8,
        decimal_places=5,
        default=Decimal("0"),
        help_text="Par unité du compteur : €/m³ pour l'eau et le gaz, €/kWh pour l'électricité.",
    )
    abonnement_mensuel = models.DecimalField(
        "abonnement (€/mois)", max_digits=8, decimal_places=2, default=Decimal("0")
    )
    notes = models.CharField(max_length=200, blank=True)

    class Meta:
        verbose_name = "tarif"
        verbose_name_plural = "tarifs"
        ordering = ["energie", "date_debut"]

    def __str__(self) -> str:
        fin = f"{self.date_fin:%m/%Y}" if self.date_fin else "…"
        return (
            f"{self.get_energie_display()} {self.fournisseur} "
            f"{self.date_debut:%m/%Y}→{fin}"
        )

    def couvre(self, jour: date) -> bool:
        if jour < self.date_debut:
            return False
        return self.date_fin is None or jour <= self.date_fin
