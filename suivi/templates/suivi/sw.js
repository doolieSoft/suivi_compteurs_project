/* Service worker du suivi de compteurs.
 *
 * Deux stratégies, selon ce qui est demandé :
 *
 *  - les ressources d'habillage (CSS, JavaScript, icônes) changent rarement :
 *    on les sert depuis le cache, et on les rafraîchit en arrière-plan ;
 *  - les pages, elles, portent des données qui bougent : on va d'abord au
 *    réseau, et le cache ne sert que de filet quand le PC est éteint.
 *
 * L'inverse afficherait des consommations périmées sans prévenir, ce qui est
 * pire que pas d'affichage du tout.
 */
// Change dès qu'une ressource est modifiée, ce qui purge l'ancien cache.
const VERSION = "suivi-{{ version }}";
const COQUILLE = [
{% for ressource in ressources %}  "{{ ressource }}",
{% endfor %}  "{{ hors_ligne }}"
];

self.addEventListener("install", (evenement) => {
  evenement.waitUntil(
    caches.open(VERSION)
      .then((cache) => cache.addAll(COQUILLE))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener("activate", (evenement) => {
  evenement.waitUntil(
    caches.keys()
      .then((noms) => Promise.all(
        noms.filter((nom) => nom !== VERSION).map((nom) => caches.delete(nom))
      ))
      .then(() => self.clients.claim())
  );
});

function estRessource(url) {
  return url.pathname.startsWith("/static/");
}

self.addEventListener("fetch", (evenement) => {
  const requete = evenement.request;
  if (requete.method !== "GET") return;

  const url = new URL(requete.url);
  if (url.origin !== self.location.origin) return;

  // Ni l'API ni l'administration ne doivent être mises en cache : on y écrit.
  if (url.pathname.startsWith("/api/") || url.pathname.startsWith("/admin/")) return;

  if (estRessource(url)) {
    evenement.respondWith(
      caches.match(requete).then((enCache) => {
        const reseau = fetch(requete).then((reponse) => {
          if (reponse.ok) {
            const copie = reponse.clone();
            caches.open(VERSION).then((cache) => cache.put(requete, copie));
          }
          return reponse;
        }).catch(() => enCache);
        return enCache || reseau;
      })
    );
    return;
  }

  evenement.respondWith(
    fetch(requete)
      .then((reponse) => {
        if (reponse.ok) {
          const copie = reponse.clone();
          caches.open(VERSION).then((cache) => cache.put(requete, copie));
        }
        return reponse;
      })
      .catch(() =>
        caches.match(requete).then((enCache) =>
          enCache || caches.match("{{ hors_ligne }}")
        )
      )
  );
});
