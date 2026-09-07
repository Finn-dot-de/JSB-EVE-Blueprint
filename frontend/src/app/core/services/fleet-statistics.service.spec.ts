import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { environment } from '../../../environments/environment';
import {
  FleetStatisticsService,
  ZEITRAEUME,
  ZEITRAUM_VORGABE,
} from './fleet-statistics.service';

/**
 * Der Dienst ist eine dünne Hülle um eine Adresse. Geprüft wird deshalb genau
 * das, was beim Umbenennen still kaputtginge - und drei Zusagen, die dieser
 * Reiter tragen muss.
 *
 * <p>Erstens: <b>eine</b> Anfrage für die ganze Seite. Getrennt geladene Panels
 * stünden nebeneinander mit 40 und 41 Flotten, sobald zwischendurch eine Flotte
 * geschlossen wird - und die Seite verlöre die Glaubwürdigkeit, um die es hier
 * geht.</p>
 *
 * <p>Zweitens: der Zeitraum steht im Parameter und nicht im Pfad, und er wird
 * mitgeschickt. Ohne ihn nähme der Server seine eigene Vorgabe - dann stünde in
 * der Kopfzeile ein anderer Zeitraum als in der Reiterleiste.</p>
 *
 * <p>Drittens: Der Dienst rechnet nichts. Was ankommt, geht unverändert weiter -
 * insbesondere bleibt jede Quote ein Paar aus Zähler und Nenner.</p>
 */
describe('FleetStatisticsService', () => {
  const apiUrl = `${environment.apiUrl}/fleets/statistics`;

  let service: FleetStatisticsService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [FleetStatisticsService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(FleetStatisticsService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  // Ohne diese Zeile bliebe eine Anfrage, die kein Test erwartet hat, unbemerkt.
  afterEach(() => httpMock.verify());

  it('holt die ganze Seite mit GET unter einer Adresse', () => {
    service.statistik(90).subscribe();

    const anfrage = httpMock.expectOne(r => r.url === apiUrl);
    expect(anfrage.request.method).toBe('GET');
    anfrage.flush({});
  });

  it('schickt den gewählten Zeitraum als Parameter mit', () => {
    // Ohne den Parameter nähme der Server seine Vorgabe - und die Kopfzeile
    // spräche von 90 Tagen, während der Leser 30 angeklickt hat.
    service.statistik(30).subscribe();

    const anfrage = httpMock.expectOne(r => r.url === apiUrl);
    expect(anfrage.request.params.get('tage')).toBe('30');
    anfrage.flush({});
  });

  it('stellt genau eine Anfrage je Aufruf, nicht eine je Panel', () => {
    service.statistik(180).subscribe();

    httpMock.expectOne(r => r.url === apiUrl).flush({});
    httpMock.verify();
  });

  it('reicht die Antwort unverändert durch und rechnet keine Quote aus', () => {
    // Der Kern des ganzen Aufbaus: Der Anteil bleibt ein Paar. Käme hier
    // irgendwo ein Quotient heraus, wäre "100 %" bei einer einzigen Flotte
    // wieder möglich.
    const geliefert = {
      kopf: {
        flotten: 1, accounts: 2, charaktere: 3, fcs: 1,
        liveAnteil: { zaehler: 1, nenner: 1 },
      },
      teilnahme: { zeilen: [], einmalige: [], ohneVerbindung: { zaehler: 1, nenner: 2 } },
    };
    let empfangen: unknown = null;

    service.statistik(90).subscribe(d => (empfangen = d));
    httpMock.expectOne(r => r.url === apiUrl).flush(geliefert);

    expect(empfangen).toEqual(geliefert);
  });

  it('bietet genau die drei Zeiträume an, die der Server annimmt', () => {
    // Der Server weist einen anderen Wert ab, statt ihn still durch 90 zu
    // ersetzen. Eine vierte Schaltfläche hier hieße also ein Fehler dort.
    expect([...ZEITRAEUME]).toEqual([30, 90, 180]);
    expect(ZEITRAEUME).toContain(ZEITRAUM_VORGABE);
  });

  it('reicht einen Fehler des Servers durch, statt ihn zu schlucken', () => {
    let status = 0;

    service.statistik(90).subscribe({ error: (err) => (status = err.status) });
    httpMock.expectOne(r => r.url === apiUrl)
      .flush({ message: 'Die FAT-Statistik sehen nur FCs und Direktoren.' },
        { status: 403, statusText: 'Forbidden' });

    // 403 ist hier der Regelfall und nicht die Ausnahme: Die Sperre steht im
    // Dienst des Servers, nicht in der Sichtbarkeit des Reiters.
    expect(status).toBe(403);
  });
});
