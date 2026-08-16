import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthService, AuthUser } from './auth.service';
import { environment } from '../../../environments/environment';
import { ToastService } from './toast.service';

describe('AuthService', () => {
  let httpMock: HttpTestingController;
  let router: { navigate: ReturnType<typeof vi.fn>; navigateByUrl: ReturnType<typeof vi.fn> };
  let toastService: { error: ReturnType<typeof vi.fn> };

  const meUrl = `${environment.apiUrl}/auth/me`;

  const user: AuthUser = {
    characterId: 95465499,
    characterName: 'Pilot Eins',
    portraitUrl: 'egal',
    roles: ['ROLE_USER', 'ROLE_DIRECTOR'],
  };

  /** Legt den Dienst an; sein Konstruktor fragt sofort den Anmeldestatus ab. */
  function createService(): AuthService {
    const service = TestBed.inject(AuthService);
    return service;
  }

  /** Der Anmeldedienst merkt sich das Sprungziel im localStorage. */
  let storage: Map<string, string>;

  beforeEach(() => {
    router = { navigate: vi.fn(), navigateByUrl: vi.fn() };
    toastService = { error: vi.fn() };
    storage = new Map<string, string>();
    vi.stubGlobal('localStorage', {
      getItem: (key: string) => storage.get(key) ?? null,
      setItem: (key: string, value: string) => storage.set(key, value),
      removeItem: (key: string) => storage.delete(key),
      clear: () => storage.clear(),
    });

    TestBed.configureTestingModule({
      providers: [
        AuthService,
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: Router, useValue: router },
        { provide: ToastService, useValue: toastService },
      ],
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    httpMock.verify();
  });

  it('übernimmt den angemeldeten Nutzer beim Start', () => {
    const service = createService();

    httpMock.expectOne(meUrl).flush(user);

    expect(service.currentUser()?.characterName).toBe('Pilot Eins');
    expect(service.loading()).toBe(false);
  });

  it('bleibt ohne Anmeldung leer statt zu scheitern', () => {
    const service = createService();

    httpMock.expectOne(meUrl).flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(service.currentUser()).toBeNull();
    expect(service.loading()).toBe(false);
  });

  it('baut die Portrait-Adresse aus der Charakter-ID', () => {
    const service = createService();

    httpMock.expectOne(meUrl).flush(user);

    expect(service.portraitUrl()).toBe(
      'https://images.evetech.net/characters/95465499/portrait?size=128',
    );
  });

  it('liefert ohne Anmeldung keine Portrait-Adresse', () => {
    const service = createService();

    httpMock.expectOne(meUrl).flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(service.portraitUrl()).toBeNull();
  });

  it('führt nach der Anmeldung auf die zuvor gewünschte Seite', () => {
    // Wer direkt auf eine geschützte Seite geht, soll dort auch landen.
    localStorage.setItem('redirectAfterLogin', '/corp/assets');
    createService();

    httpMock.expectOne(meUrl).flush(user);

    expect(router.navigateByUrl).toHaveBeenCalledWith('/corp/assets');
    expect(localStorage.getItem('redirectAfterLogin')).toBeNull();
  });

  describe('Rollenprüfung', () => {
    it('erkennt eine vorhandene Rolle', () => {
      const service = createService();
      httpMock.expectOne(meUrl).flush(user);

      expect(service.hasAnyRole(['ROLE_DIRECTOR'])).toBe(true);
      expect(service.hasAnyRole(['ROLE_CEO', 'ROLE_DIRECTOR'])).toBe(true);
    });

    it('verneint eine fehlende Rolle', () => {
      const service = createService();
      httpMock.expectOne(meUrl).flush(user);

      expect(service.hasAnyRole(['ROLE_CEO'])).toBe(false);
      expect(service.hasAnyRole([])).toBe(false);
    });

    it('verneint ohne Anmeldung jede Rolle', () => {
      const service = createService();
      httpMock.expectOne(meUrl).flush(null, { status: 401, statusText: 'Unauthorized' });

      expect(service.hasAnyRole(['ROLE_USER'])).toBe(false);
    });
  });

  it('meldet einen Fehlschlag beim Abmelden', () => {
    // Ohne Rückmeldung säße der Nutzer scheinbar weiter angemeldet da.
    const service = createService();
    httpMock.expectOne(meUrl).flush(user);

    service.logout();
    httpMock
      .expectOne(`${environment.apiUrl}/auth/logout`)
      .flush(null, { status: 500, statusText: 'Server Error' });

    expect(toastService.error).toHaveBeenCalled();
    expect(service.currentUser()).not.toBeNull();
  });

  it('räumt beim Abmelden auf und führt zur Startseite', () => {
    const service = createService();
    httpMock.expectOne(meUrl).flush(user);

    service.logout();
    httpMock.expectOne(`${environment.apiUrl}/auth/logout`).flush({});

    expect(service.currentUser()).toBeNull();
    expect(router.navigate).toHaveBeenCalledWith(['/home']);
  });
});
