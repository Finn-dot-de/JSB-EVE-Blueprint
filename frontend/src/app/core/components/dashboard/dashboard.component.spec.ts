import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DashboardComponent, DashboardDto } from './dashboard.component';
import { environment } from '../../../../environments/environment';
import { ToastService } from '../../services/toast.service';
import {
  FALLBACK_ICON,
  LOYALTY_ICONS,
  LOYALTY_LABELS,
  MILITIA_ICONS,
  SHIP_CLASS_ICONS,
} from './dashboard-icons';

const dashboard: DashboardDto = {
  characterName: 'Pilot Eins',
  portraitUrl: '',
  corporationId: 98000001,
  corporationName: 'Corp Eins',
  allianceId: 99005338,
  allianceName: 'Die Allianz',
  totalWalletBalance: 1_500_000_000,
  totalSkillPoints: 85_000_000,
  totalCharacters: 2,
  linkedCharacters: [{ id: 1, name: 'Main', portraitUrl: '' }],
  assets: {
    subcapital: { Frigate: 5, Cruiser: 2 },
    capital: { Dreadnought: 1 },
    industrial: { Hauler: 3 },
    notable: { 'Skill Injector': 4 },
    structures: { Citadel: 1 },
  },
  affiliations: {
    militias: { Amarr: 2, Gallente: 0 },
    evermarks: 5000,
    loyaltyPoints: { Total: 8000, CONCORD: 3000 },
  },
};

describe('DashboardComponent', () => {
  let component: DashboardComponent;
  let httpMock: HttpTestingController;
  let toastService: { error: ReturnType<typeof vi.fn> };

  const url = `${environment.apiUrl}/dashboard`;

  beforeEach(() => {
    toastService = { error: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ToastService, useValue: toastService },
      ],
    });
    httpMock = TestBed.inject(HttpTestingController);
    component = TestBed.runInInjectionContext(() => new DashboardComponent());
  });

  afterEach(() => httpMock.verify());

  it('lädt die Startseite beim Start', () => {
    component.ngOnInit();

    httpMock.expectOne(url).flush(dashboard);

    expect(component.dashboardData()?.characterName).toBe('Pilot Eins');
  });

  it('meldet einen Fehlschlag, statt eine leere Seite zu zeigen', () => {
    component.ngOnInit();

    httpMock.expectOne(url).flush(null, { status: 500, statusText: 'Server Error' });

    expect(component.dashboardData()).toBeNull();
    expect(toastService.error).toHaveBeenCalled();
  });

  describe('Aufbereitung für die Anzeige', () => {
    beforeEach(() => {
      component.ngOnInit();
      httpMock.expectOne(url).flush(dashboard);
    });

    it('wandelt die Bestands-Kästen in Listen um', () => {
      expect(component.subcapitalList()).toEqual([
        { name: 'Frigate', quantity: 5 },
        { name: 'Cruiser', quantity: 2 },
      ]);
      expect(component.capitalList()).toHaveLength(1);
      expect(component.industrialList()).toHaveLength(1);
      expect(component.notableList()).toHaveLength(1);
      expect(component.structuresList()).toHaveLength(1);
    });

    it('wandelt Milizen und Loyalitätspunkte in Listen um', () => {
      expect(component.militiaList()).toHaveLength(2);
      expect(component.lpAffiliationList()).toEqual([
        { name: 'Total', quantity: 8000 },
        { name: 'CONCORD', quantity: 3000 },
      ]);
    });

    it('kürzt große Zahlen für die Kacheln', () => {
      // Das Feld ist für das Template gedacht und deshalb nicht öffentlich.
      const shorten = (component as unknown as {
        formatShortNumber: (value: number) => string;
      }).formatShortNumber;

      expect(shorten(1_500_000_000)).toBe('1.50 B');
    });
  });

  describe('Ohne geladene Daten', () => {
    it('liefert überall leere Listen statt zu scheitern', () => {
      expect(component.subcapitalList()).toEqual([]);
      expect(component.capitalList()).toEqual([]);
      expect(component.industrialList()).toEqual([]);
      expect(component.notableList()).toEqual([]);
      expect(component.structuresList()).toEqual([]);
      expect(component.militiaList()).toEqual([]);
      expect(component.lpAffiliationList()).toEqual([]);

      // Ohne die Anfrage bliebe eine offene Erwartung im HttpTestingController.
      component.ngOnInit();
      httpMock.expectOne(url).flush(null, { status: 500, statusText: 'Server Error' });
    });
  });

  describe('Bilder und Bezeichnungen', () => {
    beforeEach(() => {
      component.ngOnInit();
      httpMock.expectOne(url).flush(dashboard);
    });

    it('findet die Logos der Milizen', () => {
      expect(component.getMilitiaIconUrl('Amarr')).toBe(MILITIA_ICONS['Amarr']);
      expect(component.getMilitiaIconUrl('Gibtsnicht')).toBe(FALLBACK_ICON);
    });

    it('findet die Logos der Loyalitäts-Corporations', () => {
      expect(component.getLpIconUrl('CONCORD')).toBe(LOYALTY_ICONS['CONCORD']);
      expect(component.getLpIconUrl('Gibtsnicht')).toBe(FALLBACK_ICON);
    });

    it('schreibt die Loyalitäts-Schlüssel lesbar aus', () => {
      expect(component.getLpDisplayName('FederalAdmin')).toBe(LOYALTY_LABELS['FederalAdmin']);
      // Ein unbekannter Schlüssel bleibt stehen - besser als ein leeres Feld.
      expect(component.getLpDisplayName('Eigener')).toBe('Eigener');
    });

    it('findet die Symbole der Schiffsklassen', () => {
      expect(component.getShipIconUrl('Frigate')).toBe(SHIP_CLASS_ICONS['Frigate']);
      expect(component.getShipIconUrl('Gibtsnicht')).toBe(FALLBACK_ICON);
    });
  });
});
