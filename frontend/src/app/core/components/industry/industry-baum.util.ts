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
 * Der Rang eines Materials in der Fertigung: 0 ist Beschaffung, darüber wird gebaut.
 *
 * <p>Nicht aus {@code depth} abgeleitet, und das ist der Kern der Sache. Die
 * Tiefe ist der <em>kürzeste Weg zur Wurzel</em>, nicht die Fertigungsebene:
 * Tritanium, das ein Schiff unmittelbar braucht, steht auf Tiefe 1 — nach Tiefe
 * gruppiert säße es ganz oben neben dem Schiff, obwohl es das Erste ist, was man
 * kauft.</p>
 *
 * <p>Stattdessen zählt die Entscheidung: was gekauft wird, ist Rang 0. Was
 * gebaut wird, liegt eine Stufe über seinem höchsten bekannten Kind. Damit
 * behauptet die Achse nur, was die Daten hergeben.</p>
 *
 * <p>Der Zyklusschutz ist kein Zierrat: die Elternangaben stammen aus fremden
 * Stammdaten, und zwei Blaupausen, die einander als Material führen, würden die
 * Rekursion sonst nicht beenden.</p>
 */
export function raengeVon(zeilen: Requirement[]): Map<number, number> {
  const nachTyp = new Map(zeilen.map((r) => [r.typeId, r]));
  const kinder = new Map<number, number[]>();
  for (const r of zeilen) {
    if (r.parentTypeId == null) continue;
    const liste = kinder.get(r.parentTypeId);
    if (liste) liste.push(r.typeId);
    else kinder.set(r.parentTypeId, [r.typeId]);
  }

  const rang = new Map<number, number>();
  const laufend = new Set<number>();

  const von = (typeId: number): number => {
    const fertig = rang.get(typeId);
    if (fertig !== undefined) return fertig;

    const zeile = nachTyp.get(typeId);
    if (!zeile || zeile.decision !== 'BUILD') return 0;
    if (laufend.has(typeId)) return 0;

    laufend.add(typeId);
    let hoechstes = 0;
    for (const kind of kinder.get(typeId) ?? []) {
      hoechstes = Math.max(hoechstes, von(kind));
    }
    laufend.delete(typeId);

    // Gebaut heißt mindestens Rang eins - auch wenn kein Kind bekannt ist.
    const wert = Math.max(1, hoechstes + 1);
    rang.set(typeId, wert);
    return wert;
  };

  for (const r of zeilen) rang.set(r.typeId, von(r.typeId));
  return rang;
}

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
 * <p>Rang null ist das, was man kauft; darüber wird gebaut. Die oberste Stufe
 * traegt keinen eigenen Namen — dort steht das Endprodukt, und das nennt sich
 * selbst.</p>
 */
export function stufenLabel(rang: number, hoechsterRang: number): string {
  if (rang === 0) return 'Beschaffung';
  if (rang === hoechsterRang) return 'Endmontage';
  if (rang === 1) return 'Vorprodukte';
  return 'Bauteile';
}
