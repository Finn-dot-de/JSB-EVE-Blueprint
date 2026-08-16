import { Requirement } from '../../services/industry.service';

/**
 * Der Zustand eines Knotens im Fertigungsbaum.
 *
 * Bewusst fünf Werte und nicht drei: „fehlt Material" und „wartet auf ein
 * Vorprodukt" sehen in einer Tabelle gleich aus, verlangen aber Entgegengesetztes
 * — einmal einkaufen, einmal abwarten.
 */
export type Zustand = 'LAEUFT' | 'GEDECKT' | 'STARTKLAR' | 'FEHLT' | 'WARTET';

/**
 * Was mit einem Knoten gerade los ist.
 *
 * <p>Der Zustand kommt aus den <em>eigenen bekannten Kindern</em> und nicht aus
 * der Stufennummer. Das ist Absicht: die Ränge sind eine Näherung, weil eine
 * fehlende Elternangabe einen Knoten zu tief zieht. Steht er eine Zeile zu tief,
 * ist das ein Schönheitsfehler — behauptete er dagegen „startklar", weil seine
 * Stufe an der Reihe ist, wäre es eine Falschaussage.</p>
 *
 * <p>Ein laufender Job schlägt alles andere. Wer Material im Ofen hat, will das
 * sehen und nicht „gedeckt".</p>
 */
export function zustandVon(
  zeile: Requirement,
  kinder: Requirement[],
  laeuft: boolean,
): Zustand {
  if (laeuft) return 'LAEUFT';
  if (zeile.decision !== 'BUILD') {
    return zeile.missing <= 0 ? 'GEDECKT' : 'FEHLT';
  }
  // Ein Bauteil ist erst startklar, wenn seine Zutaten dastehen.
  const offeneKinder = kinder.filter((k) => k.missing > 0);
  if (offeneKinder.length > 0) return 'WARTET';
  return zeile.missing <= 0 ? 'GEDECKT' : 'STARTKLAR';
}

/** Wie ein Zustand heißt, wenn ihn jemand lesen soll. */
export function zustandLabel(zustand: Zustand): string {
  switch (zustand) {
    case 'LAEUFT':
      return 'läuft';
    case 'GEDECKT':
      return 'gedeckt';
    case 'STARTKLAR':
      return 'startklar';
    case 'WARTET':
      return 'wartet';
    default:
      return 'fehlt';
  }
}

/**
 * Wie eine Fertigungsstufe heißt.
 *
 * <p>Nummeriert statt benannt, und das ist der Punkt. „Vorprodukte" und
 * „Bauteile" klingen nach Materialklassen, waren aber nie welche — es waren
 * die Positionen 1 und 2 einer Zahlenreihe, und wer sie las, suchte nach einer
 * fachlichen Bedeutung, die es nicht gab.</p>
 *
 * <p>Eine Nummer sagt, was gemeint ist: Erst Schritt 1, dann Schritt 2. Die
 * Reihenfolge ist zugesichert — jedes Material liegt auf einer kleineren Stufe
 * als sein Produkt —, deshalb darf die Anzeige sie auch als Anweisung
 * ausgeben.</p>
 */
export function stufenLabel(rang: number, hoechsterRang: number): string {
  if (rang === 0) return 'Beschaffung';
  const name =
    rang === hoechsterRang ? 'Endmontage' : rang === 1 ? 'Vorprodukte' : 'Bauteile';
  return `Schritt ${rang} · ${name}`;
}

/**
 * Was in dieser Stufe zu tun ist — Reaktion, Fertigung oder beides.
 *
 * <p>Der Wunsch „erst alle Reaktionen, dann die Fertigung" ist global nicht
 * immer erfüllbar: Es gibt Aufträge, in denen auf Stufe 1 gefertigt und auf
 * Stufe 2 reagiert wird. Die Abhängigkeit hat Vorrang, sonst wäre die
 * Anleitung nicht ausführbar. Wo eine Stufe gemischt ist, sagt sie das —
 * statt eine Sortierung vorzutäuschen, die die Daten nicht hergeben.</p>
 */
export function aktivitaetenLabel(zeilen: Requirement[]): string {
  const gebaut = zeilen.filter((r) => r.decision === 'BUILD');
  const reaktion = gebaut.some((r) => r.sourceKind === 'REACTION');
  const fertigung = gebaut.some((r) => r.sourceKind === 'BUILDABLE');
  if (reaktion && fertigung) return 'Reaktion + Fertigung';
  if (reaktion) return 'Reaktion';
  if (fertigung) return 'Fertigung';
  return 'Einkauf';
}
