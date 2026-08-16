import { TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AssetAuditComponent } from './asset-audit.component';
import { AssetService, PageDto } from '../../services/asset.service';
import { AuthService } from '../../services/auth.service';
import { ToastService } from '../../services/toast.service';

/** Ein Typ-Vorschlag, wie ihn der Typeahead erhält. */
const suggestion = {
  typeId: 587,
  typeName: 'Nestor',
  groupName: 'Battleship',
  iconUrl: '',
  totalQuantity: 1,
};

function page<T>(marker = 0): PageDto<T> {
  return {
    content: [],
    page: 0,
    size: 50,
    totalElements: marker,
    totalPages: 1,
    pageValue: 0,
    grandTotalValue: marker,
  };
}

describe('AssetAuditComponent', () => {
  let component: AssetAuditComponent;
  let assetService: Record<string, ReturnType<typeof vi.fn>>;
  let toastService: Record<string, ReturnType<typeof vi.fn>>;
  let authService: { hasAnyRole: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    assetService = {
      summary: vi.fn().mockReturnValue(of({ totalValue: 1 })),
      filters: vi.fn().mockReturnValue(of({ categories: [] })),
      search: vi.fn().mockReturnValue(of(page())),
      searchGrouped: vi.fn().mockReturnValue(of(page())),
      suggestTypes: vi.fn().mockReturnValue(of([suggestion])),
      holders: vi.fn().mockReturnValue(of({ holders: [] })),
      memberDetail: vi.fn().mockReturnValue(of({ mainId: 1000 })),
      exportCsv: vi.fn().mockReturnValue(of(new Blob(['csv']))),
      resolveLocations: vi.fn().mockReturnValue(of({})),
    };
    toastService = { success: vi.fn(), error: vi.fn(), info: vi.fn() };
    authService = { hasAnyRole: vi.fn().mockReturnValue(true) };

    TestBed.configureTestingModule({
      providers: [
        { provide: AssetService, useValue: assetService },
        { provide: ToastService, useValue: toastService },
        { provide: AuthService, useValue: authService },
      ],
    });
    component = TestBed.runInInjectionContext(() => new AssetAuditComponent());
  });

  afterEach(() => vi.unstubAllGlobals());

  describe('Übersicht', () => {
    it('lädt Übersicht und Filter beim Start', () => {
      component.ngOnInit();

      expect(assetService['summary']).toHaveBeenCalled();
      expect(assetService['filters']).toHaveBeenCalled();
      expect(component.loadingSummary()).toBe(false);
    });

    it('meldet einen Fehlschlag der Übersicht', () => {
      assetService['summary'].mockReturnValue(throwError(() => new Error('kaputt')));

      component.loadSummary();

      expect(toastService['error']).toHaveBeenCalled();
      expect(component.loadingSummary()).toBe(false);
    });

    it('meldet einen Fehlschlag der Filterlisten', () => {
      assetService['filters'].mockReturnValue(throwError(() => new Error('kaputt')));

      component.loadFilters();

      expect(toastService['error']).toHaveBeenCalled();
    });
  });

  describe('Suche', () => {
    it('nutzt die gruppierte Suche als Vorgabe und schaltet um', () => {
      component.runSearch();
      expect(assetService['searchGrouped']).toHaveBeenCalled();

      component.toggleGrouped();
      expect(component.grouped()).toBe(false);
      expect(assetService['search']).toHaveBeenCalled();
    });

    it('springt bei einer neuen Suche auf die erste Seite', () => {
      component.f.page = 5;

      component.runSearch();

      expect(component.f.page).toBe(0);
    });

    it('verwirft eine überholte Antwort', () => {
      const slowFirst = new Subject<PageDto<unknown>>();
      const fastSecond = new Subject<PageDto<unknown>>();
      assetService['searchGrouped'].mockReturnValueOnce(slowFirst).mockReturnValueOnce(fastSecond);

      component.runSearch();
      component.runSearch();

      fastSecond.next(page(2));
      slowFirst.next(page(1));

      expect(component.groupedResult()?.totalElements).toBe(2);
    });

    it('meldet einen Fehlschlag der Suche', () => {
      assetService['searchGrouped'].mockReturnValue(throwError(() => new Error('kaputt')));

      component.runSearch();

      expect(toastService['error']).toHaveBeenCalled();
      expect(component.loadingSearch()).toBe(false);
    });

    it('liefert Kennzahlen aus dem jeweils aktiven Ergebnis', () => {
      component.groupedResult.set({ ...page(42), totalPages: 3, grandTotalValue: 99 });

      expect(component.currentTotal()).toBe(42);
      expect(component.currentPages()).toBe(3);
      expect(component.grandTotal()).toBe(99);
    });

    it('meldet ohne Ergebnis neutrale Kennzahlen', () => {
      expect(component.currentTotal()).toBe(0);
      expect(component.currentPages()).toBe(0);
      expect(component.grandTotal()).toBe(0);
    });
  });

  describe('Blättern und Sortieren', () => {
    it('blättert vorwärts und rückwärts innerhalb der Grenzen', () => {
      component.groupedResult.set({ ...page(200), totalPages: 3 });

      component.nextPage();
      expect(component.f.page).toBe(1);

      component.prevPage();
      expect(component.f.page).toBe(0);

      component.prevPage();
      expect(component.f.page).toBe(0);
    });

    it('blättert nicht über die letzte Seite hinaus', () => {
      component.groupedResult.set({ ...page(10), totalPages: 1 });

      component.nextPage();

      expect(component.f.page).toBe(0);
    });

    it('dreht die Sortierrichtung bei derselben Spalte', () => {
      component.f.sort = 'value';
      component.f.direction = 'desc';

      component.sortBy('value');
      expect(component.f.direction).toBe('asc');

      component.sortBy('quantity');
      expect(component.f.sort).toBe('quantity');
      expect(component.f.direction).toBe('desc');
    });

    it('zeigt das passende Sortier-Symbol', () => {
      component.f.sort = 'value';
      component.f.direction = 'desc';

      expect(component.sortIcon('value')).toContain('sort-down');
      component.f.direction = 'asc';
      expect(component.sortIcon('value')).toContain('sort-up');
      expect(component.sortIcon('andere')).toBe('fa-solid fa-sort');
    });
  });

  describe('Filter und Vorschläge', () => {
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

    it('blendet die Vorschläge erst ab zwei Zeichen ein', () => {
      component.onTypeAhead('r');

      expect(component.showSuggestions()).toBe(false);
      expect(component.f.q).toBe('r');
    });

    it('setzt alle Filter auf die Ausgangswerte zurück', () => {
      component.f.q = 'Nestor';
      component.f.mainId = 1000;
      component.f.ownerType = 'CORPORATION';

      component.resetFilters();

      expect(component.f.q).toBe('');
      expect(component.f.mainId).toBeNull();
      expect(component.f.ownerType).toBeNull();
    });
  });

  describe('Wer hat das?', () => {
    it('öffnet die Besitzerliste zu einem Typ', () => {
      component.openHolders(587, 'Rifter');

      expect(assetService['holders']).toHaveBeenCalledWith(587);
      expect(component.showHoldersModal()).toBe(true);
      expect(component.loadingHolders()).toBe(false);
    });

    it('nennt beim Fehlschlag den betroffenen Typ', () => {
      assetService['holders'].mockReturnValue(throwError(() => new Error('kaputt')));

      component.openHolders(587, 'Rifter');

      expect(toastService['error']).toHaveBeenCalledWith(expect.stringContaining('Rifter'));
    });

    it('klappt einen Besitzer auf und wieder zu', () => {
      component.toggleHolder(1000);
      expect(component.isHolderExpanded(1000)).toBe(true);

      component.toggleHolder(1000);
      expect(component.isHolderExpanded(1000)).toBe(false);
    });

    it('räumt die aufgeklappten Besitzer beim erneuten Öffnen ab', () => {
      component.toggleHolder(1000);

      component.openHolders(587);

      expect(component.isHolderExpanded(1000)).toBe(false);
    });
  });

  describe('Account-Detail', () => {
    it('öffnet die Detailansicht eines Accounts', () => {
      component.openMember(1000);

      expect(assetService['memberDetail']).toHaveBeenCalledWith(1000);
      expect(component.showMemberModal()).toBe(true);
      expect(component.loadingMember()).toBe(false);
    });

    it('meldet einen Fehlschlag der Detailansicht', () => {
      assetService['memberDetail'].mockReturnValue(throwError(() => new Error('kaputt')));

      component.openMember(1000);

      expect(toastService['error']).toHaveBeenCalled();
      expect(component.loadingMember()).toBe(false);
    });

    it('lädt beim Wechsel im Auswahlfeld den gewählten Account', () => {
      component.selectedMainId = 2000 as never;

      component.onMemberSelectChange();

      expect(assetService['memberDetail']).toHaveBeenCalledWith(2000);
    });

    it('lädt ohne Auswahl nichts', () => {
      component.selectedMainId = null as never;

      component.onMemberSelectChange();

      expect(assetService['memberDetail']).not.toHaveBeenCalled();
    });

    it('schließt alle Dialoge auf einmal', () => {
      component.showHoldersModal.set(true);
      component.showMemberModal.set(true);

      component.closeModals();

      expect(component.showHoldersModal()).toBe(false);
      expect(component.showMemberModal()).toBe(false);
    });
  });

  describe('Export und Wartung', () => {
    it('lädt den Export als Datei herunter', () => {
      const link = { href: '', download: '', click: vi.fn() };
      vi.stubGlobal('URL', { createObjectURL: vi.fn(() => 'blob:x'), revokeObjectURL: vi.fn() });
      vi.stubGlobal('window', {
        URL: { createObjectURL: vi.fn(() => 'blob:x'), revokeObjectURL: vi.fn() },
      });
      vi.stubGlobal('document', { createElement: vi.fn(() => link) });

      component.exportCsv();

      expect(link.click).toHaveBeenCalled();
      expect(link.download).toContain('assets-');
      expect(toastService['success']).toHaveBeenCalled();
    });

    it('meldet einen Fehlschlag des Exports', () => {
      assetService['exportCsv'].mockReturnValue(throwError(() => new Error('kaputt')));

      component.exportCsv();

      expect(toastService['error']).toHaveBeenCalled();
    });

    it('stößt die Standort-Auflösung an', () => {
      component.resolveLocations();

      expect(assetService['resolveLocations']).toHaveBeenCalled();
      expect(toastService['info']).toHaveBeenCalled();
    });

    it('meldet einen Fehlschlag der Standort-Auflösung', () => {
      assetService['resolveLocations'].mockReturnValue(throwError(() => new Error('kaputt')));

      component.resolveLocations();

      expect(toastService['error']).toHaveBeenCalled();
    });
  });

  describe('Reiter und Rechte', () => {
    it('lädt die Übersicht beim ersten Wechsel dorthin', () => {
      component.setTab('OVERVIEW');

      expect(assetService['summary']).toHaveBeenCalled();
    });

    it('lädt die Suche beim ersten Wechsel dorthin', () => {
      component.setTab('SEARCH');

      expect(assetService['searchGrouped']).toHaveBeenCalled();
    });

    it('meldet die Rechte für die Oberfläche', () => {
      expect(component.isLeadership).toBe(true);
      expect(component.isAdmin).toBe(true);
    });
  });

  describe('Typeahead', () => {
    it('blendet Vorschläge erst nach der Tipppause ein', () => {
      vi.useFakeTimers();

      component.onTypeAhead('nes');
      expect(assetService['suggestTypes']).not.toHaveBeenCalled();

      vi.advanceTimersByTime(250);

      expect(assetService['suggestTypes']).toHaveBeenCalledWith('nes');
      expect(component.suggestions()).toHaveLength(1);
      expect(component.showSuggestions()).toBe(true);

      vi.useRealTimers();
    });

    it('bleibt nach einem Fehlschlag benutzbar', () => {
      // Lag die Fehlerbehandlung im äusseren subscribe, war der Typeahead nach
      // dem ersten Fehlschlag für den Rest der Sitzung stumm.
      vi.useFakeTimers();
      assetService['suggestTypes'].mockReturnValueOnce(throwError(() => new Error('kaputt')));

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
      assetService['searchGrouped'].mockReturnValueOnce(first).mockReturnValueOnce(second);

      component.runSearch();
      expect(first.observed).toBe(true);

      component.runSearch();

      expect(first.observed).toBe(false);
      expect(second.observed).toBe(true);
    });

    it('bleibt nach einem Fehlschlag benutzbar', () => {
      assetService['searchGrouped'].mockReturnValueOnce(throwError(() => new Error('kaputt')));

      component.runSearch();
      expect(toastService['error']).toHaveBeenCalled();

      component.runSearch();

      expect(component.groupedResult()).not.toBeNull();
    });
  });
});
