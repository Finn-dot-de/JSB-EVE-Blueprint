import { TestBed } from '@angular/core/testing';
import { Route } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AppComponent } from './app.component';
import { appConfig } from './app.config';
import { routes } from './app.routes';
import { authGuard } from './core/auth/auth.guard';
import { AuthService } from './core/services/auth.service';

describe('AppComponent', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [{ provide: AuthService, useValue: { loading: () => false } }],
    });
  });

  it('stellt den Anmeldedienst für die Rahmen-Ansicht bereit', () => {
    const component = TestBed.runInInjectionContext(() => new AppComponent());

    expect(component.authService).toBeTruthy();
  });
});

describe('Routen', () => {
  /** Alle Routen mit Ziel - die Weiterleitung von '' hat keines. */
  const targets = routes.filter((route: Route) => !route.redirectTo);

  /** Die einzige Seite hinter einem Rollen-Wächter statt nur der Anmeldung. */
  const RIGHTS_PATH = 'groups/rights';

  it('leitet die Wurzel auf die Startseite um', () => {
    const root = routes.find((route: Route) => route.path === '');

    expect(root?.redirectTo).toBe('home');
    expect(root?.pathMatch).toBe('full');
  });

  it('lässt nur die Startseite ohne Anmeldung zu', () => {
    // Jede andere Seite zeigt Corp-Daten und gehört hinter die Anmeldung.
    const unguarded = targets.filter((route: Route) => !route.canActivate);

    expect(unguarded.map((route: Route) => route.path)).toEqual(['home']);
  });

  it('schützt jede übrige Route mit dem Anmelde-Wächter', () => {
    const guarded = targets.filter((route: Route) => route.canActivate);

    expect(guarded.length).toBeGreaterThan(5);
    expect(
      guarded
        .filter((route: Route) => route.path !== RIGHTS_PATH)
        .every((route: Route) => route.canActivate?.[0] === authGuard),
    ).toBe(true);
  });

  it('verlangt für die Rechteverwaltung mehr als nur eine Anmeldung', () => {
    // Der Server lässt dort ohnehin nur die Führung durch - ohne eigenen
    // Wächter fände sich jedes Mitglied auf einer Seite voller Fehler wieder.
    const rights = targets.find((route: Route) => route.path === RIGHTS_PATH);

    expect(rights?.canActivate).toHaveLength(1);
    expect(rights?.canActivate?.[0]).not.toBe(authGuard);
  });

  it('lädt jede Seite erst bei Bedarf nach', () => {
    expect(targets.every((route: Route) => typeof route.loadComponent === 'function')).toBe(true);
  });

  it('vergibt jeden Pfad nur einmal', () => {
    const paths = routes.map((route: Route) => route.path);

    expect(new Set(paths).size).toBe(paths.length);
  });

  it('lädt die Komponente hinter jedem Pfad tatsächlich', async () => {
    const loaded = await Promise.all(
      targets.map((route: Route) => (route.loadComponent as () => Promise<unknown>)()),
    );

    expect(loaded.every((component) => typeof component === 'function')).toBe(true);
  });
});

describe('Anwendungs-Konfiguration', () => {
  it('bringt Router, HTTP-Zugriff und die Fehlerbehandlung mit', () => {
    expect(appConfig.providers).toHaveLength(3);
  });
});
