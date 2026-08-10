import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { IndustryComponent } from './industry.component';
import {
  BlueprintCheck,
  BuildLocation,
  IndustryService,
  OrderDetail,
  OrderSummary,
  PlanPreview,
  ProductHit,
  Requirement,
} from '../../services/industry.service';

/** Legt die Komponente im Injektionskontext an - latestRequest braucht ihn. */
function build(): IndustryComponent {
  return TestBed.runInInjectionContext(() => new IndustryComponent());
}

function requirement(over: Partial<Requirement> = {}): Requirement {
  return {
    typeId: 34,
    typeName: 'Tritanium',
    needed: 1000,
    have: 400,
    missing: 600,
    sourceKind: 'MINERAL',
    buildable: false,
    decision: 'BUY',
    depth: 1,
    parentTypeId: null,
    unitPrice: null,
    priceMissing: true,
    packagedVolume: 1,
    onCharacters: 2,
    haveElsewhere: 0,
    ...over,
  };
}

function preview(over: Partial<PlanPreview> = {}): PlanPreview {
  return {
    productTypeId: 638,
    productName: 'Raven',
    quantity: 50,
    summary: {
      jobCount: 5,
      runsPerJob: 10,
      totalRuns: 50,
      jobSeconds: 900_000,
      materialCount: 10,
      packagedVolume: 2_500_000,
      materialEfficiency: 0,
      timeEfficiency: 0,
      blueprintFound: true,
      blueprintOwned: true,
    },
    requirements: [requirement()],
    ...over,
  };
}

function order(over: Partial<OrderSummary> = {}): OrderSummary {
  return {
    id: 1,
    productTypeId: 638,
    productName: 'Raven',
    targetQuantity: 50,
    status: 'ACTIVE',
    buildLocationName: null,
    progress: {
      target: 50,
      delivered: 10,
      inProgress: 0,
      percent: 20,
      coveredUnits: 30,
      openJobs: 0,
    },
    createdAt: null,
    ...over,
  };
}

describe('IndustryComponent', () => {
  let industry: {
    search: ReturnType<typeof vi.fn>;
    preview: ReturnType<typeof vi.fn>;
    orders: ReturnType<typeof vi.fn>;
    order: ReturnType<typeof vi.fn>;
    create: ReturnType<typeof vi.fn>;
    decide: ReturnType<typeof vi.fn>;
    cancel: ReturnType<typeof vi.fn>;
    remove: ReturnType<typeof vi.fn>;
    locations: ReturnType<typeof vi.fn>;
    procurement: ReturnType<typeof vi.fn>;
    blueprints: ReturnType<typeof vi.fn>;
    applyStrategy: ReturnType<typeof vi.fn>;
    recalculate: ReturnType<typeof vi.fn>;
    setBuildLocation: ReturnType<typeof vi.fn>;
  };

  beforeEach(() => {
    industry = {
      search: vi.fn().mockReturnValue(of([])),
      preview: vi.fn().mockReturnValue(of(preview())),
      orders: vi.fn().mockReturnValue(of([])),
      order: vi.fn().mockReturnValue(of({} as OrderDetail)),
      create: vi.fn(),
      decide: vi.fn(),
      cancel: vi.fn().mockReturnValue(of(void 0)),
      remove: vi.fn().mockReturnValue(of(void 0)),
      locations: vi.fn().mockReturnValue(of([])),
      procurement: vi.fn().mockReturnValue(of({ lines: [], locationChosen: false })),
      blueprints: vi.fn().mockReturnValue(of([])),
      applyStrategy: vi.fn(),
      recalculate: vi.fn(),
      setBuildLocation: vi.fn(),
    };
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [{ provide: IndustryService, useValue: industry }],
    });
  });

  describe('Auswahl', () => {
    it('rechnet erst, wenn ein Produkt gewählt ist', () => {
      const component = build();

      component.onQuery('rav');

      // Tippen allein löst keine Rechnung aus - sonst rechnet das Werkzeug
      // bei jedem Tastendruck einen ganzen Stücklistenbaum durch.
      expect(industry.preview).not.toHaveBeenCalled();
    });

    it('rechnet nach der Auswahl mit der eingestellten Menge', () => {
      const component = build();
      const hit: ProductHit = {
        typeId: 638,
        typeName: 'Raven',
        groupName: 'Battleship',
        blueprintTypeId: 688,
      };

      component.onQuantity('50');
      component.choose(hit);

      // Ohne gewählten Bauort geht kein System mit - dann zählt der Bestand aus
      // ganz EVE, und der Spaltenkopf sagt das auch.
      expect(industry.preview).toHaveBeenCalledWith(638, 50, 1, null);
      expect(component.query()).toBe('Raven');
      // Die Vorschlagsliste verschwindet nach der Auswahl.
      expect(component.hits()).toEqual([]);
    });

    it('fängt eine unbrauchbare Menge ab', () => {
      const component = build();

      component.onQuantity('-5');
      expect(component.quantity()).toBe(1);

      component.onQuantity('abc');
      expect(component.quantity()).toBe(1);

      component.onQuantity('7.9');
      expect(component.quantity()).toBe(7);
    });

    it('verwirft die alte Rechnung, sobald wieder getippt wird', () => {
      const component = build();
      component.preview.set(preview());

      component.onQuery('ande');

      expect(component.preview()).toBeNull();
      expect(component.chosen()).toBeNull();
    });
  });

  describe('Anzeige', () => {
    it('zeigt zunächst nur die oberste Ebene', () => {
      const component = build();
      component.preview.set(
        preview({
          requirements: [
            requirement({ typeId: 34, depth: 1 }),
            requirement({ typeId: 35, depth: 2 }),
          ],
        }),
      );

      // Ein Titan brächte sonst über hundert Zeilen mit.
      expect(component.topLevel().map((r) => r.typeId)).toEqual([34]);
    });

    it('benennt Job-Zeit in Stunden statt Sekunden', () => {
      const component = build();

      expect(component.hours(900_000)).toBe('250.0 h');
      expect(component.hours(1800)).toBe('30 min');
      expect(component.hours(0)).toBe('0 h');
    });

    it('fasst die Restzeit eines Jobs kurz', () => {
      const component = build();
      const jetzt = Date.parse('2026-08-10T12:00:00Z');
      const in_ = (ms: number) => new Date(jetzt + ms).toISOString();

      // Unter dem Namen eines Bauteils ist Platz für eine Größenordnung,
      // nicht für einen Zeitstempel.
      expect(component.remaining(in_(90 * 1000), jetzt)).toBe('1 min');
      expect(component.remaining(in_(45 * 60 * 1000), jetzt)).toBe('45 min');
      expect(component.remaining(in_(3 * 3600 * 1000), jetzt)).toBe('3 h');
      expect(component.remaining(in_((3 * 3600 + 12 * 60) * 1000), jetzt)).toBe('3 h 12 min');
      expect(component.remaining(in_(50 * 3600 * 1000), jetzt)).toBe('2 T 2 h');
      expect(component.remaining(in_(48 * 3600 * 1000), jetzt)).toBe('2 T');
    });

    it('sagt bei abgelaufenen und fehlenden Zeiten nichts Falsches', () => {
      const component = build();
      const jetzt = Date.parse('2026-08-10T12:00:00Z');

      // Ein Job, dessen Ende vorbei ist, läuft nicht mehr - eine negative
      // Restzeit wäre schlimmer als gar keine.
      expect(component.remaining('2026-08-10T11:00:00Z', jetzt)).toBe('fertig');
      expect(component.remaining(null, jetzt)).toBeNull();
      expect(component.remaining('kein Datum', jetzt)).toBeNull();
    });

    it('schreibt große Zahlen lesbar', () => {
      const component = build();

      expect(component.amount(5_200_000)).toBe('5.200.000');
      expect(component.volume(2_500_000)).toBe('2.500.000 m³');
      expect(component.volume(0)).toBe('—');
    });

    it('nennt PI-Güter ausdrücklich als nicht baubar', () => {
      const component = build();

      // Wer dort einen Bauen-Knopf anbietet, schickt den Nutzer in eine Sackgasse:
      // per Industriejob lassen sich diese Güter gar nicht herstellen.
      expect(component.kindLabel('PI')).toContain('nicht per Industriejob baubar');
      expect(component.kindLabel('MINERAL')).toBe('Mineral');
      expect(component.kindLabel('REACTION')).toBe('Reaktion');
    });

    it('rechnet die Deckung einer Zeile in Prozent', () => {
      const component = build();

      expect(component.coverage(requirement({ needed: 1000, have: 400 }))).toBe(40);
      // Mehr als voll gedeckt bleibt bei hundert stehen.
      expect(component.coverage(requirement({ needed: 100, have: 500 }))).toBe(100);
      expect(component.coverage(requirement({ needed: 0, have: 0 }))).toBe(100);
    });
  });

  describe('Blaupausen-Lage', () => {
    it('unterscheidet fehlende von unerforschter Blaupause', () => {
      const component = build();

      // Ohne Blaupause lässt sich der Job gar nicht starten - eine unerforschte
      // macht ihn nur teurer. Beides mit ME 0 zu vermengen wäre irreführend.
      component.preview.set(
        preview({
          summary: { ...preview().summary, blueprintOwned: false, materialEfficiency: 0 },
        }),
      );
      expect(component.summary()?.blueprintOwned).toBe(false);

      component.preview.set(
        preview({
          summary: { ...preview().summary, blueprintOwned: true, materialEfficiency: 0 },
        }),
      );
      expect(component.summary()?.blueprintOwned).toBe(true);
      expect(component.summary()?.materialEfficiency).toBe(0);
    });
  });

  describe('Blaupausen-Prüfung', () => {
    function check(over: Partial<BlueprintCheck> = {}): BlueprintCheck {
      return {
        productTypeId: 638,
        productName: 'Raven',
        blueprintTypeId: 688,
        neededRuns: 50,
        availableRuns: 50,
        owned: true,
        sufficient: true,
        materialEfficiency: 10,
        timeEfficiency: 20,
        required: true,
        kind: 'Blaupause',
        note: null,
        ...over,
      };
    }

    it('nennt ein Original beim Namen statt minus eins anzuzeigen', () => {
      const component = build();

      // -1 steht intern für "unbegrenzt" - als Zahl wäre das sinnlos.
      expect(component.runsLabel(check({ availableRuns: -1 }))).toBe('Original');
    });

    it('stellt vorhandene gegen benötigte Läufe', () => {
      const component = build();

      expect(component.runsLabel(check({ availableRuns: 5, neededRuns: 50 })))
        .toBe('5 von 50');
    });

    it('sagt bei fehlender Blaupause nicht null Läufe', () => {
      const component = build();

      expect(component.runsLabel(check({ owned: false, availableRuns: 0 }))).toBe('keine');
    });
  });

  describe('Bauort', () => {
    function ort(over: Partial<BuildLocation> = {}): BuildLocation {
      return {
        structureId: 1000,
        name: 'MA Werft',
        systemName: 'Jita',
        systemId: 30000142,
        security: 0.946,
        typeName: 'Raitaru',
        source: 'CORP',
        servicesKnown: true,
        manufacturing: true,
        reprocessing: false,
        reactions: false,
        hints: [],
        ...over,
      };
    }

    it('sagt bei unbekannten Diensten nichts zu', () => {
      const component = build();

      // Für fremde Strukturen verrät ESI die Dienste nicht. Dann wird auch nichts
      // behauptet - eine geratene Zusage wäre schlimmer als keine Auskunft.
      expect(component.locationServices(ort({ servicesKnown: false }))).toBe(
        'Dienste unbekannt',
      );
    });

    it('zählt die laufenden Dienste auf', () => {
      const component = build();

      expect(
        component.locationServices(ort({ manufacturing: true, reactions: true })),
      ).toBe('Fertigung · Reaktionen');
    });

    it('unterscheidet eine Struktur ohne Industriedienste von einer unbekannten', () => {
      const component = build();

      expect(
        component.locationServices(
          ort({ servicesKnown: true, manufacturing: false, reprocessing: false }),
        ),
      ).toBe('Keine Industriedienste online');
    });

    it('benennt die Herkunft eines Ortes', () => {
      const component = build();

      expect(component.sourceLabel('CORP')).toBe('Eigene Corp');
      expect(component.sourceLabel('NPC')).toBe('NPC-Station');
      expect(component.sourceLabel('PUBLIC')).toBe('Andockrecht');
    });

    it('sucht erst ab zwei Zeichen', () => {
      industry.locations = vi.fn().mockReturnValue(of([ort()]));
      const component = build();

      component.onLocationQuery('J');

      expect(component.locationQuery()).toBe('J');
      expect(industry.locations).not.toHaveBeenCalled();
    });
  });

  describe('Aufträge', () => {
    it('legt einen Auftrag an und räumt die Eingabe ab', () => {
      const detail = { order: order(), summary: preview().summary, requirements: [], jobs: [] };
      industry.create.mockReturnValue(of(detail));
      const component = build();
      component.choose({
        typeId: 638,
        typeName: 'Raven',
        groupName: 'Battleship',
        blueprintTypeId: 688,
      });
      component.onQuantity('50');

      component.createOrder();

      // Ohne gewählten Bauort geht null mit - der Server nimmt dann den
      // teureren Transport an, statt zu schmeicheln.
      expect(industry.create).toHaveBeenCalledWith(638, 50, null, null);
      expect(component.openOrder()).toBe(detail);
      expect(component.chosen()).toBeNull();
      expect(component.query()).toBe('');
    });

    it('legt ohne gewähltes Produkt nichts an', () => {
      const component = build();

      component.createOrder();

      expect(industry.create).not.toHaveBeenCalled();
    });

    it('meldet, wenn sich ein Material nicht bauen lässt', () => {
      industry.decide.mockReturnValue(throwError(() => new Error('nein')));
      const component = build();
      component.openOrder.set({
        order: order(),
        summary: preview().summary,
        requirements: [],
        jobs: [],
      });

      component.decide(requirement({ typeName: 'Nanites', sourceKind: 'PI' }), 'BUILD');

      expect(component.error()).toContain('Nanites');
    });

    it('übernimmt die Antwort einer Umstellung', () => {
      const nachher = {
        order: order(),
        summary: preview().summary,
        requirements: [requirement({ decision: 'BUILD' })],
        jobs: [],
      };
      industry.decide.mockReturnValue(of(nachher));
      const component = build();
      component.openOrder.set({
        order: order(),
        summary: preview().summary,
        requirements: [],
        jobs: [],
      });

      component.decide(requirement({ buildable: true, sourceKind: 'BUILDABLE' }), 'BUILD');

      expect(industry.decide).toHaveBeenCalledWith(1, 34, 'BUILD');
      expect(component.openOrder()).toBe(nachher);
    });

    it('löscht einen Auftrag nur nach Rückfrage', () => {
      const component = build();
      vi.stubGlobal('confirm', vi.fn().mockReturnValue(false));

      component.deleteOrder(order());
      expect(industry.remove).not.toHaveBeenCalled();

      // Die Nullmessung lässt sich nicht wiederherstellen - deshalb die Rückfrage.
      vi.stubGlobal('confirm', vi.fn().mockReturnValue(true));
      component.deleteOrder(order());
      expect(industry.remove).toHaveBeenCalledWith(1);

      vi.unstubAllGlobals();
    });

    it('lädt Einkaufsliste und Blaupausen nach einer Umstellung neu', () => {
      const nachher = {
        order: order(),
        summary: preview().summary,
        requirements: [requirement({ decision: 'BUILD' })],
        jobs: [],
      };
      industry.decide.mockReturnValue(of(nachher));
      const component = build();
      component.openOrder.set({
        order: order(),
        summary: preview().summary,
        requirements: [],
        jobs: [],
      });
      industry.procurement.mockClear();
      industry.blueprints.mockClear();

      component.decide(requirement({ buildable: true, sourceKind: 'BUILDABLE' }), 'BUILD');

      // Beide hängen am Bedarf - ohne Nachladen zeigen sie weiter den alten
      // Stand, ohne dass man es ihnen ansieht.
      expect(industry.procurement).toHaveBeenCalledWith(1);
      expect(industry.blueprints).toHaveBeenCalledWith(1);
    });

    it('wendet eine Voreinstellung an und lädt danach alles neu', () => {
      const nachher = {
        order: order(),
        summary: preview().summary,
        requirements: [requirement({ decision: 'BUILD' })],
        jobs: [],
      };
      industry.applyStrategy.mockReturnValue(of(nachher));
      const component = build();
      component.openOrder.set({
        order: order(),
        summary: preview().summary,
        requirements: [],
        jobs: [],
      });
      industry.procurement.mockClear();

      component.applyStrategy('COST_EFFICIENT');

      expect(industry.applyStrategy).toHaveBeenCalledWith(1, 'COST_EFFICIENT');
      expect(component.openOrder()).toBe(nachher);
      // Die Einkaufsliste ändert sich mit jeder Entscheidung mit.
      expect(industry.procurement).toHaveBeenCalledWith(1);
      expect(component.strategyRunning()).toBe(false);
    });

    it('rechnet einen Auftrag neu und lädt die Einkaufsliste mit', () => {
      // Der Bedarf ist eingefroren, damit der Balken nicht bei jedem Neuladen
      // springt. Ohne diesen Weg zurück erreichen eine erforschte Blaupause und
      // geänderte Marktpreise einen bestehenden Auftrag nie.
      const nachher = {
        order: order(),
        summary: preview().summary,
        requirements: [requirement({ needed: 9999 })],
        jobs: [],
      };
      industry.recalculate.mockReturnValue(of(nachher));
      const component = build();
      component.openOrder.set({
        order: order(),
        summary: preview().summary,
        requirements: [],
        jobs: [],
      });
      industry.procurement.mockClear();

      component.recalculate();

      expect(industry.recalculate).toHaveBeenCalledWith(1);
      expect(component.openOrder()).toBe(nachher);
      expect(industry.procurement).toHaveBeenCalledWith(1);
      expect(component.recalcRunning()).toBe(false);
    });

    it('sagt im Spaltenkopf, worauf sich "Vorhanden" bezieht', () => {
      const component = build();

      // Ohne Bauort meint die Zahl ganz EVE. Das muss dastehen, sonst wechselt
      // sie stillschweigend ihre Bedeutung, sobald ein Ort gewählt wird.
      expect(component.bestandsOrt()).toBe('in ganz EVE');

      component.openOrder.set({
        order: { ...order(), buildLocationName: 'K-6K16' },
        summary: preview().summary,
        requirements: [],
        jobs: [],
      });
      expect(component.bestandsOrt()).toBe('in K-6K16');
    });

    it('setzt den Bauort an einem offenen Auftrag statt ihn nur vorzumerken', () => {
      // Beide bestehenden Aufträge haben kein Bausystem. Ohne diesen Weg bliebe
      // die Ortsangabe für sie unerreichbar - außer man löscht sie.
      const nachher = {
        order: { ...order(), buildLocationName: 'Jita' },
        summary: preview().summary,
        requirements: [],
        jobs: [],
      };
      industry.setBuildLocation.mockReturnValue(of(nachher));
      const component = build();
      component.openOrder.set({
        order: order(),
        summary: preview().summary,
        requirements: [],
        jobs: [],
      });

      component.chooseLocation({
        structureId: 30000142,
        name: 'Jita',
        systemName: 'Jita',
        systemId: 30000142,
        security: 0.946,
        typeName: 'The Forge',
        source: 'SYSTEM',
        servicesKnown: false,
        manufacturing: false,
        reprocessing: false,
        reactions: false,
        hints: [],
      });

      expect(industry.setBuildLocation).toHaveBeenCalledWith(1, 30000142, null, 'Jita');
      expect(component.openOrder()).toBe(nachher);
      // Die Einkaufsliste hängt am Bestand, und der ändert sich mit dem Ort.
      expect(industry.procurement).toHaveBeenCalledWith(1);
    });

    it('bietet die drei Voreinstellungen in aufsteigendem Aufwand', () => {
      const component = build();

      expect(component.strategies.map((s) => s.key)).toEqual([
        'BUY_ALL',
        'COST_EFFICIENT',
        'BUILD_ALL',
      ]);
    });

    it('lädt die Liste beim Start und bleibt bei einem Fehler leer', () => {
      industry.orders.mockReturnValue(throwError(() => new Error('weg')));

      const component = build();

      expect(industry.orders).toHaveBeenCalled();
      expect(component.orders()).toEqual([]);
    });
  });
});
