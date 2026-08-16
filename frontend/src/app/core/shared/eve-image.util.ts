/**
 * Adressen des offiziellen EVE-Bildservers und der Umgang mit fehlenden Bildern.
 *
 * <p>Das Gegenstück zu {@code EveImageUrls} im Backend. Die Adressen standen
 * zuvor an über zwanzig Stellen ausgeschrieben - in Komponenten wie in
 * Templates.</p>
 */

const BASE_URL = 'https://images.evetech.net';

/** Übliche Kantenlänge in Listen und Tabellen. */
export const SIZE_SMALL = 64;

/** Kantenlänge für hervorgehobene Darstellungen. */
export const SIZE_LARGE = 128;

/**
 * Tritanium - der Platzhalter für ein nicht ladbares Item-Bild.
 *
 * <p>Ein vorhandenes, unauffälliges Icon ist besser als ein gebrochenes Bild:
 * die Zeilenhöhe bleibt stabil und die Tabelle springt nicht.</p>
 */
const PLACEHOLDER_TYPE_ID = 34;

/** Platzhalter-IDs des Bildservers für unbekannte Charaktere bzw. Corporations. */
const PLACEHOLDER_CHARACTER_ID = 1;
const PLACEHOLDER_CORPORATION_ID = 1;

export function typeIcon(typeId: number, size: number = SIZE_SMALL): string {
  return `${BASE_URL}/types/${typeId}/icon?size=${size}`;
}

export function typeRender(typeId: number, size = 256): string {
  return `${BASE_URL}/types/${typeId}/render?size=${size}`;
}

export function portrait(characterId: number, size: number = SIZE_SMALL): string {
  return `${BASE_URL}/characters/${characterId}/portrait?size=${size}`;
}

export function corporationLogo(corporationId: number, size: number = SIZE_SMALL): string {
  return `${BASE_URL}/corporations/${corporationId}/logo?size=${size}`;
}

export function allianceLogo(allianceId: number, size = 32): string {
  return `${BASE_URL}/alliances/${allianceId}/logo?size=${size}`;
}

/**
 * Rückfallkette für Item-Bilder.
 *
 * <p>Blaupausen liegen beim Bildserver unter einem eigenen Pfad ({@code /bp}) und
 * nicht unter {@code /icon}. Schlägt das Icon fehl, wird deshalb zuerst die
 * Blaupausen-Variante versucht, erst danach der Platzhalter.</p>
 *
 * <p>Das erneute Setzen von {@code src} löst wieder ein Fehlerereignis aus, falls
 * auch das nicht lädt - deshalb bricht die Kette beim Platzhalter ausdrücklich ab.
 * Ohne diesen Abbruch liefe sie endlos.</p>
 */
export function handleTypeImageError(event: Event): void {
  const target = event.target as HTMLImageElement;

  if (target.src.includes(`/types/${PLACEHOLDER_TYPE_ID}/icon`)) return;

  if (target.src.includes('/icon?') || target.src.includes('/render?')) {
    const match = target.src.match(/\/types\/(\d+)\//);
    if (match) {
      target.src = `${BASE_URL}/types/${match[1]}/bp?size=${SIZE_SMALL}`;
      return;
    }
  }
  target.src = typeIcon(PLACEHOLDER_TYPE_ID);
}

/**
 * Platzhalter für ein nicht ladbares Besitzer-Bild.
 *
 * <p>Für Corp-Hangars muss der Platzhalter ein Corp-Logo sein: die
 * Charakter-Silhouette würde die Corporation als Spieler ausgeben.</p>
 */
export function handlePortraitError(event: Event, isCorp: boolean | null = false): void {
  (event.target as HTMLImageElement).src = isCorp
    ? corporationLogo(PLACEHOLDER_CORPORATION_ID)
    : portrait(PLACEHOLDER_CHARACTER_ID);
}

/**
 * Die eigene Corporation - ihr Logo dient als Markenzeichen der Anwendung.
 *
 * <p>Die ID stand zuvor in zwei Templates ausgeschrieben. Wandert die Anwendung
 * zu einer anderen Corporation, ist sie hier zu ändern.</p>
 */
const OWN_CORPORATION_ID = 98378388;

export const OWN_CORPORATION_LOGO = corporationLogo(OWN_CORPORATION_ID, SIZE_LARGE);
