/**
 * Text in die Zwischenablage legen.
 *
 * Die drei Aufrufstellen im Projekt hatten je eine eigene Kopie dieser sechs
 * Zeilen - mitsamt je eigener Fehlerbehandlung. Hier steht sie einmal.
 *
 * @returns ob es geklappt hat; der Aufrufer entscheidet über die Rückmeldung
 */
export function copyText(text: string): Promise<boolean> {
  if (!text) {
    return Promise.resolve(false);
  }
  return navigator.clipboard
    .writeText(text)
    .then(() => true)
    .catch(() => false);
}
