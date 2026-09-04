import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { environment } from '../../../environments/environment';
import { FleetPingService, PingRequestDto } from './fleet-ping.service';

/**
 * Der Dienst ist eine dünne Hülle um je eine Adresse. Geprüft wird deshalb genau
 * das, was beim Umbenennen still kaputtgeht: Adresse und Methode.
 *
 * <p>Bei diesem Feature ist das mehr als Formsache. `/api/fleet/pings` und
 * `/api/fleets` liegen einen Buchstaben auseinander und bedeuten Verschiedenes -
 * der eine kündigt an, der andere zählt ab. Und `POST` gegen `PUT` ist hier der
 * Unterschied zwischen einem zweiten `@here` im Kanal und einer stillen
 * Korrektur an derselben Nachricht.</p>
 */
describe('FleetPingService', () => {
  const apiUrl = `${environment.apiUrl}/fleet/pings`;

  let service: FleetPingService;
  let httpMock: HttpTestingController;

  const befehl: PingRequestDto = {
    fleetType: 'Roam',
    doctrine: 'Armor',
    formupLocation: 'Jita IV - Moon 4',
    formupTime: '2026-09-03T19:00:00.000Z',
    comms: 'Discord',
    srpCovered: null,
    notes: null,
    erwaehnung: 'HIER',
    rolleId: null,
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [FleetPingService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(FleetPingService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  // Ohne diese Zeile bliebe ein Aufruf, den kein Test erwartet hat, unbemerkt -
  // und ein versehentlich verdoppelter Ping fiele nie auf.
  afterEach(() => httpMock.verify());

  it('fragt den Einrichtungsstand unter einer eigenen Adresse ab', () => {
    // Eigene Adresse und nicht ein Feld der Liste: die Oberfläche muss wissen,
    // ob ein Kanal hinterlegt ist, bevor sie überhaupt ein Formular anbietet.
    service.status().subscribe();

    const anfrage = httpMock.expectOne(`${apiUrl}/status`);
    expect(anfrage.request.method).toBe('GET');
    anfrage.flush({ verfuegbar: true, rolleKonfiguriert: false, hinweis: null });
  });

  it('holt die wählbaren Ping-Rollen unter einer eigenen Adresse', () => {
    // Eigene Adresse und kein Feld des Status: Der Status wird bei jedem
    // Öffnen des Reiters geladen, diese Liste erst, wenn jemand wirklich eine
    // Rolle wählen will - und sie kostet im Server einen Aufruf zu Discord.
    service.rollen().subscribe();

    const anfrage = httpMock.expectOne(`${apiUrl}/rollen`);
    expect(anfrage.request.method).toBe('GET');
    anfrage.flush([]);
  });

  it('holt die Rechenschaftsliste unter der Grundadresse', () => {
    service.letzte().subscribe();

    const anfrage = httpMock.expectOne(apiUrl);
    expect(anfrage.request.method).toBe('GET');
    anfrage.flush([]);
  });

  it('setzt einen Ping mit POST ab und schickt die Erwähnung unverändert mit', () => {
    // Die Erwähnung ist das einzige Feld dieses Rumpfes, an dem hängt, ob
    // jemandes Telefon leuchtet. Sie darf unterwegs weder umbenannt noch
    // weggelassen werden - der Server liest genau diesen Schlüssel.
    service.senden(befehl).subscribe();

    const anfrage = httpMock.expectOne(apiUrl);
    expect(anfrage.request.method).toBe('POST');
    expect(anfrage.request.body).toEqual(befehl);
    expect(anfrage.request.body.erwaehnung).toBe('HIER');
    anfrage.flush({});
  });

  it('schickt die Formup-Zeit mit Zonenversatz, nicht als Ortszeit', () => {
    // Ohne Versatz weist Jackson die Anfrage ab - und das ist die gewollte
    // Richtung: eine Zeit ohne Zone wäre entweder die des Servers, die des
    // Browsers oder EVE-Zeit, und welche gemeint war, wüsste hinterher niemand.
    service.senden(befehl).subscribe();

    const anfrage = httpMock.expectOne(apiUrl);
    expect(anfrage.request.body.formupTime).toMatch(/Z$|[+-]\d{2}:\d{2}$/);
    anfrage.flush({});
  });

  it('lässt "form up now" als null durch, statt eine Uhrzeit zu erfinden', () => {
    // null ist hier eine Aussage und keine fehlende Angabe: "jetzt" bleibt
    // wahr, eine ausgeschriebene Uhrzeit ist eine Minute später Vergangenheit.
    service.senden({ ...befehl, formupTime: null }).subscribe();

    const anfrage = httpMock.expectOne(apiUrl);
    expect(anfrage.request.body.formupTime).toBeNull();
    anfrage.flush({});
  });

  it('ändert mit PUT auf die Id - nicht mit einem zweiten POST', () => {
    // Ein zweiter POST wäre eine zweite Nachricht im Kanal. Zwei
    // widersprüchliche Pings sind schlimmer als ein falscher, weil niemand
    // weiß, welcher gilt.
    service.bearbeiten(42, befehl).subscribe();

    const anfrage = httpMock.expectOne(`${apiUrl}/42`);
    expect(anfrage.request.method).toBe('PUT');
    expect(anfrage.request.body).toEqual(befehl);
    anfrage.flush({});
  });

  it('sagt mit POST auf /absage ab und nicht mit DELETE', () => {
    // Gelöscht wird nichts: Der Ping bleibt in der Liste, und im Kanal bleibt
    // die Nachricht stehen - sie sagt ab jetzt nur etwas anderes.
    service.absagen(42, 'Ziel ist weg').subscribe();

    const anfrage = httpMock.expectOne(`${apiUrl}/42/absage`);
    expect(anfrage.request.method).toBe('POST');
    expect(anfrage.request.body).toEqual({ grund: 'Ziel ist weg' });
    anfrage.flush({});
  });

  it('schickt auch die Absage ohne Grund als Rumpf mit null', () => {
    // Der Server erwartet einen Rumpf, der leer sein darf. Kein Rumpf wäre
    // etwas anderes als ein leerer - und die Absage ist der Aufruf, bei dem am
    // wenigsten etwas an einer Formalität scheitern darf.
    service.absagen(7, null).subscribe();

    const anfrage = httpMock.expectOne(`${apiUrl}/7/absage`);
    expect(anfrage.request.body).toEqual({ grund: null });
    anfrage.flush({});
  });

  it('reicht die Ablehnung des Servers durch, statt sie zu verschlucken', () => {
    // 429 kommt aus der Wartezeit, 503 aus einem fehlenden Kanal. Beides muss
    // beim Aufrufer ankommen: ein hier abgefangener Fehler läse sich als
    // "gepingt", und der FC wartete auf eine Flotte, die niemand gerufen hat.
    let status = 0;
    service.senden(befehl).subscribe({ error: (err) => (status = err.status) });

    httpMock.expectOne(apiUrl).flush(
      { message: 'Zu schnell hintereinander.' },
      { status: 429, statusText: 'Too Many Requests' });

    expect(status).toBe(429);
  });
});
