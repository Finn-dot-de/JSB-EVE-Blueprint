import { describe, expect, it } from 'vitest';
import {
  OWN_CORPORATION_LOGO,
  SIZE_LARGE,
  allianceLogo,
  corporationLogo,
  handlePortraitError,
  handleTypeImageError,
  portrait,
  typeIcon,
  typeRender,
} from './eve-image.util';

/** Ein Bild-Element, das sich wie im Browser verhält. */
function imageWith(src: string): { target: HTMLImageElement } {
  return { target: { src } as HTMLImageElement };
}

describe('Adressen des EVE-Bildservers', () => {
  it('baut Portraits mit Standard- und Wunschgröße', () => {
    expect(portrait(95465499)).toBe(
      'https://images.evetech.net/characters/95465499/portrait?size=64',
    );
    expect(portrait(95465499, SIZE_LARGE)).toBe(
      'https://images.evetech.net/characters/95465499/portrait?size=128',
    );
  });

  it('baut Corporation- und Allianz-Logos', () => {
    expect(corporationLogo(98378388)).toContain('/corporations/98378388/logo?size=64');
    expect(allianceLogo(99005338, 32)).toContain('/alliances/99005338/logo?size=32');
  });

  it('baut Item-Symbole und Schiffsansichten', () => {
    expect(typeIcon(34)).toBe('https://images.evetech.net/types/34/icon?size=64');
    expect(typeIcon(34, 32)).toContain('size=32');
    expect(typeRender(17738)).toBe('https://images.evetech.net/types/17738/render?size=256');
  });

  it('stellt das eigene Corp-Logo als feste Adresse bereit', () => {
    expect(OWN_CORPORATION_LOGO).toContain('/corporations/');
  });
});

describe('Rückfall bei nicht ladbaren Bildern', () => {
  it('versucht bei einem fehlenden Icon zuerst die Blaupausen-Variante', () => {
    // Blaupausen liegen beim Bildserver unter einem eigenen Pfad.
    const event = imageWith('https://images.evetech.net/types/1002/icon?size=64');

    handleTypeImageError(event as unknown as Event);

    expect(event.target.src).toBe('https://images.evetech.net/types/1002/bp?size=64');
  });

  it('greift auch bei einer fehlenden Schiffsansicht', () => {
    const event = imageWith('https://images.evetech.net/types/1002/render?size=256');

    handleTypeImageError(event as unknown as Event);

    expect(event.target.src).toContain('/types/1002/bp');
  });

  it('zeigt den Platzhalter, wenn auch die Blaupause fehlt', () => {
    const event = imageWith('https://images.evetech.net/types/1002/bp?size=64');

    handleTypeImageError(event as unknown as Event);

    expect(event.target.src).toBe('https://images.evetech.net/types/34/icon?size=64');
  });

  it('bricht beim Platzhalter ab, statt endlos weiterzuversuchen', () => {
    const placeholder = 'https://images.evetech.net/types/34/icon?size=64';
    const event = imageWith(placeholder);

    handleTypeImageError(event as unknown as Event);

    expect(event.target.src).toBe(placeholder);
  });

  it('zeigt für Spieler eine Charakter-Silhouette', () => {
    const event = imageWith('https://images.evetech.net/characters/1234/portrait?size=64');

    handlePortraitError(event as unknown as Event);

    expect(event.target.src).toContain('/characters/1/portrait');
  });

  it('zeigt für Corp-Hangars ein Corp-Logo', () => {
    // Eine Charakter-Silhouette würde die Corporation als Spieler ausgeben.
    const event = imageWith('https://images.evetech.net/corporations/98378388/logo?size=64');

    handlePortraitError(event as unknown as Event, true);

    expect(event.target.src).toContain('/corporations/1/logo');
  });
});
