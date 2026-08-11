/*
 * apiUrl ist RELATIV, und das ist keine Nachlässigkeit.
 *
 * Warum relativ: nginx reicht /api/ an den Backend-Container weiter
 * (frontend/nginx.conf). Eine absolute Adresse auf localhost:8080 zeigt im
 * Browser jedes Nutzers auf DESSEN Rechner - in Produktion funktioniert das
 * nur, wenn man zufällig auf dem Server selbst surft. Genau daran lag es.
 *
 * Nebenbei: angular.json hat keine fileReplacements, environment.prod.ts wird
 * also gar nicht eingesetzt. Beide Dateien tragen denselben Wert, damit es
 * keine Rolle spielt, welche greift.
 */
export const environment = {
  production: true,
  apiUrl: '/api'
};
