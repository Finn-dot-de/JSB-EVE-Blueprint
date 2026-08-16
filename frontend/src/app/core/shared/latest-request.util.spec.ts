import { TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { latestRequest } from './latest-request.util';

describe('latestRequest', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({});
  });

  afterEach(() => vi.useRealTimers());

  /** Legt den Auslöser im Injektionskontext an, wie in einer Komponente. */
  function create<TInput, TResult>(options: Parameters<typeof latestRequest<TInput, TResult>>[0]) {
    return TestBed.runInInjectionContext(() => latestRequest(options));
  }

  describe('Nur die jüngste Anfrage zählt', () => {
    it('liefert das Ergebnis der jüngsten Anfrage', () => {
      const first = new Subject<string>();
      const second = new Subject<string>();
      const results: string[] = [];

      const request = create<number, string>({
        run: (nr) => (nr === 1 ? first : second),
        next: (value) => results.push(value),
      });

      request(1);
      request(2);
      second.next('neu');
      first.next('alt');

      expect(results).toEqual(['neu']);
    });

    it('bricht die vorherige Anfrage tatsächlich ab', () => {
      // Das ist der Unterschied zu einer laufenden Nummer: der HttpClient
      // beendet den Aufruf, statt ihn zu Ende laufen zu lassen.
      const first = new Subject<string>();
      const second = new Subject<string>();

      const request = create<number, string>({
        run: (nr) => (nr === 1 ? first : second),
        next: () => undefined,
      });

      request(1);
      expect(first.observed).toBe(true);

      request(2);
      expect(first.observed).toBe(false);
      expect(second.observed).toBe(true);
    });
  });

  describe('Ein Fehler beendet den Auslöser nicht', () => {
    it('meldet den Fehler und bleibt danach benutzbar', () => {
      // Läge die Fehlerbehandlung im äusseren subscribe, wäre der Auslöser
      // nach dem ersten Fehlschlag für immer tot.
      const results: string[] = [];
      const errors: unknown[] = [];
      let shouldFail = true;

      const request = create<void, string>({
        run: () => (shouldFail ? throwError(() => new Error('kaputt')) : of('geht wieder')),
        next: (value) => results.push(value),
        error: (error) => errors.push(error),
      });

      request();
      expect(errors).toHaveLength(1);
      expect(results).toEqual([]);

      shouldFail = false;
      request();

      expect(results).toEqual(['geht wieder']);
    });

    it('kommt ohne Fehlerbehandlung zurecht', () => {
      const request = create<void, string>({
        run: () => throwError(() => new Error('kaputt')),
        next: () => undefined,
      });

      expect(() => request()).not.toThrow();
    });
  });

  describe('Wartezeit und Dubletten', () => {
    it('sendet erst nach der Wartezeit', () => {
      const runs: string[] = [];

      const request = create<string, string>({
        debounceMs: 250,
        run: (term) => {
          runs.push(term);
          return of(term);
        },
        next: () => undefined,
      });

      request('n');
      request('ne');
      request('nes');
      expect(runs).toEqual([]);

      vi.advanceTimersByTime(250);

      expect(runs).toEqual(['nes']);
    });

    it('überspringt unveränderte Eingaben', () => {
      const runs: string[] = [];

      const request = create<string, string>({
        distinct: true,
        run: (term) => {
          runs.push(term);
          return of(term);
        },
        next: () => undefined,
      });

      request('nes');
      request('nes');
      request('nest');

      expect(runs).toEqual(['nes', 'nest']);
    });
  });

  it('beendet das Abo, sobald die Komponente verschwindet', () => {
    const source = new Subject<string>();

    const request = create<void, string>({
      run: () => source,
      next: () => undefined,
    });
    request();
    expect(source.observed).toBe(true);

    TestBed.resetTestingModule();

    expect(source.observed).toBe(false);
  });
});
