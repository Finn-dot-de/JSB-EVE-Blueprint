import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { MiningService } from './mining.service';
import { environment } from '../../../environments/environment';

describe('MiningService', () => {
  let service: MiningService;
  let httpMock: HttpTestingController;

  const apiUrl = `${environment.apiUrl}/mining`;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [MiningService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(MiningService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('lädt die eigene Bilanz', () => {
    service.getMyLedger().subscribe();

    httpMock.expectOne(`${apiUrl}/my-ledger`).flush({});
  });

  it('lädt die Rangliste ohne Monat, wenn keiner gewählt ist', () => {
    service.getLeaderboard(null).subscribe();

    const request = httpMock.expectOne((req) => req.url === `${apiUrl}/leaderboard`);
    expect(request.request.params.has('month')).toBe(false);
    request.flush({});
  });

  it('lädt die Rangliste für einen gewählten Monat', () => {
    service.getLeaderboard('2026-08').subscribe();

    const request = httpMock.expectOne((req) => req.url === `${apiUrl}/leaderboard`);
    expect(request.request.params.get('month')).toBe('2026-08');
    request.flush({});
  });

  it('verwaltet die Steuersätze über die passenden Methoden', () => {
    service.getTaxRates().subscribe();
    expect(httpMock.expectOne(`${apiUrl}/taxes`).request.method).toBe('GET');
    httpMock.verify();

    const rate = {
      typeId: 1230,
      typeName: 'Veldspar',
      category: 'ORE',
      taxPercentage: 10,
      currentJitaBuy: 5,
    };
    service.saveTaxRate(rate).subscribe();
    const save = httpMock.expectOne(`${apiUrl}/taxes`);
    expect(save.request.method).toBe('POST');
    expect(save.request.body).toEqual(rate);
    save.flush(rate);

    service.deleteTaxRate(1230).subscribe();
    const remove = httpMock.expectOne(`${apiUrl}/taxes/1230`);
    expect(remove.request.method).toBe('DELETE');
    remove.flush(null);
  });

  it('setzt einen Satz für eine ganze Steuerklasse', () => {
    service.saveBulkTax('ORE', 12.5).subscribe();

    const request = httpMock.expectOne(
      `${apiUrl}/taxes/bulk?category=ORE&taxPercentage=12.5`,
    );
    expect(request.request.method).toBe('POST');
    request.flush(null);
  });

  it('lädt die Admin-Bilanzen', () => {
    service.getAdminLedgers().subscribe();

    httpMock.expectOne(`${apiUrl}/admin/ledgers`).flush([]);
  });
});
