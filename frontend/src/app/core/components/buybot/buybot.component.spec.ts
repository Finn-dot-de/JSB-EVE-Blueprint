import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { BuybotComponent } from './buybot.component';
import { ParsedItemDto, PublicConfig } from '../../services/buybot.service';
import { environment } from '../../../../environments/environment';

/**
 * Tests der Anzeige-Logik des Rechners.
 *
 * Geprüft wird, was der Spieler am Ende sieht: übersetzter Status, der zu kopierende
 * Vertragspreis und der Wartungsmodus.
 */
describe('BuybotComponent', () => {
  let component: BuybotComponent;
  let httpMock: HttpTestingController;

  /** Beantwortet die Anfragen, die beim Start von selbst losgehen. */
  function answerStartupRequests(config?: Partial<PublicConfig>) {
    // Der AuthService fragt beim Erzeugen, ob jemand angemeldet ist - hier niemand.
    httpMock.expectOne(`${environment.apiUrl}/auth/me`).flush(null, { status: 401, statusText: 'Unauthorized' });
    httpMock.expectOne(`${environment.apiUrl}/buybot/config`).flush({
      botEnabled: true,
      ...config
    });
    httpMock.expectOne(`${environment.apiUrl}/buybot/injector-price`)
      .flush({ typeId: 40520, name: 'Large Skill Injector', price: 750_000_000 });
    httpMock.expectOne(`${environment.apiUrl}/buybot/locations`)
      .flush([{ id: 7, name: 'Teststation', transportFee: 0, securityFee: 0, stationId: 60003760 }]);
  }

  function item(overrides: Partial<ParsedItemDto>): ParsedItemDto {
    return {
      rawName: 'Tritanium', quantity: 100, typeId: 34, volumeEach: 0.01, categoryId: 4,
      resolved: true, status: 'OK', statusCode: 'OK', unitPrice: 1, totalPrice: 100,
      appliedModifier: 90, priceSource: 'MARKET',
      ...overrides
    } as ParsedItemDto;
  }

  beforeEach(() => {
    // jsdom kennt matchMedia nicht; die Komponente fragt darüber die Bildschirmbreite ab.
    Object.defineProperty(window, 'matchMedia', {
      configurable: true,
      value: (query: string) => ({
        matches: false,
        media: query,
        addEventListener: () => undefined,
        removeEventListener: () => undefined
      })
    });

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [BuybotComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()]
    });
    const fixture = TestBed.createComponent(BuybotComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('übersetzt die Statuscodes in die gewählte Sprache', () => {
    answerStartupRequests();

    expect(component.statusLabel(item({ statusCode: 'OK' }))).toBe('OK');
    expect(component.statusLabel(item({ statusCode: 'BLOCKED' }))).toBe('GESPERRT');
    expect(component.statusLabel(item({ statusCode: 'NOT_LISTED' }))).toBe('NICHT GELISTET');
    expect(component.statusLabel(item({ statusCode: 'UNKNOWN' }))).toBe('NICHT GEFUNDEN');

    component.i18n.setLang('en');
    expect(component.statusLabel(item({ statusCode: 'BLOCKED' }))).toBe('BLOCKED');
  });

  it('färbt Fehler rot und Sperren gelb', () => {
    answerStartupRequests();

    expect(component.statusClass(item({ statusCode: 'OK' }))['status-ok']).toBe(true);
    expect(component.statusClass(item({ statusCode: 'UNKNOWN' }))['status-err']).toBe(true);
    expect(component.statusClass(item({ statusCode: 'BLOCKED' }))['status-warn']).toBe(true);
  });

  it('rundet den Vertragspreis auf volle ISK, damit er eintippbar bleibt', () => {
    answerStartupRequests();
    component.totalPrice = 1234567.89;

    expect(component.contractPrice).toBe(1234568);
  });

  it('rechnet den Preis in Skill Injectors um', () => {
    answerStartupRequests();
    component.totalPrice = 1_500_000_000;

    expect(component.injectorCount).toBe(2);
  });

  it('zeigt keine Injector-Zahl, solange nichts berechnet wurde', () => {
    answerStartupRequests();

    expect(component.injectorCount).toBeNull();
  });

  it('erkennt den Wartungsmodus und übernimmt den hinterlegten Text', () => {
    answerStartupRequests({
      botEnabled: false,
      maintenanceTitle: 'GERADE ZU',
      maintenanceMessage: 'Wir sind gleich wieder da.'
    });

    expect(component.isMaintenance).toBe(true);
    expect(component.maintenanceTitle).toBe('GERADE ZU');
    expect(component.maintenanceMessage).toBe('Wir sind gleich wieder da.');
  });

  it('nutzt den eingebauten Text, wenn kein eigener hinterlegt ist', () => {
    answerStartupRequests({ botEnabled: false });

    expect(component.maintenanceTitle).toBe('BUYBOT PAUSIERT');
  });

  it('meldet nicht ankaufbare Positionen für die Vertragswarnung', () => {
    answerStartupRequests();

    component.items = [item({ statusCode: 'OK' })];
    expect(component.hasRejectedItems).toBe(false);

    component.items = [item({ statusCode: 'OK' }), item({ statusCode: 'BLOCKED' })];
    expect(component.hasRejectedItems).toBe(true);
  });

  it('berechnet nicht, solange der Wartungsmodus aktiv ist', () => {
    answerStartupRequests({ botEnabled: false });
    component.rawInput = 'Tritanium 100';

    component.calculate();

    httpMock.expectNone(`${environment.apiUrl}/buybot/calculate`);
  });
});
