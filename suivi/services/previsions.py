"""Projection de la consommation de fin d'année.

Deux méthodes, choisies automatiquement :

* **Méthode thermique** — quand le modèle ``base + k × DJ`` est fiable (cas du
  gaz) : les jours restants sont estimés avec les degrés-jours *normaux* de la
  saison, ce qui évite d'extrapoler un hiver doux sur un mois de janvier.
* **Méthode du profil saisonnier** — sinon : on mesure, sur les années
  complètes passées, quelle fraction de l'année est déjà écoulée en termes de
  consommation, et on divise le réalisé par cette fraction.

Dans les deux cas, le réalisé mesuré n'est jamais réécrit : la prévision ne
porte que sur les jours non encore couverts par un relevé.

L'« année » est l'année civile tant qu'aucun relevé n'est marqué *annuel*.
Dès qu'il en existe un, elle court d'anniversaire en anniversaire du dernier
relevé annuel : la borne tombe alors sur un index réellement lu, au lieu d'un
1er janvier où personne n'est descendu à la cave. La borne est la même chaque
année, pour que les périodes restent de même longueur et comparables ; les
quelques jours qui la séparent du relevé annuel d'une autre année sont estimés
par la ventilation.
"""
from __future__ import annotations

import datetime as dt
from dataclasses import dataclass

from suivi.models import Maison, Releve
from suivi.services import consommation as cs
from suivi.services import degres_jours as dj_service
from suivi.services.consommation import Ligne

UN_JOUR = dt.timedelta(days=1)

# Part des jours d'une période qui doivent être couverts pour qu'elle serve de
# référence ou de profil.
SEUIL_COMPLETUDE = 0.9
# Au-delà, un relevé annuel est trop loin de la borne pour s'y rattacher.
TOLERANCE_BORNE_JOURS = 45


@dataclass
class Prevision:
    annee: int
    realise: float
    jours_realises: int
    estime_restant: float
    jours_restants: int
    total_prevu: float
    borne_basse: float | None
    borne_haute: float | None
    methode: str
    reference: float | None  # consommation de la période complète précédente
    reference_annee: int | None
    reference_normalisee: float | None
    # Premier et dernier jour de la période projetée.
    debut: dt.date
    fin: dt.date
    sur_releve_annuel: bool = False

    @property
    def evolution_pct(self) -> float | None:
        if not self.reference:
            return None
        return 100.0 * (self.total_prevu - self.reference) / self.reference

    @property
    def evolution_normalisee_pct(self) -> float | None:
        """Évolution une fois l'effet du climat retiré des deux termes."""
        if not self.reference_normalisee:
            return None
        return (
            100.0
            * (self.total_prevu - self.reference_normalisee)
            / self.reference_normalisee
        )

    @property
    def avancement_pct(self) -> float:
        total = (self.fin - self.debut).days + 1
        return 100.0 * self.jours_realises / total

    @property
    def borne_depart(self) -> dt.date:
        """Jour dont l'index sert de point de départ à la période."""
        return self.debut - UN_JOUR


# ---------------------------------------------------------------------------
# Périodes
# ---------------------------------------------------------------------------


def _decaler(jour: dt.date, annees: int) -> dt.date:
    """Même jour, ``annees`` plus tard (ou plus tôt si négatif)."""
    try:
        return jour.replace(year=jour.year + annees)
    except ValueError:  # 29 février
        return jour.replace(year=jour.year + annees, day=28)


def _millesime(debut: dt.date) -> int:
    """Année à laquelle on rattache une période : celle où tombe son milieu."""
    return (debut + dt.timedelta(days=182)).year


def releves_annuels(ligne: Ligne) -> list[dt.date]:
    """Dates des relevés marqués « annuels », dans l'ordre."""
    return sorted(
        set(
            Releve.objects.filter(
                compteur__in=ligne.compteurs, annuel=True
            ).values_list("date", flat=True)
        )
    )


def periode_en_cours(
    ancre: dt.date, aujourdhui: dt.date
) -> tuple[dt.date, dt.date]:
    """Période d'un an, bornée par l'anniversaire de ``ancre``, où tombe ``aujourdhui``.

    Le jour même de la borne ouvre la période suivante : c'est le relevé qui
    clôt l'année écoulée.
    """
    borne = _decaler(ancre, aujourdhui.year - ancre.year)
    if borne > aujourdhui:
        borne = _decaler(ancre, aujourdhui.year - ancre.year - 1)
    suivante = _decaler(ancre, borne.year - ancre.year + 1)
    return borne + UN_JOUR, suivante


def _periodes_passees(
    ligne: Ligne, debut: dt.date, fin: dt.date
) -> list[tuple[int, float, list[float]]]:
    """Mêmes dates les années précédentes, pour celles qui sont complètes.

    Renvoie ``(recul en années, total, part cumulée jour après jour)``, de la
    plus récente à la plus ancienne.
    """
    serie = ligne.serie
    premier = serie.premier_jour
    resultat: list[tuple[int, float, list[float]]] = []
    if premier is None:
        return resultat

    recul = 1
    while True:
        d, f = _decaler(debut, -recul), _decaler(fin, -recul)
        if f < premier:
            break
        nb_jours = (f - d).days + 1
        valeurs = [serie.jours.get(d + dt.timedelta(days=i)) for i in range(nb_jours)]
        couverts = [v for v in valeurs if v is not None]
        total = sum(couverts)
        # Période incomplète : profil non représentatif.
        if len(couverts) >= nb_jours * SEUIL_COMPLETUDE and total > 0:
            cumul = 0.0
            courbe: list[float] = []
            for valeur in valeurs:
                cumul += valeur or 0.0
                courbe.append(cumul / total)
            resultat.append((recul, total, courbe))
        recul += 1
    return resultat


# ---------------------------------------------------------------------------
# Prévision
# ---------------------------------------------------------------------------


def prevoir(
    ligne: Ligne,
    *,
    annee: int | None = None,
    aujourdhui: dt.date | None = None,
    normales: dict[tuple[int, int], float] | None = None,
    periode: tuple[dt.date, dt.date] | None = None,
) -> Prevision | None:
    """Projette la consommation de ``ligne`` jusqu'à la fin de la période.

    Sans précision, la période est celle ouverte par le dernier relevé annuel,
    ou l'année civile en cours s'il n'y en a pas. ``annee`` impose une année
    civile, ``periode`` des bornes quelconques (premier et dernier jour).
    """
    serie = ligne.serie
    if not serie.jours:
        return None

    aujourdhui = aujourdhui or dt.date.today()

    sur_releve_annuel = False
    if periode is not None:
        debut, fin = periode
        sur_releve_annuel = True
        annee = _millesime(debut)
    elif annee is None:
        annuels = [d for d in releves_annuels(ligne) if d <= aujourdhui]
        if annuels:
            debut, fin = periode_en_cours(annuels[-1], aujourdhui)
            sur_releve_annuel = True
            annee = _millesime(debut)
        else:
            annee = aujourdhui.year
    if not sur_releve_annuel:
        debut, fin = dt.date(annee, 1, 1), dt.date(annee, 12, 31)

    jours_periode = [j for j in serie.jours if debut <= j <= fin]
    if not jours_periode and not sur_releve_annuel:
        return None

    realise = sum(serie.jours[j] for j in jours_periode)
    # Au lendemain d'un relevé annuel, la période est ouverte mais encore vide.
    dernier_couvert = max(jours_periode) if jours_periode else debut - UN_JOUR
    jours_realises = len(jours_periode)

    debut_restant = dernier_couvert + UN_JOUR
    jours_restants = max(0, (fin - debut_restant).days + 1)

    if normales is None:
        normales = dj_service.dj_normaux_par_jour_calendaire(ligne.maison.station)

    passees = _periodes_passees(ligne, debut, fin)

    estime = 0.0
    borne_basse: float | None = None
    borne_haute: float | None = None
    methode = "linéaire"

    if jours_restants > 0:
        modele = serie.modele
        if modele.fiable and normales:
            dj_restants = dj_service.dj_normal_sur_periode(
                normales, debut_restant, fin
            )
            estime = modele.base * jours_restants + modele.k * dj_restants
            methode = "modèle thermique (DJ normaux)"
            # Amplitude observée des hivers : ±15 % sur la part chauffage.
            part_chauffage = modele.k * dj_restants
            borne_basse = realise + estime - 0.15 * part_chauffage
            borne_haute = realise + estime + 0.15 * part_chauffage
        elif not jours_periode:
            # Rien de relevé encore : seules les années passées renseignent.
            totaux = [total for _, total, _ in passees]
            if not totaux:
                return None
            estime = sum(totaux) / len(totaux)
            methode = f"moyenne de {len(totaux)} année(s) passée(s)"
            borne_basse, borne_haute = min(totaux), max(totaux)
        else:
            rang = (dernier_couvert - debut).days + 1
            fractions = [
                courbe[min(rang, len(courbe)) - 1] for _, _, courbe in passees
            ]
            fractions = [f for f in fractions if f > 0.05]
            if fractions:
                moyenne = sum(fractions) / len(fractions)
                estime = realise / moyenne - realise
                methode = f"profil saisonnier ({len(fractions)} année(s) de référence)"
                borne_basse = realise / max(fractions)
                borne_haute = realise / min(fractions)
            else:
                # Aucun historique complet : simple prorata temporis.
                estime = realise / jours_realises * jours_restants
                methode = "prorata temporis (pas d'historique complet)"

    total_prevu = realise + estime

    # Référence : dernière période complète disponible.
    reference = reference_annee = reference_normalisee = None
    if passees:
        recul, reference, _ = passees[0]
        reference_annee = annee - recul
        ref_debut, ref_fin = _decaler(debut, -recul), _decaler(fin, -recul)

        if serie.modele.fiable:
            dj_reels = sum(
                serie.djs[j]
                for j in serie.jours
                if ref_debut <= j <= ref_fin and j in serie.djs
            )
            dj_normal = dj_service.dj_normal_sur_periode(normales, ref_debut, ref_fin)
            if dj_reels > 0 and dj_normal > 0:
                reference_normalisee = max(
                    0.0, reference + serie.modele.k * (dj_normal - dj_reels)
                )

    return Prevision(
        annee=annee,
        realise=realise,
        jours_realises=jours_realises,
        estime_restant=estime,
        jours_restants=jours_restants,
        total_prevu=total_prevu,
        borne_basse=borne_basse,
        borne_haute=borne_haute,
        methode=methode,
        reference=reference,
        reference_annee=reference_annee,
        reference_normalisee=reference_normalisee,
        debut=debut,
        fin=fin,
        sur_releve_annuel=sur_releve_annuel,
    )


@dataclass
class ComparaisonGlissante:
    """Même portion d'année, cette année et l'an dernier."""

    jusqua: dt.date
    annee: int
    valeur: float
    annee_precedente: int
    valeur_precedente: float
    dj: float
    dj_precedent: float

    @property
    def evolution_pct(self) -> float | None:
        if not self.valeur_precedente:
            return None
        return 100.0 * (self.valeur - self.valeur_precedente) / self.valeur_precedente

    @property
    def evolution_dj_pct(self) -> float | None:
        if not self.dj_precedent:
            return None
        return 100.0 * (self.dj - self.dj_precedent) / self.dj_precedent


def comparer_a_annee_precedente(
    ligne: Ligne, *, aujourdhui: dt.date | None = None
) -> ComparaisonGlissante | None:
    """Compare le cumul depuis le 1er janvier à celui de l'an dernier à date."""
    serie = ligne.serie
    if not serie.jours:
        return None

    aujourdhui = aujourdhui or dt.date.today()
    annee = aujourdhui.year
    jours_annee = [j for j in serie.jours if j.year == annee]
    if not jours_annee:
        return None

    borne = max(jours_annee)
    try:
        borne_precedente = borne.replace(year=annee - 1)
    except ValueError:  # 29 février
        borne_precedente = borne.replace(year=annee - 1, day=28)

    valeur = serie.entre(dt.date(annee, 1, 1), borne)
    valeur_precedente = serie.entre(dt.date(annee - 1, 1, 1), borne_precedente)
    if valeur_precedente <= 0:
        return None

    dj = sum(
        v for j, v in serie.djs.items() if dt.date(annee, 1, 1) <= j <= borne
    )
    dj_precedent = sum(
        v
        for j, v in serie.djs.items()
        if dt.date(annee - 1, 1, 1) <= j <= borne_precedente
    )

    return ComparaisonGlissante(
        jusqua=borne,
        annee=annee,
        valeur=valeur,
        annee_precedente=annee - 1,
        valeur_precedente=valeur_precedente,
        dj=dj,
        dj_precedent=dj_precedent,
    )


# ---------------------------------------------------------------------------
# Rejeu : ce que la prévision aurait annoncé, comparé à ce qui s'est passé
# ---------------------------------------------------------------------------


@dataclass
class Borne:
    """Limite d'une période, et le relevé annuel qui s'y rattache.

    Le relevé ne tombe pas chaque année le même jour : ``volume_estime`` est la
    consommation, issue de la ventilation, des jours qui le séparent de la
    borne. C'est la seule part du total qui ne vient pas d'un index lu.
    """

    date: dt.date
    releve: dt.date | None
    volume_estime: float

    @property
    def ecart_jours(self) -> int | None:
        """Positif quand le relevé a été fait après la borne."""
        if self.releve is None:
            return None
        return (self.releve - self.date).days

    @property
    def jours_avant(self) -> int | None:
        return None if self.ecart_jours is None else -self.ecart_jours


def _borne(ligne: Ligne, jour: dt.date, annuels: list[dt.date]) -> Borne:
    proches = [a for a in annuels if abs((a - jour).days) <= TOLERANCE_BORNE_JOURS]
    if not proches:
        return Borne(date=jour, releve=None, volume_estime=0.0)
    releve = min(proches, key=lambda a: abs((a - jour).days))
    volume = ligne.serie.entre(min(releve, jour) + UN_JOUR, max(releve, jour))
    return Borne(date=jour, releve=releve, volume_estime=volume)


@dataclass
class PointRejeu:
    """Prévision telle qu'elle aurait été affichée à la date ``coupe``."""

    coupe: dt.date
    prevision: Prevision
    reel: float

    @property
    def ecart_pct(self) -> float | None:
        if not self.reel:
            return None
        return 100.0 * (self.prevision.total_prevu - self.reel) / self.reel

    @property
    def dans_fourchette(self) -> bool | None:
        p = self.prevision
        if p.borne_basse is None or p.borne_haute is None:
            return None
        return p.borne_basse <= self.reel <= p.borne_haute


@dataclass
class Rejeu:
    ligne: Ligne
    annee: int
    reel: float
    jours_reels: int
    points: list[PointRejeu]
    debut: dt.date
    fin: dt.date
    # Renseignées quand la période s'appuie sur les relevés annuels.
    borne_debut: Borne | None = None
    borne_fin: Borne | None = None

    @property
    def sur_releve_annuel(self) -> bool:
        return self.borne_debut is not None

    @property
    def bornes(self) -> list[tuple[str, Borne]]:
        if self.borne_debut is None or self.borne_fin is None:
            return []
        return [("Début", self.borne_debut), ("Fin", self.borne_fin)]

    @property
    def ecart_moyen_pct(self) -> float | None:
        """Moyenne des écarts absolus : les erreurs ne se compensent pas."""
        ecarts = [abs(p.ecart_pct) for p in self.points if p.ecart_pct is not None]
        return sum(ecarts) / len(ecarts) if ecarts else None


def _periodes_rejouables(ligne: Ligne) -> dict[int, tuple[dt.date, dt.date, bool]]:
    """Périodes closes d'une ligne : ``{millésime: (début, fin, sur relevé annuel)}``."""
    serie = ligne.serie
    if not serie.jours:
        return {}
    premier, dernier = serie.premier_jour, serie.dernier_jour

    annuels = releves_annuels(ligne)
    if annuels:
        debut, fin = periode_en_cours(annuels[-1], dernier)
        candidates = []
        while fin >= premier:
            candidates.append((debut, fin, True))
            debut, fin = _decaler(debut, -1), _decaler(fin, -1)
    else:
        candidates = [
            (dt.date(annee, 1, 1), dt.date(annee, 12, 31), False)
            for annee in range(premier.year, dernier.year + 1)
        ]

    periodes: dict[int, tuple[dt.date, dt.date, bool]] = {}
    for debut, fin, annuel in candidates:
        nb_jours = (fin - debut).days + 1
        # Sur relevé annuel, la période doit être réellement refermée : c'est
        # tout l'intérêt de la borne. L'année civile garde sa tolérance.
        if annuel and fin > dernier:
            continue
        if serie.nb_jours_couverts(debut, fin) >= nb_jours * SEUIL_COMPLETUDE:
            periodes[_millesime(debut)] = (debut, fin, annuel)
    return periodes


def annees_rejouables(maisons) -> list[int]:
    """Années pour lesquelles un total réel existe, la plus récente d'abord."""
    annees: set[int] = set()
    for maison in maisons:
        for ligne in cs.lignes_de(maison):
            annees.update(_periodes_rejouables(ligne))
    return sorted(annees, reverse=True)


def rejouer(maison: Maison, annee: int) -> list[Rejeu]:
    """Refait la prévision de ``annee`` à chacune de ses dates de relevé.

    À chaque date, tout ce qui est postérieur est masqué : les relevés, donc le
    modèle thermique et les profils saisonniers qui en découlent, ainsi que la
    normale climatique. Le total réel, lui, vient de l'historique complet.
    """
    resultat: list[Rejeu] = []
    normales_par_coupe: dict[dt.date, dict[tuple[int, int], float]] = {}

    for ligne in cs.lignes_de(maison):
        periode = _periodes_rejouables(ligne).get(annee)
        if periode is None:
            continue
        debut, fin, annuel = periode
        reel = ligne.serie.entre(debut, fin)
        # Le relevé qui ouvre la période compte : c'est la première prévision.
        coupes = sorted(
            set(
                Releve.objects.filter(
                    compteur__in=ligne.compteurs,
                    date__gte=debut - UN_JOUR,
                    date__lt=fin,
                ).values_list("date", flat=True)
            )
        )

        points: list[PointRejeu] = []
        for coupe in coupes:
            ligne_a_date = cs.ligne_unique(
                maison, ligne.energie, ligne.plage, jusqua=coupe
            )
            if ligne_a_date is None:
                continue
            if coupe not in normales_par_coupe:
                normales_par_coupe[coupe] = dj_service.dj_normaux_par_jour_calendaire(
                    maison.station, jusqua=coupe
                )
            prevision = prevoir(
                ligne_a_date,
                aujourdhui=coupe,
                normales=normales_par_coupe[coupe],
                **({"periode": (debut, fin)} if annuel else {"annee": annee}),
            )
            if prevision is None or prevision.jours_restants == 0:
                continue
            points.append(PointRejeu(coupe=coupe, prevision=prevision, reel=reel))

        if points:
            annuels = releves_annuels(ligne) if annuel else []
            resultat.append(
                Rejeu(
                    ligne=ligne,
                    annee=annee,
                    reel=reel,
                    jours_reels=ligne.serie.nb_jours_couverts(debut, fin),
                    points=points,
                    debut=debut,
                    fin=fin,
                    borne_debut=(
                        _borne(ligne, debut - UN_JOUR, annuels) if annuel else None
                    ),
                    borne_fin=_borne(ligne, fin, annuels) if annuel else None,
                )
            )
    return resultat
