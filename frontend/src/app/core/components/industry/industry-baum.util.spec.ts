import { describe, expect, it } from 'vitest';
import { Requirement } from '../../services/industry.service';
import { aktivitaetenLabel, stufenLabel, zustandVon } from './industry-baum.util';

function zeile(over: Partial<Requirement> & { typeId: number }): Requirement {
  return {
    typeName: 'X',
    needed: 100,
    have: 0,
    haveElsewhere: 0,
    missing: 100,
    sourceKind: 'BUILDABLE',
    buildable: true,
    decision: 'BUY',
    alreadyBuilt: 0,
    depth: 1,
    buildLevel: 0,
    parentTypeId: null,
    unitPrice: null,
    priceMissing: false,
    packagedVolume: 1,
    onCharacters: 0,
    ...over,
  };
}

/**
 * Die Rangregel trägt die ganze Ansicht: sie entscheidet, was unten steht und
 * was oben. Geht sie kaputt, steht die Fertigung auf dem Kopf - und zwar
 * lautlos, weil die Seite weiterhin gut aussieht.
 */
describe('zustandVon', () => {
  it('lässt einen laufenden Job alles andere schlagen', () => {
    // Wer Material im Ofen hat, will das sehen und nicht "gedeckt".
    const z = zeile({ typeId: 1, missing: 0, decision: 'BUILD' });

    expect(zustandVon(z, [], true)).toBe('LAEUFT');
  });

  it('unterscheidet startklar von wartet', () => {
    // Gleiche Zeile, gleiche Entscheidung, gleiches missing - der Unterschied
    // liegt allein im Zustand der Kinder. In einer Tabelle sähe beides gleich
    // aus, verlangt aber Entgegengesetztes: einkaufen oder abwarten.
    const teil = zeile({ typeId: 100, decision: 'BUILD', missing: 5 });
    const fertigesKind = zeile({ typeId: 34, missing: 0 });
    const offenesKind = zeile({ typeId: 35, missing: 900 });

    expect(zustandVon(teil, [fertigesKind], false)).toBe('STARTKLAR');
    expect(zustandVon(teil, [offenesKind], false)).toBe('WARTET');
  });

  it('nennt eine gedeckte Kaufzeile gedeckt und eine offene fehlend', () => {
    expect(zustandVon(zeile({ typeId: 34, decision: 'BUY', missing: 0 }), [], false))
      .toBe('GEDECKT');
    expect(zustandVon(zeile({ typeId: 34, decision: 'BUY', missing: 7 }), [], false))
      .toBe('FEHLT');
  });
});

describe('stufenLabel', () => {
  it('nummeriert die Schritte und behält die Namen', () => {
    // Die Nummer sagt, was zuerst dran ist; der Name sagt, was es ist.
    // Ohne Nummer musste man die Reihenfolge raten, ohne Name klang jede
    // Stufe gleich.
    expect(stufenLabel(0, 3)).toBe('Beschaffung');
    expect(stufenLabel(3, 3)).toBe('Schritt 3 · Endmontage');
    expect(stufenLabel(1, 3)).toBe('Schritt 1 · Vorprodukte');
    expect(stufenLabel(2, 3)).toBe('Schritt 2 · Bauteile');
  });
});

describe('aktivitaetenLabel', () => {
  it('nennt beides, wenn eine Stufe gemischt ist', () => {
    // "Erst alle Reaktionen, dann die Fertigung" ist nicht immer erfüllbar -
    // die Abhängigkeit hat Vorrang, sonst wäre die Anleitung nicht
    // ausführbar. Wo eine Stufe gemischt ist, muss sie das sagen, statt eine
    // Sortierung vorzutäuschen.
    expect(
      aktivitaetenLabel([
        zeile({ typeId: 1, decision: 'BUILD', sourceKind: 'REACTION' }),
        zeile({ typeId: 2, decision: 'BUILD', sourceKind: 'BUILDABLE' }),
      ]),
    ).toBe('Reaktion + Fertigung');
  });

  it('nennt die eine Tätigkeit, wenn die Stufe rein ist', () => {
    expect(
      aktivitaetenLabel([zeile({ typeId: 1, decision: 'BUILD', sourceKind: 'REACTION' })]),
    ).toBe('Reaktion');
    expect(
      aktivitaetenLabel([zeile({ typeId: 2, decision: 'BUILD', sourceKind: 'BUILDABLE' })]),
    ).toBe('Fertigung');
  });

  it('nennt eine Stufe ohne gebaute Zeile Einkauf', () => {
    // Gekauftes ist kein Arbeitsschritt. Es als "Fertigung" zu etikettieren,
    // nur weil das Material baubar wäre, wäre eine Arbeitsanweisung zuviel.
    expect(
      aktivitaetenLabel([zeile({ typeId: 3, decision: 'BUY', sourceKind: 'BUILDABLE' })]),
    ).toBe('Einkauf');
    expect(aktivitaetenLabel([])).toBe('Einkauf');
  });
});
