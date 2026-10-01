from django.contrib import admin

from .models import (
    Compteur,
    DegreJour,
    Evenement,
    Maison,
    Releve,
    StationMeteo,
    Tarif,
)


class CompteurInline(admin.TabularInline):
    model = Compteur
    extra = 0
    fields = ("energie", "plage", "libelle", "unite", "coef_kwh", "date_pose", "date_depose")


class EvenementInline(admin.TabularInline):
    model = Evenement
    extra = 0
    fields = ("date", "energie", "libelle")


@admin.register(Maison)
class MaisonAdmin(admin.ModelAdmin):
    list_display = ("nom", "nb_facades", "date_entree", "date_sortie", "station")
    prepopulated_fields = {"slug": ("nom",)}
    inlines = [CompteurInline, EvenementInline]


class ReleveInline(admin.TabularInline):
    model = Releve
    extra = 1
    fields = ("date", "index", "source", "annuel", "commentaire")
    ordering = ("-date",)


@admin.register(Compteur)
class CompteurAdmin(admin.ModelAdmin):
    list_display = (
        "__str__",
        "unite",
        "date_pose",
        "date_depose",
        "nombre_releves",
    )
    list_filter = ("maison", "energie", "plage")
    inlines = [ReleveInline]

    @admin.display(description="relevés")
    def nombre_releves(self, obj: Compteur) -> int:
        return obj.releves.count()


@admin.register(Releve)
class ReleveAdmin(admin.ModelAdmin):
    list_display = ("date", "compteur", "index", "source", "annuel", "commentaire")
    list_filter = ("compteur__maison", "compteur__energie", "source", "annuel")
    date_hierarchy = "date"
    search_fields = ("commentaire",)
    autocomplete_fields = ()
    ordering = ("-date",)


@admin.register(Evenement)
class EvenementAdmin(admin.ModelAdmin):
    list_display = ("date", "maison", "energie", "libelle")
    list_filter = ("maison", "energie")
    date_hierarchy = "date"


@admin.register(Tarif)
class TarifAdmin(admin.ModelAdmin):
    list_display = (
        "energie",
        "maison",
        "fournisseur",
        "date_debut",
        "date_fin",
        "prix_unitaire",
        "abonnement_mensuel",
    )
    list_filter = ("energie", "maison", "fournisseur")


@admin.register(StationMeteo)
class StationMeteoAdmin(admin.ModelAdmin):
    list_display = ("nom", "latitude", "longitude", "base_dj", "nombre_jours")

    @admin.display(description="jours en base")
    def nombre_jours(self, obj: StationMeteo) -> int:
        return obj.degres_jours.count()


@admin.register(DegreJour)
class DegreJourAdmin(admin.ModelAdmin):
    list_display = ("date", "station", "temperature_moyenne", "dj")
    list_filter = ("station",)
    date_hierarchy = "date"
    ordering = ("-date",)
