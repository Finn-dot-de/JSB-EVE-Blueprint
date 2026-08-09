import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { THEME_STORAGE_KEY, ThemeService } from './theme.service';

describe('ThemeService', () => {
  let storage: Map<string, string>;

  function createService(): ThemeService {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [ThemeService] });
    return TestBed.inject(ThemeService);
  }

  beforeEach(() => {
    storage = new Map<string, string>();
    vi.stubGlobal('localStorage', {
      getItem: (key: string) => storage.get(key) ?? null,
      setItem: (key: string, value: string) => storage.set(key, value),
      removeItem: (key: string) => storage.delete(key),
      clear: () => storage.clear(),
    });
    document.documentElement.removeAttribute('data-theme');
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    document.documentElement.removeAttribute('data-theme');
  });

  it('folgt ohne gemerkte Wahl dem System', () => {
    const service = createService();

    expect(service.choice()).toBe('system');
    // Kein Attribut heisst: die Systemeinstellung entscheidet.
    expect(document.documentElement.hasAttribute('data-theme')).toBe(false);
  });

  it('setzt das Attribut, das die Stylesheets auswerten', () => {
    const service = createService();

    service.set('dark');

    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
    expect(service.choice()).toBe('dark');
  });

  it('entfernt das Attribut bei der Rückkehr zum System', () => {
    // Ein eigener Wert würde die Regel für den hellen Systemmodus aushebeln -
    // die greift genau dann, wenn kein data-theme="dark" gesetzt ist.
    const service = createService();
    service.set('dim');

    service.set('system');

    expect(document.documentElement.hasAttribute('data-theme')).toBe(false);
  });

  it('merkt sich die Wahl über einen Neustart hinweg', () => {
    createService().set('dim');

    expect(storage.get(THEME_STORAGE_KEY)).toBe('dim');
    expect(createService().choice()).toBe('dim');
  });

  it('übernimmt eine früher gewählte helle Einstellung als gedämpft', () => {
    // Das helle Thema gibt es nicht mehr. Wer es gewählt hatte, soll nicht
    // stillschweigend auf "wie das System" zurückfallen.
    storage.set(THEME_STORAGE_KEY, 'light');

    const service = createService();

    expect(service.choice()).toBe('dim');
    expect(document.documentElement.getAttribute('data-theme')).toBe('dim');
  });

  it('kennt das Thema der Korporation', () => {
    const service = createService();

    service.set('ma');

    expect(document.documentElement.getAttribute('data-theme')).toBe('ma');
    expect(storage.get(THEME_STORAGE_KEY)).toBe('ma');
    expect(createService().choice()).toBe('ma');
  });

  it('übergeht einen unbrauchbaren gemerkten Wert', () => {
    storage.set(THEME_STORAGE_KEY, 'neongrün');

    expect(createService().choice()).toBe('system');
  });

  it('läuft weiter, wenn der Speicher gar nicht zugänglich ist', () => {
    // Im privaten Modus mancher Browser wirft schon das Lesen.
    vi.stubGlobal('localStorage', {
      getItem: () => {
        throw new Error('verweigert');
      },
      setItem: () => {
        throw new Error('verweigert');
      },
    });

    const service = createService();
    expect(service.choice()).toBe('system');

    service.set('dark');

    // Die Wahl gilt für diese Sitzung, auch ohne Speicher.
    expect(service.choice()).toBe('dark');
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
  });
});
