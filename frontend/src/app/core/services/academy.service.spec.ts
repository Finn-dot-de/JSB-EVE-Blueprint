import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { environment } from '../../../environments/environment';
import { AcademyService, SaveInterestDto, SaveTopicDto } from './academy.service';

/**
 * Der Dienst ist eine dünne Hülle um je eine Adresse. Geprüft wird deshalb genau
 * das, was beim Umbenennen still kaputtgeht: Adresse und Methode.
 *
 * <p>Zwei Adressen sind hier besonders leicht zu verwechseln - `/api/academy`
 * und `/api/admin/academy` liegen einen Wortstamm auseinander und tragen völlig
 * verschiedene Rechtekreise.</p>
 */
describe('AcademyService', () => {
  const apiUrl = `${environment.apiUrl}/academy`;
  const adminUrl = `${environment.apiUrl}/admin/academy`;

  let service: AcademyService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [AcademyService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AcademyService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  // Ohne diese Zeile bliebe ein Aufruf, den kein Test erwartet hat, unbemerkt -
  // und ein versehentlich verdoppelter Abruf fiele nie auf.
  afterEach(() => httpMock.verify());

  it('holt die Themenliste ohne Lehrpläne und das einzelne Thema mit', () => {
    // Zwei getrennte Adressen mit Absicht: ginge der Lehrplan an der Liste mit,
    // gingen bei zwölf Themen zwölf Lehrpläne über die Leitung, bei jedem Laden.
    service.getTopics().subscribe();
    expect(httpMock.expectOne(`${apiUrl}/topics`).request.method).toBe('GET');

    service.getTopic(7).subscribe();
    expect(httpMock.expectOne(`${apiUrl}/topics/7`).request.method).toBe('GET');

    httpMock.match(() => true).forEach((anfrage) => anfrage.flush([]));
  });

  it('bekundet Interesse mit PUT und ohne jede Account-Id im Pfad oder Rumpf', () => {
    // Der Kern der Absicherung dieses Features: es gibt keinen Weg, eine fremde
    // Id hereinzureichen. Stünde sie im Pfad oder im Rumpf, wäre der Aufruf ein
    // Hebel, jedem beliebigen Mitglied eine beliebige Bekundung unterzuschieben.
    // PUT und nicht POST, weil ein zweiter Aufruf keine zweite Zeile erzeugt.
    const bekundung: SaveInterestDto = {
      weekdays: ['TUESDAY', 'THURSDAY'],
      timeWindows: ['EU_PRIME'],
      note: null,
    };
    service.saveInterest(7, bekundung).subscribe();

    const anfrage = httpMock.expectOne(`${apiUrl}/topics/7/interest`);
    expect(anfrage.request.method).toBe('PUT');
    expect(anfrage.request.body).toEqual(bekundung);
    expect(Object.keys(anfrage.request.body)).toEqual(['weekdays', 'timeWindows', 'note']);
    anfrage.flush({});
  });

  it('zieht die eigene Bekundung mit DELETE unter derselben Adresse zurück', () => {
    // Dieselbe Adresse wie das Speichern, nur mit anderer Methode: es gibt
    // keinen Zustand "zurückgezogen", weil es nie einen Antrag gab.
    service.withdrawInterest(7).subscribe();
    const anfrage = httpMock.expectOne(`${apiUrl}/topics/7/interest`);
    expect(anfrage.request.method).toBe('DELETE');
    anfrage.flush(null);
  });

  it('holt die Namensliste unter der Themen-Adresse - ohne Betrachter im Pfad', () => {
    // Der Betrachter steht nicht im Pfad, obwohl die Adresse den engsten
    // Rechtekreis des Dienstes trägt: der Server nimmt den angemeldeten. Stünde
    // er im Pfad, wäre er eine Behauptung, die sich fälschen ließe.
    service.getInterested(7).subscribe();
    const anfrage = httpMock.expectOne(`${apiUrl}/topics/7/interest`);
    expect(anfrage.request.method).toBe('GET');
    anfrage.flush([]);
  });

  it('reicht die Ablehnung des Servers durch, statt eine leere Liste zu liefern', () => {
    // Wer nicht zum Sichtkreis gehört, bekommt 403. Das muss beim Aufrufer als
    // Fehler ankommen: eine hier abgefangene leere Liste läse sich als "niemand
    // hat Interesse" - genau die Falschaussage, die das Backend mit der Ausnahme
    // vermeidet.
    let status = 0;
    service.getInterested(7).subscribe({ error: (err) => (status = err.status) });
    httpMock
      .expectOne(`${apiUrl}/topics/7/interest`)
      .flush({ message: 'Nicht erlaubt.' }, { status: 403, statusText: 'Forbidden' });

    expect(status).toBe(403);
  });

  it('pflegt Themen unter dem eigenen Verwaltungs-Präfix', () => {
    // /api/academy und /api/admin/academy tragen völlig verschiedene
    // Rechtekreise. Rutschte eine Pflege-Adresse auf den öffentlichen Pfad,
    // liefe sie ohne den Riegel des Autorenkreises am Controller.
    service.getAdminTopics().subscribe();
    expect(httpMock.expectOne(`${adminUrl}/topics`).request.method).toBe('GET');
    httpMock.match(() => true).forEach((anfrage) => anfrage.flush([]));

    const neu: SaveTopicDto = {
      id: null,
      title: 'EWar Grundlagen',
      summary: 'Dampener, Painter, Jammer',
      description: '## Inhalt\n- Dampener gegen Logi',
      active: true,
      teacherRoleNames: ['ROLE_A38'],
    };
    service.saveTopic(neu).subscribe();
    const speichern = httpMock.expectOne(`${adminUrl}/topics`);
    expect(speichern.request.method).toBe('POST');
    // Der Lehrplan geht als ROHER Markdown-Text hinaus und nicht als fertiges
    // Markup: gerendert wird ausschließlich im Browser, aus dem Token-Modell.
    expect(speichern.request.body.description).toBe('## Inhalt\n- Dampener gegen Logi');
    // Leer wäre eine andere Aussage als "nicht mitgeschickt": ohne das Feld
    // nähme der Server keine Änderung an den Ausbilderrollen an.
    expect(speichern.request.body.teacherRoleNames).toEqual(['ROLE_A38']);
    speichern.flush({});

    service.deleteTopic(4).subscribe();
    const loeschen = httpMock.expectOne(`${adminUrl}/topics/4`);
    expect(loeschen.request.method).toBe('DELETE');
    loeschen.flush(null);
  });
});
