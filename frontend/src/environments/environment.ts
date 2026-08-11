/*
 * Für die Entwicklung: der Browser läuft auf dem Host, und der Backend-Port
 * ist veröffentlicht - die absolute Adresse trifft also. Für Produktion gilt
 * environment.prod.ts mit einem relativen Pfad; siehe dort.
 */
export const environment = {
  production: false,
  apiUrl: 'http://localhost:8080/api'
};
