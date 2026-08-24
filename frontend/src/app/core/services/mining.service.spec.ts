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

  it('lädt die Steuerakte eines Members über seine Account-ID', () => {
    service.getMemberLedger(2118431553).subscribe();

    const request = httpMock.expectOne(`${apiUrl}/admin/ledgers/2118431553`);
    expect(request.request.method).toBe('GET');
    request.flush({});
  });

  it('schickt den Gutschriftsbetrag als Zeichenkette', () => {
    // Der Kern der Sache: als Zahl wäre der Betrag ein double und schon
    // ungenau, bevor er die Leitung erreicht. Was eingetippt wurde, geht
    // unverändert hinaus - gelesen wird es genau einmal, auf dem Server.
    service.grantCredit(2118431553, '12345678901,23', 'Moon-Anteil').subscribe();

    const request = httpMock.expectOne(`${apiUrl}/admin/credits/accounts/2118431553`);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ amount: '12345678901,23', reason: 'Moon-Anteil' });
    expect(typeof request.request.body.amount).toBe('string');
    request.flush({});
  });

  it('lässt einen fehlenden Grund als null durchgehen', () => {
    service.grantCredit(1, '500', null).subscribe();

    const request = httpMock.expectOne(`${apiUrl}/admin/credits/accounts/1`);
    expect(request.request.body).toEqual({ amount: '500', reason: null });
    request.flush({});
  });

  it('nimmt eine Gutschrift per POST zurück, nicht per DELETE', () => {
    // DELETE würde nahelegen, dass etwas verschwindet. Es entsteht aber eine
    // Gegenbuchung, und der Grund dafür gehört in den Rumpf statt in die
    // Adresszeile, wo er in jedem Zugriffsprotokoll landen würde.
    service.reverseCredit(42, 'doppelt gebucht').subscribe();

    const request = httpMock.expectOne(`${apiUrl}/admin/credits/42/reverse`);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ reason: 'doppelt gebucht' });
    request.flush({});
  });
});
