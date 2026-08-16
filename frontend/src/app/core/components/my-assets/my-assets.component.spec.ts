import { TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { MyAssetsComponent } from './my-assets.component';
import { MyAssetService } from '../../services/my-asset.service';
import { AuthService } from '../../services/auth.service';
import { ToastService } from '../../services/toast.service';
import { PageDto } from '../../services/asset.service';

/** Ein Typ-Vorschlag, wie ihn der Typeahead erhält. */
const suggestion = {
  typeId: 587,
  typeName: 'Nestor',
  groupName: 'Battleship',
  iconUrl: '',
  totalQuantity: 1,
};

/** Eine leere Ergebnisseite, wie sie der Server liefert. */
function page<T>(marker = 0): PageDto<T> {
  return {
    content: [],
    page: 0,
    size: 50,
    totalElements: marker,
    totalPages: 1,
    pageValue: 0,
    grandTotalValue: 0,
  };
}

describe('MyAssetsComponent', () => {
  let component: MyAssetsComponent;
  let myAssetService: {
    summary: ReturnType<typeof vi.fn>;
    filters: ReturnType<typeof vi.fn>;
    search: ReturnType<typeof vi.fn>;
    searchGrouped: ReturnType<typeof vi.fn>;
    suggestTypes: ReturnType<typeof vi.fn>;
    exportCsv: ReturnType<typeof vi.fn>;
    placements: ReturnType<typeof vi.fn>;
  };
  let toastService: { error: ReturnType<typeof vi.fn>; success: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    myAssetService = {
      summary: vi.fn().mockReturnValue(of(null)),
      filters: vi.fn().mockReturnValue(of(null)),
      search: vi.fn().mockReturnValue(of(page())),
      searchGrouped: vi.fn().mockReturnValue(of(page())),
      suggestTypes: vi.fn().mockReturnValue(of([suggestion])),
      placements: vi.fn().mockReturnValue(of([])),
      exportCsv: vi.fn().mockReturnValue(of(new Blob(['csv']))),
    };
    toastService = { error: vi.fn(), success: vi.fn() };

    TestBed.configureTestingModule({
      providers: [
        { provide: MyAssetService, useValue: myAssetService },
        { provide: AuthService, useValue: {} },
        { provide: ToastService, useValue: toastService },
      ],
    });

    component = TestBed.runInInjectionContext(() => new MyAssetsComponent());
  });

  describe('Suche', () => {
    it('nutzt die gruppierte Suche als Vorgabe', () => {
      component.runSearch();

      expect(myAssetService.searchGrouped).toHaveBeenCalled();
      expect(myAssetService.search).not.toHaveBeenCalled();
    });

    it('wechselt beim Umschalten auf die flache Suche', () => {
      component.toggleGrouped();

      expect(component.grouped()).toBe(false);
      expect(myAssetService.search).toHaveBeenCalled();
    });

    it('springt bei einer neuen Suche auf die erste Seite', () => {
      component.f.page = 5;

      component.runSearch();

      expect(component.f.page).toBe(0);
    });

    it('blättert vorwärts, solange Seiten übrig sind', () => {
      component.f.page = 0;
      component.groupedResult.set({ ...page(200), totalPages: 4 });

      component.nextPage();

      expect(component.f.page).toBe(1);
    });

    it('behält die Seite beim Blättern statt zurückzuspringen', () => {
      component.groupedResult.set({ ...page(200), totalPages: 4 });
      component.f.page = 2;

      component.prevPage();

      expect(component.f.page).toBe(1);
    });

    it('blättert nicht über das Ende hinaus', () => {
      component.groupedResult.set({ ...page(), totalPages: 1 });
      component.f.page = 0;

      component.nextPage();

      expect(component.f.page).toBe(0);
    });

    it('blättert nicht vor die erste Seite', () => {
      component.f.page = 0;

      component.prevPage();

      expect(component.f.page).toBe(0);
    });

    it('verwirft eine überholte Antwort', () => {
      // Ohne diese Absicherung überschreibt eine langsame ältere Antwort die neuere.
      const slowFirst = new Subject<PageDto<unknown>>();
      const fastSecond = new Subject<PageDto<unknown>>();
      myAssetService.searchGrouped
        .mockReturnValueOnce(slowFirst)
        .mockReturnValueOnce(fastSecond);

      component.runSearch();
      component.runSearch();

      fastSecond.next(page(2));
      slowFirst.next(page(1));

      expect(component.groupedResult()?.totalElements).toBe(2);
    });

    it('meldet einen Fehlschlag der Suche', () => {
      myAssetService.searchGrouped.mockReturnValue(throwError(() => new Error('kaputt')));

      component.runSearch();

      expect(toastService.error).toHaveBeenCalled();
      expect(component.loadingSearch()).toBe(false);
    });
  });

  describe('Sortierung', () => {
    it('dreht die Richtung beim erneuten Klick auf dieselbe Spalte', () => {
      component.f.sort = 'value';
      component.f.direction = 'desc';

      component.sortBy('value');

      expect(component.f.direction).toBe('asc');
    });

    it('startet eine neue Spalte absteigend', () => {
      component.f.sort = 'value';

      component.sortBy('quantity');

      expect(component.f.sort).toBe('quantity');
      expect(component.f.direction).toBe('desc');
    });

    it('zeigt das passende Symbol je Spalte und Richtung', () => {
      component.f.sort = 'value';
      component.f.direction = 'desc';

      expect(component.sortIcon('value')).toContain('sort-down');
      component.f.direction = 'asc';
      expect(component.sortIcon('value')).toContain('sort-up');
      expect(component.sortIcon('quantity')).toBe('fa-solid fa-sort');
    });
  });

  describe('Filter', () => {
    it('setzt alle Filter auf die Ausgangswerte zurück', () => {
      component.f.q = 'Nestor';
      component.f.typeId = 587;
      component.f.locationId = 60003760;

      component.resetFilters();

      expect(component.f.q).toBe('');
      expect(component.f.typeId).toBeNull();
      expect(component.f.locationId).toBeNull();
    });

    it('leert beim Wechsel der Kategorie die Gruppe', () => {
      component.f.groupId = 25;

      component.onCategoryChange();

      expect(component.f.groupId).toBeNull();
    });

    it('übernimmt einen Vorschlag als Typ-Filter', () => {
      component.pickSuggestion({
        typeId: 587,
        typeName: 'Rifter',
        groupName: 'Frigate',
        iconUrl: '',
        totalQuantity: 1,
      });

      expect(component.f.typeId).toBe(587);
      expect(component.f.q).toBe('Rifter');
      expect(component.showSuggestions()).toBe(false);
    });

    it('räumt den Typ-Filter wieder ab', () => {
      component.f.typeId = 587;
      component.f.q = 'Rifter';

      component.clearTypeFilter();

      expect(component.f.typeId).toBeNull();
      expect(component.f.q).toBe('');
    });
  });

  describe('Sprung aus der Übersicht in die Suche', () => {
    it('setzt genau einen Standort-Filter und wechselt den Reiter', () => {
      component.f.q = 'alter Filter';

      component.drillIntoLocation(60003760);

      expect(component.f.locationId).toBe(60003760);
      expect(component.f.q).toBe('');
      expect(component.activeTab()).toBe('SEARCH');
      expect(component.showAdvancedFilters()).toBe(true);
    });

    it('löst dabei nur eine einzige Suche aus', () => {
      // Ein Zurücksetzen über resetFilters würde eine zweite, ungefilterte
      // Suche starten, die mit der gefilterten um das Ergebnis konkurriert.
      component.drillIntoLocation(60003760);

      expect(myAssetService.searchGrouped).toHaveBeenCalledTimes(1);
    });
  });

  describe('Reiter', () => {
    it('lädt die Übersicht beim ersten Wechsel dorthin', () => {
      component.setTab('OVERVIEW');

      expect(myAssetService.summary).toHaveBeenCalled();
    });

    it('lädt die Suche beim ersten Wechsel dorthin', () => {
      component.setTab('SEARCH');

      expect(myAssetService.searchGrouped).toHaveBeenCalled();
    });
  });

  describe('Wo genau liegt der Bestand', () => {
    /** Ein Standort, wie ihn der Server liefert. */
    const place = {
      characterId: 1000,
      characterName: 'Pilot Eins',
      locationId: 60003760,
      locationName: 'Jita IV - Moon 4',
      systemName: 'Jita',
      regionName: 'The Forge',
      locationFlag: 'Hangar',
      containerName: 'Munikiste',
      containerTypeName: 'Giant Secure Container',
      quantity: 42,
      totalValue: 1234.5,
    };

    it('holt die Standorte erst beim Aufklappen', () => {
      // Fuer jede Zeile der Seite waere die Aufschluesselung den Aufwand nicht wert.
      expect(myAssetService.placements).not.toHaveBeenCalled();

      component.togglePlacements(587);

      expect(component.expandedTypeId()).toBe(587);
      expect(myAssetService.placements).toHaveBeenCalled();
    });

    it('schickt die aktiven Filter mit, damit die Aufschlüsselung dazu passt', () => {
      component.f.regionName = 'The Forge';

      component.togglePlacements(587);

      expect(myAssetService.placements).toHaveBeenCalledWith(
        expect.objectContaining({ typeId: 587, regionName: 'The Forge' }),
      );
    });

    it('klappt beim zweiten Klick wieder zu, ohne erneut zu laden', () => {
      component.togglePlacements(587);
      myAssetService.placements.mockClear();

      component.togglePlacements(587);

      expect(component.expandedTypeId()).toBeNull();
      expect(myAssetService.placements).not.toHaveBeenCalled();
    });

    it('übernimmt die geladenen Standorte', () => {
      myAssetService.placements.mockReturnValue(of([place]));

      component.togglePlacements(587);

      expect(component.placements()).toHaveLength(1);
      expect(component.loadingPlacements()).toBe(false);
    });

    it('meldet einen Fehlschlag', () => {
      myAssetService.placements.mockReturnValue(throwError(() => new Error('kaputt')));

      component.togglePlacements(587);

      expect(toastService.error).toHaveBeenCalled();
      expect(component.loadingPlacements()).toBe(false);
    });

    it('setzt Behälter und Fach zu einer lesbaren Angabe zusammen', () => {
      expect(component.placementPath(place)).toBe('Munikiste (Giant Secure Container) in Hangar');
    });

    it('nennt nur das Fach, wenn kein Behälter im Spiel ist', () => {
      expect(component.placementPath({ ...place, containerName: null, containerTypeName: null }))
        .toBe('Hangar');
    });

    it('nennt den Behältertyp, wenn er keinen eigenen Namen trägt', () => {
      expect(component.placementPath({ ...place, containerName: null }))
        .toBe('Giant Secure Container in Hangar');
    });

    it('übersetzt die gängigen ESI-Fachbezeichnungen', () => {
      // Die Kürzel aus der API sind englisch und für Mitglieder wenig hilfreich.
      expect(component.flagLabel('ShipHangar')).toBe('Schiffshangar');
      expect(component.flagLabel('Deliveries')).toBe('Lieferungen');
      // Ein unbekanntes Kürzel bleibt stehen - besser als ein leeres Feld.
      expect(component.flagLabel('CorpSAG3')).toBe('CorpSAG3');
    });
  });

  describe('Typeahead', () => {
    it('blendet Vorschläge erst nach der Tipppause ein', () => {
      vi.useFakeTimers();

      component.onTypeAhead('nes');
      expect(myAssetService['suggestTypes']).not.toHaveBeenCalled();

      vi.advanceTimersByTime(250);

      expect(myAssetService['suggestTypes']).toHaveBeenCalledWith('nes');
      expect(component.suggestions()).toHaveLength(1);
      expect(component.showSuggestions()).toBe(true);

      vi.useRealTimers();
    });

    it('bleibt nach einem Fehlschlag benutzbar', () => {
      // Lag die Fehlerbehandlung im äusseren subscribe, war der Typeahead nach
      // dem ersten Fehlschlag für den Rest der Sitzung stumm.
      vi.useFakeTimers();
      myAssetService['suggestTypes'].mockReturnValueOnce(throwError(() => new Error('kaputt')));

      component.onTypeAhead('nes');
      vi.advanceTimersByTime(250);
      expect(component.suggestions()).toEqual([]);

      component.onTypeAhead('nest');
      vi.advanceTimersByTime(250);

      expect(component.suggestions()).toHaveLength(1);

      vi.useRealTimers();
    });
  });

  describe('Abbruch überholter Suchen', () => {
    it('bricht eine noch laufende Suche ab, statt sie zu Ende laufen zu lassen', () => {
      const first = new Subject<PageDto<unknown>>();
      const second = new Subject<PageDto<unknown>>();
      myAssetService['searchGrouped'].mockReturnValueOnce(first).mockReturnValueOnce(second);

      component.runSearch();
      expect(first.observed).toBe(true);

      component.runSearch();

      expect(first.observed).toBe(false);
      expect(second.observed).toBe(true);
    });

    it('bleibt nach einem Fehlschlag benutzbar', () => {
      myAssetService['searchGrouped'].mockReturnValueOnce(throwError(() => new Error('kaputt')));

      component.runSearch();
      expect(toastService['error']).toHaveBeenCalled();

      component.runSearch();

      expect(component.groupedResult()).not.toBeNull();
    });
  });
});
