import { HttpRequest } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree } from '@angular/router';
import { firstValueFrom, of } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { authGuard, roleGuard } from './auth.guard';
import { authInterceptor } from './auth.interceptor';
import { AuthService, AuthUser } from '../services/auth.service';
import { ToastService } from '../services/toast.service';

describe('authInterceptor', () => {
  it('schickt jede Anfrage mit Zugangsdaten', () => {
    // Das Sitzungs-Cookie ist httpOnly - ohne withCredentials ginge es nie mit.
    const request = new HttpRequest('GET', '/api/auth/me');
    let forwarded: HttpRequest<unknown> | null = null;

    authInterceptor(request, (req) => {
      forwarded = req;
      return of();
    }).subscribe();

    expect(forwarded!.withCredentials).toBe(true);
  });

  it('lässt Methode und Adresse unverändert', () => {
    const request = new HttpRequest('POST', '/api/fleets/create', {});
    let forwarded: HttpRequest<unknown> | null = null;

    authInterceptor(request, (req) => {
      forwarded = req;
      return of();
    }).subscribe();

    expect(forwarded!.method).toBe('POST');
    expect(forwarded!.url).toBe('/api/fleets/create');
  });
});

describe('authGuard', () => {
  let storage: Map<string, string>;
  let router: Router;

  const route = {} as ActivatedRouteSnapshot;
  const state = { url: '/corp/assets' } as RouterStateSnapshot;

  /** Der Anmeldedienst im gewünschten Zustand. */
  function authState(user: AuthUser | null, loading = false) {
    return { currentUser: signal(user), loading: signal(loading) };
  }

  function runGuard(auth: unknown) {
    TestBed.configureTestingModule({
      providers: [{ provide: AuthService, useValue: auth }],
    });
    router = TestBed.inject(Router);
    return TestBed.runInInjectionContext(() => authGuard(route, state));
  }

  beforeEach(() => {
    storage = new Map<string, string>();
    vi.stubGlobal('localStorage', {
      getItem: (key: string) => storage.get(key) ?? null,
      setItem: (key: string, value: string) => storage.set(key, value),
      removeItem: (key: string) => storage.delete(key),
      clear: () => storage.clear(),
    });
    TestBed.resetTestingModule();
  });

  afterEach(() => vi.unstubAllGlobals());

  const user: AuthUser = {
    characterId: 1,
    characterName: 'Pilot',
    portraitUrl: '',
    roles: ['ROLE_USER'],
  };

  it('lässt einen angemeldeten Nutzer durch', async () => {
    const result = runGuard(authState(user));

    await expect(firstValueFrom(result as never)).resolves.toBe(true);
  });

  it('führt einen nicht angemeldeten Nutzer auf die Startseite', async () => {
    const result = await firstValueFrom(runGuard(authState(null)) as never);

    expect(result).toBeInstanceOf(UrlTree);
    expect(String(result)).toBe('/home');
  });

  it('merkt sich das gewünschte Ziel für nach der Anmeldung', async () => {
    await firstValueFrom(runGuard(authState(null)) as never);

    expect(localStorage.getItem('redirectAfterLogin')).toBe('/corp/assets');
  });

  it('wartet ab, solange der Anmeldestatus noch geprüft wird', async () => {
    // Sonst würde ein kurzer Ladezustand jeden Direktaufruf abweisen.
    const auth = authState(user, true);
    const result = runGuard(auth);

    let resolved: unknown = 'noch nichts';
    (result as never as { subscribe: (fn: (v: unknown) => void) => void }).subscribe(
      (value) => (resolved = value),
    );
    expect(resolved).toBe('noch nichts');

    auth.loading.set(false);
    // toObservable überträgt den Signalwert über einen Effekt - der muss laufen.
    TestBed.tick();

    expect(resolved).toBe(true);
  });
});

describe('roleGuard', () => {
  const route = {} as ActivatedRouteSnapshot;
  const state = { url: '/groups/rights' } as RouterStateSnapshot;

  const LEADERSHIP = ['ROLE_DIRECTOR', 'ROLE_CEO', 'ROLE_IT_ADMIN'];

  let toastService: { error: ReturnType<typeof vi.fn> };

  /** Ein Anmeldedienst mit genau diesen Rollen. */
  function authState(roles: string[] | null) {
    const user: AuthUser | null = roles
      ? { characterId: 1, characterName: 'Pilot', portraitUrl: '', roles }
      : null;
    return {
      currentUser: signal(user),
      loading: signal(false),
      hasAnyRole: (allowed: string[]) => allowed.some((role) => roles?.includes(role) ?? false),
    };
  }

  function runGuard(auth: unknown) {
    TestBed.configureTestingModule({
      providers: [
        { provide: AuthService, useValue: auth },
        { provide: ToastService, useValue: toastService },
      ],
    });
    return TestBed.runInInjectionContext(() => roleGuard(LEADERSHIP)(route, state));
  }

  beforeEach(() => {
    toastService = { error: vi.fn() };
    const storage = new Map<string, string>();
    vi.stubGlobal('localStorage', {
      getItem: (key: string) => storage.get(key) ?? null,
      setItem: (key: string, value: string) => storage.set(key, value),
      removeItem: (key: string) => storage.delete(key),
      clear: () => storage.clear(),
    });
    TestBed.resetTestingModule();
  });

  afterEach(() => vi.unstubAllGlobals());

  it('lässt jemanden mit passender Rolle durch', async () => {
    const result = runGuard(authState(['ROLE_USER', 'ROLE_DIRECTOR']));

    await expect(firstValueFrom(result as never)).resolves.toBe(true);
  });

  it('weist ohne passende Rolle ab und sagt auch warum', async () => {
    // Eine wortlose Umleitung sähe aus, als hätte der Klick nicht funktioniert.
    const result = await firstValueFrom(runGuard(authState(['ROLE_USER'])) as never);

    expect(result).toBeInstanceOf(UrlTree);
    expect(String(result)).toBe('/dashboard');
    expect(toastService.error).toHaveBeenCalled();
  });

  it('führt einen nicht angemeldeten Nutzer zur Startseite, nicht zur Rechteprüfung', async () => {
    const result = await firstValueFrom(runGuard(authState(null)) as never);

    expect(String(result)).toBe('/home');
    expect(localStorage.getItem('redirectAfterLogin')).toBe('/groups/rights');
    expect(toastService.error).not.toHaveBeenCalled();
  });
});
