from django import forms

from .models import Compteur, Releve, Tarif


class DateInput(forms.DateInput):
    input_type = "date"


class ReleveForm(forms.ModelForm):
    class Meta:
        model = Releve
        fields = ["compteur", "date", "index", "source", "commentaire"]
        widgets = {
            "date": DateInput(),
            "index": forms.NumberInput(attrs={"step": "0.001", "inputmode": "decimal"}),
            "commentaire": forms.TextInput(
                attrs={"placeholder": "Facultatif : index fournisseur, travaux…"}
            ),
        }

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.fields["compteur"].queryset = Compteur.objects.filter(
            date_depose__isnull=True
        ).select_related("maison")
        self.fields["compteur"].label_from_instance = str

    def clean(self):
        donnees = super().clean()
        compteur, date, index = (
            donnees.get("compteur"),
            donnees.get("date"),
            donnees.get("index"),
        )
        if not (compteur and date and index is not None):
            return donnees

        precedent = (
            compteur.releves.filter(date__lt=date).order_by("-date").first()
        )
        if precedent and index < precedent.index:
            self.add_error(
                "index",
                f"Index inférieur au relevé du {precedent.date:%d/%m/%Y} "
                f"({precedent.index}). S'il s'agit d'un compteur remplacé, "
                "créez d'abord un nouveau compteur dans l'administration.",
            )

        suivant = compteur.releves.filter(date__gt=date).order_by("date").first()
        if suivant and index > suivant.index:
            self.add_error(
                "index",
                f"Index supérieur au relevé du {suivant.date:%d/%m/%Y} "
                f"({suivant.index}).",
            )
        return donnees


class TarifForm(forms.ModelForm):
    class Meta:
        model = Tarif
        fields = [
            "maison",
            "energie",
            "fournisseur",
            "date_debut",
            "date_fin",
            "prix_unitaire",
            "abonnement_mensuel",
            "notes",
        ]
        widgets = {
            "date_debut": DateInput(),
            "date_fin": DateInput(),
        }


class ImportExcelForm(forms.Form):
    fichier = forms.FileField(
        label="Classeur Excel",
        help_text="Le fichier compteur.xlsx, avec ses feuilles Eau, Gaz et Électricité.",
    )
    purger = forms.BooleanField(
        label="Remplacer entièrement les données existantes",
        required=False,
        help_text="Sinon, les relevés déjà connus sont simplement mis à jour.",
    )
