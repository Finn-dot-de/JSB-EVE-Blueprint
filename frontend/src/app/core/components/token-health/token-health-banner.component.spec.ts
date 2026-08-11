import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { environment } from '../../../../environments/environment';
import { TokenHealthBannerComponent } from './token-health-banner.component';

/**
 * Der Hinweis auf abgelaufene EVE-Anmeldungen.
 *
 * Der Anlass: läuft der Refresh-Token eines Charakters ab, veralten seine
 * Daten stillschweigend. Im Serverlog stand das, im Auth nirgends.
 */
describe('TokenHealthBannerComponent', () => {
  let httpMock: HttpTestingController;

  const url = `${environment.apiUrl}/characters/token-health`;

  function build(): TokenHealthBannerComponent {
    return TestBed.runInInjectionContext(() => new TokenHealthBannerComponent());
  }

  beforeEach(() => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('zeigt nichts, solange alle Charaktere angemeldet sind', () => {
    const component = build();
    httpMock.expectOne(url).flush([]);

    // Ein Banner, das immer da ist, liest bald niemand mehr.
    expect(component.betroffene()).toEqual([]);
  });

  it('nennt die betroffenen Charaktere beim Namen', () => {
    const component = build();
    httpMock.expectOne(url).flush([
      { characterId: 1, name: 'Rat Izia', invalidSince: '2026-08-08T12:00:00Z', reason: 'invalid_grant' },
      { characterId: 2, name: 'Akavera', invalidSince: null, reason: null },
    ]);

    // Ohne die Namen weiß niemand, wo er nachsehen muss.
    expect(component.betroffene().map((c) => c.name)).toEqual(['Rat Izia', 'Akavera']);
  });

  it('bleibt still, wenn der Abruf fehlschlägt', () => {
    const component = build();
    httpMock.expectOne(url).error(new ProgressEvent('401'));

    // Wer nicht angemeldet ist, bekommt eine 401 - ein Hinweis über abgelaufene
    // Anmeldungen wäre auf dem Anmeldebildschirm bestenfalls verwirrend.
    expect(component.betroffene()).toEqual([]);
  });

  it('lässt sich wegklicken', () => {
    const component = build();
    httpMock.expectOne(url).flush([
      { characterId: 1, name: 'Rat Izia', invalidSince: null, reason: null },
    ]);

    component.verbergen.set(true);

    // Wegklicken ja, abstellen nein: beim nächsten Laden ist er wieder da,
    // solange das Problem besteht.
    expect(component.betroffene()).toEqual([]);
  });

  it('sagt in Worten, wie lange das schon geht', () => {
    const component = build();
    httpMock.expectOne(url).flush([]);

    const vorStunden = (h: number) => new Date(Date.now() - h * 3_600_000).toISOString();

    // "seit 3 Tagen" entscheidet darüber, ob man es heute erledigt oder
    // nächste Woche. Ein Zeitstempel tut das nicht.
    expect(component.seit(vorStunden(0.2))).toBe('gerade eben');
    expect(component.seit(vorStunden(5))).toBe('5 h');
    expect(component.seit(vorStunden(24))).toBe('einem Tag');
    expect(component.seit(vorStunden(72))).toBe('3 Tagen');
    expect(component.seit('kein Datum')).toBe('unbekannt');
  });
});
