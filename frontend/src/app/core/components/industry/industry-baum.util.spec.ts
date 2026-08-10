import { describe, expect, it } from 'vitest';
import { Requirement } from '../../services/industry.service';
import { raengeVon, stufenLabel, zustandVon } from './industry-baum.util';

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
    depth: 1,
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
describe('raengeVon', () => {
  it('setzt alles Gekaufte auf Rang null', () => {
    // Was man kauft, ist Beschaffung - unabhängig davon, wie tief es im
    // Stücklistenbaum hängt.
    const raenge = raengeVon([
      zeile({ typeId: 34, decision: 'BUY', depth: 1 }),
      zeile({ typeId: 35, decision: 'BUY', depth: 4 }),
    ]);

    expect(raenge.get(34)).toBe(0);
    expect(raenge.get(35)).toBe(0);
  });

  it('legt Gebautes über sein höchstes bekanntes Kind', () => {
    // Kette: Tritanium (kaufen) -> Bauteil (bauen) -> Baugruppe (bauen).
    const raenge = raengeVon([
      zeile({ typeId: 34, decision: 'BUY', parentTypeId: 100 }),
      zeile({ typeId: 100, decision: 'BUILD', parentTypeId: 200 }),
      zeile({ typeId: 200, decision: 'BUILD', parentTypeId: null }),
    ]);

    expect(raenge.get(34)).toBe(0);
    expect(raenge.get(100)).toBe(1);
    expect(raenge.get(200)).toBe(2);
  });

  it('nutzt die Tiefe ausdrücklich NICHT als Rang', () => {
    // Der Kern der Sache. Die Tiefe ist der kürzeste Weg zur Wurzel: ein
    // Mineral, das das Endprodukt unmittelbar braucht, steht auf Tiefe 1.
    // Nach Tiefe gruppiert säße es ganz oben neben dem Schiff, obwohl es das
    // Erste ist, was man einkauft.
    const raenge = raengeVon([
      zeile({ typeId: 34, decision: 'BUY', depth: 1 }),
      zeile({ typeId: 100, decision: 'BUILD', depth: 1, parentTypeId: null }),
      zeile({ typeId: 35, decision: 'BUY', depth: 2, parentTypeId: 100 }),
    ]);

    // Gleiche Tiefe, entgegengesetzter Rang.
    expect(raenge.get(34)).toBe(0);
    expect(raenge.get(100)).toBe(1);
  });

  it('gibt einem gebauten Teil ohne bekannte Kinder mindestens Rang eins', () => {
    // Sonst stünde ein Bauteil, dessen Materialien unter einem anderen
    // Elternteil verbucht sind, in der Beschaffung.
    const raenge = raengeVon([zeile({ typeId: 100, decision: 'BUILD' })]);

    expect(raenge.get(100)).toBe(1);
  });

  it('bleibt bei einem Zyklus in den Daten stehen', () => {
    // Die Elternangaben stammen aus fremden Stammdaten. Zwei Blaupausen, die
    // einander als Material führen, dürfen die Seite nicht aufhängen.
    const raenge = raengeVon([
      zeile({ typeId: 1, decision: 'BUILD', parentTypeId: 2 }),
      zeile({ typeId: 2, decision: 'BUILD', parentTypeId: 1 }),
    ]);

    expect(raenge.get(1)).toBeGreaterThanOrEqual(1);
    expect(raenge.get(2)).toBeGreaterThanOrEqual(1);
  });

  it('legt jedes gebaute Teil echt über jedes seiner bekannten Kinder', () => {
    // Die Monotonie ist die Zusage der Achse. Ohne sie könnte ein Bauteil
    // unter seinem eigenen Material stehen.
    const zeilen = [
      zeile({ typeId: 34, decision: 'BUY', parentTypeId: 100 }),
      zeile({ typeId: 35, decision: 'BUY', parentTypeId: 100 }),
      zeile({ typeId: 100, decision: 'BUILD', parentTypeId: 200 }),
      zeile({ typeId: 101, decision: 'BUILD', parentTypeId: 200 }),
      zeile({ typeId: 200, decision: 'BUILD', parentTypeId: null }),
    ];
    const raenge = raengeVon(zeilen);

    for (const z of zeilen) {
      if (z.decision !== 'BUILD' || z.parentTypeId == null) continue;
      const eltern = raenge.get(z.parentTypeId);
      if (eltern === undefined) continue;
      expect(eltern).toBeGreaterThan(raenge.get(z.typeId)!);
    }
  });
});

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
  it('nennt Rang null Beschaffung und die oberste Stufe Endmontage', () => {
    expect(stufenLabel(0, 3)).toBe('Beschaffung');
    expect(stufenLabel(3, 3)).toBe('Endmontage');
    expect(stufenLabel(1, 3)).toBe('Vorprodukte');
    expect(stufenLabel(2, 3)).toBe('Bauteile');
  });
});
