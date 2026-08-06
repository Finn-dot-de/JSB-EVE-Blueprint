import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { AssetService } from './asset.service';
import { environment } from '../../../environments/environment';

describe('AssetService', () => {
  let service: AssetService;
  let httpMock: HttpTestingController;

  const apiUrl = `${environment.apiUrl}/assets`;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [AssetService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AssetService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  describe('Suchparameter', () => {
    it('schickt nur gesetzte Filter mit', () => {
      service.search({ q: 'Nestor', typeId: 587, groupId: null, regionName: '' }).subscribe();

      const request = httpMock.expectOne((req) => req.url === `${apiUrl}/search`);
      expect(request.request.params.get('q')).toBe('Nestor');
      expect(request.request.params.get('typeId')).toBe('587');
      // Leere und fehlende Werte würden serverseitig als Filter zählen.
      expect(request.request.params.has('groupId')).toBe(false);
      expect(request.request.params.has('regionName')).toBe(false);
      request.flush({});
    });

    it('setzt den Gruppierungs-Schalter je nach Aufruf', () => {
      service.search({}).subscribe();
      const flat = httpMock.expectOne((req) => req.url === `${apiUrl}/search`);
      expect(flat.request.params.get('grouped')).toBe('false');
      flat.flush({});

      service.searchGrouped({}).subscribe();
      const grouped = httpMock.expectOne((req) => req.url === `${apiUrl}/search`);
      expect(grouped.request.params.get('grouped')).toBe('true');
      grouped.flush({});
    });

    it('überschreibt einen mitgegebenen Gruppierungs-Schalter', () => {
      service.search({ grouped: true }).subscribe();

      const request = httpMock.expectOne((req) => req.url === `${apiUrl}/search`);
      expect(request.request.params.get('grouped')).toBe('false');
      request.flush({});
    });
  });

  describe('Endpunkte', () => {
    it('fragt die Auswertungen an den erwarteten Adressen ab', () => {
      service.summary().subscribe();
      httpMock.expectOne(`${apiUrl}/summary`).flush({});

      service.holders(587).subscribe();
      httpMock.expectOne(`${apiUrl}/holders/587`).flush({});

      service.memberDetail(1000).subscribe();
      httpMock.expectOne(`${apiUrl}/member/1000`).flush({});

      service.doctrines().subscribe();
      httpMock.expectOne(`${apiUrl}/doctrines`).flush([]);
    });

    it('hängt den Kategorie-Filter nur an, wenn einer gesetzt ist', () => {
      service.filters(6).subscribe();
      const withFilter = httpMock.expectOne((req) => req.url === `${apiUrl}/filters`);
      expect(withFilter.request.params.get('categoryId')).toBe('6');
      withFilter.flush({});

      service.filters(null).subscribe();
      const without = httpMock.expectOne((req) => req.url === `${apiUrl}/filters`);
      expect(without.request.params.has('categoryId')).toBe(false);
      without.flush({});
    });

    it('fragt die Doktrin-Verfügbarkeit mit dem Namen ab', () => {
      service.doctrineReadiness('Armor').subscribe();

      const request = httpMock.expectOne((req) => req.url === `${apiUrl}/doctrines/readiness`);
      expect(request.request.params.get('doctrineName')).toBe('Armor');
      request.flush({});
    });

    it('lädt den Export als Blob, damit das Auth-Cookie mitgeht', () => {
      service.exportCsv({ grouped: true }).subscribe();

      const request = httpMock.expectOne((req) => req.url === `${apiUrl}/export`);
      expect(request.request.responseType).toBe('blob');
      request.flush(new Blob(['csv']));
    });

    it('stößt die Standort-Auflösung per POST an', () => {
      service.resolveLocations().subscribe();

      const request = httpMock.expectOne(`${apiUrl}/locations/resolve`);
      expect(request.request.method).toBe('POST');
      request.flush({});
    });
  });
});
