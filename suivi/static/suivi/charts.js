/* Socle graphique commun.
 *
 * Les couleurs sont lues sur les variables CSS, jamais codées en dur : le mode
 * sombre se contente donc de redéfinir la variable, et les graphiques suivent.
 * Aucun graphique n'utilise deux axes verticaux — deux grandeurs d'échelles
 * différentes donnent deux graphiques superposés partageant l'axe horizontal.
 */
(function (global) {
  "use strict";

  var MOIS = ["janv.", "févr.", "mars", "avr.", "mai", "juin",
              "juil.", "août", "sept.", "oct.", "nov.", "déc."];

  function couleur(nom) {
    return getComputedStyle(document.documentElement)
      .getPropertyValue("--" + nom).trim();
  }

  var instances = [];

  function palette() {
    return {
      surface: couleur("surface"),
      encre: couleur("encre"),
      encre2: couleur("encre-2"),
      encre3: couleur("encre-3"),
      grille: couleur("grille"),
      axe: couleur("axe"),
      series: [1, 2, 3, 4, 5, 6].map(function (n) { return couleur("serie-" + n); })
    };
  }

  function nombre(valeur, decimales) {
    if (valeur === null || valeur === undefined || isNaN(valeur)) return "—";
    return valeur.toLocaleString("fr-BE", {
      minimumFractionDigits: decimales === undefined ? 0 : decimales,
      maximumFractionDigits: decimales === undefined ? 0 : decimales
    });
  }

  /** Bases communes : grille discrète, infobulle sobre, légende en haut. */
  function base(p, unite) {
    return {
      responsive: true,
      maintainAspectRatio: false,
      interaction: { mode: "index", intersect: false },
      layout: { padding: { top: 4, right: 8 } },
      plugins: {
        legend: {
          display: true,
          position: "top",
          align: "start",
          labels: {
            boxWidth: 10,
            boxHeight: 10,
            padding: 14,
            color: p.encre2,
            font: { size: 12.5, family: "system-ui, -apple-system, sans-serif" },
            usePointStyle: true,
            pointStyle: "rectRounded"
          }
        },
        tooltip: {
          backgroundColor: p.surface,
          titleColor: p.encre,
          bodyColor: p.encre2,
          borderColor: p.axe,
          borderWidth: 1,
          padding: 10,
          cornerRadius: 8,
          displayColors: true,
          boxWidth: 9,
          boxHeight: 9,
          usePointStyle: true,
          titleFont: { size: 13, weight: "600" },
          bodyFont: { size: 12.5 },
          callbacks: {
            label: function (ctx) {
              var v = ctx.parsed.y;
              if (v === null || v === undefined) return null;
              return " " + ctx.dataset.label + " : " + nombre(v, 1) + " " + unite;
            }
          }
        }
      },
      scales: {
        x: {
          grid: { display: false },
          border: { color: p.axe },
          ticks: { color: p.encre3, font: { size: 12 } }
        },
        y: {
          beginAtZero: true,
          grid: { color: p.grille, drawTicks: false },
          border: { display: false },
          ticks: {
            color: p.encre3,
            font: { size: 12 },
            padding: 8,
            callback: function (v) { return nombre(v); }
          },
          title: {
            display: !!unite,
            text: unite,
            color: p.encre3,
            font: { size: 11.5 }
          }
        }
      }
    };
  }

  function enregistrer(chart, refaire) {
    instances.push({ chart: chart, refaire: refaire });
    return chart;
  }

  /* --- Barres groupées : mesuré vs corrigé du climat ------------------- */

  function barresGroupees(canvas, donnees) {
    var p = palette();
    var options = base(p, donnees.unite);

    options.plugins.tooltip.callbacks.afterBody = function (items) {
      var i = items[0].dataIndex;
      var lignes = [];
      if (donnees.dj && donnees.dj[i] !== undefined) {
        lignes.push("Rigueur du climat : " + nombre(donnees.dj[i]) + " degrés-jours");
      }
      if (donnees.complete && donnees.complete[i] === false) {
        lignes.push("Année incomplète");
      }
      return lignes;
    };

    var jeux = [{
      label: "Consommation mesurée",
      data: donnees.brut,
      backgroundColor: p.series[0],
      borderRadius: 4,
      borderSkipped: "bottom",
      // Le liseré à la couleur de la surface crée l'écart de 2 px entre barres.
      borderColor: p.surface,
      borderWidth: { top: 0, right: 1, bottom: 0, left: 1 },
      maxBarThickness: 46
    }];

    if (donnees.normalise && donnees.normalise.some(function (v) { return v !== null; })) {
      jeux.push({
        label: "Corrigée du climat",
        data: donnees.normalise,
        backgroundColor: p.series[1],
        borderRadius: 4,
        borderSkipped: "bottom",
        borderColor: p.surface,
        borderWidth: { top: 0, right: 1, bottom: 0, left: 1 },
        maxBarThickness: 46
      });
    } else {
      options.plugins.legend.display = false;
    }

    return enregistrer(new Chart(canvas, {
      type: "bar",
      data: { labels: donnees.labels, datasets: jeux },
      options: options
    }), function () { barresGroupees(canvas, donnees); });
  }

  /* --- Barres simples, avec repère de référence facultatif ------------- */

  function barresSimples(canvas, donnees) {
    var p = palette();
    var options = base(p, donnees.unite);
    options.plugins.legend.display = false;
    options.interaction = { mode: "nearest", intersect: true };

    if (donnees.etiquettesSelectives) {
      // Cinq ans de mois donnent soixante graduations illisibles : on ne garde
      // que les janviers, qui suffisent à situer chaque hiver.
      options.scales.x.ticks.autoSkip = false;
      options.scales.x.ticks.maxRotation = 0;
      options.scales.x.ticks.callback = function (_, i) {
        var etiquette = donnees.labels[i] || "";
        return etiquette.indexOf("janv.") === 0 ? etiquette.slice(6) : null;
      };
    }

    var teinte = donnees.couleur ? couleur(donnees.couleur) : p.series[0];
    var jeux = [{
      label: donnees.libelle || "Consommation",
      data: donnees.valeurs,
      backgroundColor: teinte,
      borderRadius: 4,
      borderSkipped: "bottom",
      borderColor: p.surface,
      borderWidth: { top: 0, right: 1, bottom: 0, left: 1 },
      maxBarThickness: 40
    }];

    if (donnees.reference) {
      jeux.push({
        label: donnees.libelleReference || "Référence",
        data: donnees.labels.map(function () { return donnees.reference; }),
        type: "line",
        borderColor: p.encre3,
        borderWidth: 2,
        borderDash: [5, 4],
        pointRadius: 0,
        fill: false
      });
      options.plugins.legend.display = true;
    }

    return enregistrer(new Chart(canvas, {
      type: "bar",
      data: { labels: donnees.labels, datasets: jeux },
      options: options
    }), function () { barresSimples(canvas, donnees); });
  }

  /* --- Cumuls annuels superposés (x = jour de l'année) ----------------- */

  function cumulsAnnuels(canvas, donnees) {
    var p = palette();
    var options = base(p, donnees.unite);
    var annees = donnees.annees.map(String);

    options.scales.x = {
      type: "linear",
      min: 1,
      max: 366,
      grid: { display: false },
      border: { color: p.axe },
      ticks: {
        color: p.encre3,
        font: { size: 12 },
        stepSize: 1,
        autoSkip: false,
        callback: function (v) {
          // Une graduation au premier jour de chaque mois.
          var debuts = [1, 32, 60, 91, 121, 152, 182, 213, 244, 274, 305, 335];
          var i = debuts.indexOf(v);
          return i === -1 ? null : MOIS[i];
        }
      }
    };

    options.plugins.tooltip.callbacks.title = function (items) {
      return "Jour " + items[0].parsed.x + " de l'année";
    };
    options.plugins.tooltip.callbacks.label = function (ctx) {
      return " " + ctx.dataset.label + " : " + nombre(ctx.parsed.y, 1) + " " + donnees.unite;
    };

    var jeux = annees.map(function (annee, i) {
      var recent = i === annees.length - 1;
      return {
        label: annee,
        data: donnees.series[annee].map(function (pt) { return { x: pt.j, y: pt.v }; }),
        borderColor: p.series[i % p.series.length],
        backgroundColor: p.series[i % p.series.length],
        borderWidth: recent ? 2.5 : 2,
        pointRadius: 0,
        pointHoverRadius: 4,
        tension: 0.08,
        fill: false
      };
    });

    return enregistrer(new Chart(canvas, {
      type: "line",
      data: { datasets: jeux },
      options: options
    }), function () { cumulsAnnuels(canvas, donnees); });
  }

  /* --- Signature énergétique : conso/jour en fonction des DJ/jour ------ */

  function signature(canvas, donnees) {
    var p = palette();
    var options = base(p, donnees.unite + "/jour");
    options.interaction = { mode: "nearest", intersect: true };
    options.scales.x = {
      type: "linear",
      beginAtZero: true,
      grid: { color: p.grille, drawTicks: false },
      border: { display: false },
      ticks: { color: p.encre3, font: { size: 12 } },
      title: {
        display: true,
        text: "degrés-jours par jour",
        color: p.encre3,
        font: { size: 11.5 }
      }
    };
    options.plugins.tooltip.callbacks = {
      title: function (items) { return items[0].raw.d || ""; },
      label: function (ctx) {
        if (ctx.dataset.type === "line") return null;
        return [
          " " + nombre(ctx.parsed.y, 2) + " " + donnees.unite + "/jour",
          " " + nombre(ctx.parsed.x, 1) + " DJ/jour sur " + ctx.raw.n + " jours"
        ];
      }
    };

    var jeux = [{
      label: "Périodes relevées",
      data: donnees.points,
      backgroundColor: p.series[0],
      borderColor: p.surface,
      borderWidth: 2,
      pointRadius: 5,
      pointHoverRadius: 8,
      type: "scatter"
    }];

    if (donnees.droite && donnees.droite.length) {
      jeux.push({
        label: "Modèle ajusté",
        data: donnees.droite,
        type: "line",
        borderColor: p.series[1],
        borderWidth: 2,
        pointRadius: 0,
        fill: false
      });
    }

    return enregistrer(new Chart(canvas, {
      type: "scatter",
      data: { datasets: jeux },
      options: options
    }), function () { signature(canvas, donnees); });
  }

  /* --- Index bruts relevés dans le temps ------------------------------- */

  function index(canvas, donnees) {
    var p = palette();
    var options = base(p, donnees.unite);
    options.scales.y.beginAtZero = false;
    options.interaction = { mode: "nearest", intersect: false };
    // Une graduation au 1er janvier de chaque année : sur un axe linéaire,
    // Chart.js placerait sinon ses repères à des dates arbitraires.
    var tous = donnees.series.reduce(function (acc, s) {
      return acc.concat(s.points.map(function (pt) { return new Date(pt.t).getTime(); }));
    }, []);
    var debuts = [];
    if (tous.length) {
      var an = new Date(Math.min.apply(null, tous)).getFullYear();
      var anFin = new Date(Math.max.apply(null, tous)).getFullYear();
      for (; an <= anFin + 1; an++) debuts.push(new Date(an, 0, 1).getTime());
    }

    options.scales.x = {
      type: "linear",
      grid: { display: false },
      border: { color: p.axe },
      afterBuildTicks: function (echelle) {
        if (debuts.length) {
          echelle.ticks = debuts.map(function (v) { return { value: v }; });
        }
      },
      ticks: {
        color: p.encre3,
        font: { size: 12 },
        maxRotation: 0,
        autoSkip: false,
        callback: function (v) { return new Date(v).getFullYear(); }
      }
    };
    options.plugins.tooltip.callbacks.title = function (items) {
      return new Date(items[0].parsed.x).toLocaleDateString("fr-BE");
    };

    var jeux = donnees.series.map(function (serie, i) {
      return {
        label: serie.compteur,
        data: serie.points.map(function (pt) {
          return { x: new Date(pt.t).getTime(), y: pt.v };
        }),
        borderColor: p.series[i % p.series.length],
        backgroundColor: p.series[i % p.series.length],
        borderWidth: 2,
        pointRadius: 3,
        pointHoverRadius: 6,
        tension: 0,
        fill: false
      };
    });

    options.plugins.legend.display = jeux.length > 1;

    return enregistrer(new Chart(canvas, {
      type: "line",
      data: { datasets: jeux },
      options: options
    }), function () { index(canvas, donnees); });
  }

  /* --- Suivi du thème -------------------------------------------------- */

  if (global.matchMedia) {
    global.matchMedia("(prefers-color-scheme: dark)").addEventListener("change", function () {
      var anciennes = instances.slice();
      instances.length = 0;
      anciennes.forEach(function (item) {
        item.chart.destroy();
        item.refaire();
      });
    });
  }

  function donneesDe(id) {
    var noeud = document.getElementById(id);
    return noeud ? JSON.parse(noeud.textContent) : null;
  }

  global.Suivi = {
    barresGroupees: barresGroupees,
    barresSimples: barresSimples,
    cumulsAnnuels: cumulsAnnuels,
    signature: signature,
    index: index,
    donneesDe: donneesDe,
    nombre: nombre
  };
})(window);
