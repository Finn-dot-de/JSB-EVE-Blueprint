import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { BuybotAdminComponent } from './buybot-admin.component';
import { environment } from '../../../../environments/environment';

/**
 * Tests des Admin-Panels.
 *
 * Der wichtigste Fall ist eine gewachsene Datenbank: dort stehen in den später
 * ergänzten Spalten noch NULL-Werte. Werden die nicht abgefangen, zeigen die
 * Auswahlfelder nichts an und ein Speichern schreibt Unsinn zurück.
 */
describe('BuybotAdminComponent', () => {
  let component: BuybotAdminComponent;
  let httpMock: HttpTestingController;

  const adminUrl = `${environment.apiUrl}/admin/buybot`;

  /** Beantwortet alle Anfragen, die das Panel beim Öffnen stellt. */
  function answerStartupRequests(config: Record<string, unknown>) {
    httpMock.expectOne(`${adminUrl}/config`).flush(config);
    httpMock.expectOne(`${adminUrl}/locations`).flush([]);
    httpMock.expectOne(`${adminUrl}/categories`).flush([]);
    httpMock.expectOne(`${adminUrl}/types`).flush([]);
    httpMock.expectOne(`${adminUrl}/characters`).flush([]);
    httpMock.expectOne(`${adminUrl}/contract-check/results?limit=25`).flush([]);
    httpMock.expectOne(`${adminUrl}/contract-check/status`).flush({
      enabled: false, intervalMinutes: 15, notifyTarget: 'NONE',
      lastRunSuccess: true, scanned: 0, checked: 0, notified: 0, pendingNotifications: 0
    });
    httpMock.expectOne(`${environment.apiUrl}/admin/audit?limit=50`).flush({ entries: [], total: 0 });
  }

  beforeEach(() => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [BuybotAdminComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()]
    });
    const fixture = TestBed.createComponent(BuybotAdminComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
  });

  afterEach(() => {
    httpMock.verify();
    component.ngOnDestroy();
  });

  it('füllt fehlende Werte einer gewachsenen Datenbank mit Vorgaben auf', () => {
    answerStartupRequests({
      priceBasis: 'buy',
      globalModifier: 90,
      botEnabled: null,
      contractCheckEnabled: null,
      notifyOnOk: null,
      reprocessingRate: null,
      contractCheckCharacterId: null,
      notifyMailRecipientId: null,
      botTexts: null
    });

    expect(component.config.botEnabled).toBe(true);
    expect(component.config.contractCheckEnabled).toBe(false);
    expect(component.config.notifyOnOk).toBe(true);
    expect(component.config.reprocessingRate).toBe(50);
    expect(component.config.botTexts).toBeTruthy();
  });

  it('macht aus "nicht gesetzt" eine 0, damit die Auswahlfelder etwas anzeigen', () => {
    answerStartupRequests({
      priceBasis: 'buy',
      globalModifier: 90,
      contractCheckCharacterId: null,
      notifyMailRecipientId: null
    });

    expect(component.config.contractCheckCharacterId).toBe(0);
    expect(component.config.notifyMailRecipientId).toBe(0);
  });

  it('lässt gesetzte Werte unangetastet', () => {
    answerStartupRequests({
      priceBasis: 'sell',
      globalModifier: 85,
      botEnabled: false,
      reprocessingRate: 62.5,
      contractCheckCharacterId: 2118431553,
      notifyOnOk: false
    });

    expect(component.config.priceBasis).toBe('sell');
    expect(component.config.globalModifier).toBe(85);
    expect(component.config.botEnabled).toBe(false);
    expect(component.config.reprocessingRate).toBe(62.5);
    expect(component.config.contractCheckCharacterId).toBe(2118431553);
    expect(component.config.notifyOnOk).toBe(false);
  });

  it('startet mit zugeklappten Listen, damit das Panel kurz bleibt', () => {
    answerStartupRequests({ priceBasis: 'buy', globalModifier: 90 });

    expect(component.isSectionOpen('reports')).toBe(false);
    expect(component.isSectionOpen('audit')).toBe(false);
  });

  it('klappt einen Abschnitt auf und wieder zu', () => {
    answerStartupRequests({ priceBasis: 'buy', globalModifier: 90 });

    component.toggleSection('audit');
    expect(component.isSectionOpen('audit')).toBe(true);
    // Der andere Abschnitt bleibt davon unberuehrt
    expect(component.isSectionOpen('reports')).toBe(false);

    component.toggleSection('audit');
    expect(component.isSectionOpen('audit')).toBe(false);
  });

  it('zeigt an, wenn das Reprocessing-Häkchen bei einem Item wirkungslos ist', () => {
    answerStartupRequests({ priceBasis: 'buy', globalModifier: 90 });

    const verwertbar = { typeId: 1230, typeName: 'Veldspar', modifier: 90, isBlacklisted: false,
      useReprocessedValue: true, reprocessable: true };
    const endprodukt = { typeId: 34, typeName: 'Tritanium', modifier: 90, isBlacklisted: false,
      useReprocessedValue: true, reprocessable: false };
    const ohneHaken = { typeId: 620, typeName: 'Osprey', modifier: 90, isBlacklisted: false,
      useReprocessedValue: false, reprocessable: true };

    expect(component.reprocessLabel(verwertbar)).toBe('JA');
    expect(component.reprocessClass(verwertbar)).toBe('status-ok');

    expect(component.reprocessLabel(endprodukt)).toBe('JA (wirkungslos)');
    expect(component.reprocessClass(endprodukt)).toBe('status-warn');

    expect(component.reprocessLabel(ohneHaken)).toBe('-');
    expect(component.reprocessClass(ohneHaken)).toBe('');
  });

  it('kennzeichnet Befunde und Schweregrade farblich', () => {
    answerStartupRequests({ priceBasis: 'buy', globalModifier: 90 });

    expect(component.verdictClass('OK')).toBe('status-ok');
    expect(component.verdictClass('WARN')).toBe('status-warn');
    expect(component.verdictClass('REJECT')).toBe('status-err');

    expect(component.severityClass('INFO')).toBe('status-ok');
    expect(component.severityClass('WARN')).toBe('status-warn');
    expect(component.severityClass('ERROR')).toBe('status-err');
  });
});
