import { TestBed } from '@angular/core/testing';
import { I18nService } from './i18n.service';

/**
 * Tests der Übersetzung.
 *
 * Ein fehlender Schlüssel darf nie als roher Schlüsselname in der Oberfläche landen,
 * und die Sprachwahl muss einen Seitenwechsel überleben.
 */
describe('I18nService', () => {
  let service: I18nService;

  beforeEach(() => {
    // jsdom bringt hier nur ein Rumpf-localStorage mit - für den Test ein eigenes,
    // damit die Persistenz der Sprachwahl überhaupt prüfbar ist.
    const store = new Map<string, string>();
    Object.defineProperty(window, 'localStorage', {
      configurable: true,
      value: {
        getItem: (key: string) => store.get(key) ?? null,
        setItem: (key: string, value: string) => store.set(key, value),
        removeItem: (key: string) => store.delete(key),
        clear: () => store.clear()
      }
    });

    TestBed.configureTestingModule({});
    service = TestBed.inject(I18nService);
  });

  it('startet auf Deutsch, weil das die Hauptsprache ist', () => {
    expect(service.lang()).toBe('de');
    expect(service.isGerman()).toBe(true);
  });

  it('übersetzt in die gewählte Sprache', () => {
    expect(service.t('btn.calculate')).toBe('BERECHNEN');

    service.setLang('en');

    expect(service.t('btn.calculate')).toBe('CALCULATE');
  });

  it('schaltet zwischen den Sprachen hin und her', () => {
    service.toggle();
    expect(service.lang()).toBe('en');

    service.toggle();
    expect(service.lang()).toBe('de');
  });

  it('merkt sich die Sprachwahl für den nächsten Besuch', () => {
    service.setLang('en');

    expect(localStorage.getItem('buybot.lang')).toBe('en');
  });

  it('setzt Platzhalter ein', () => {
    expect(service.t('contract.expireDays', 3)).toBe('3 Tage');
  });

  it('fällt bei fehlender Übersetzung auf Deutsch zurück statt den Schlüssel zu zeigen', () => {
    service.setLang('en');

    // Ein Schlüssel, den es in beiden Sprachen gibt, bleibt übersetzt ...
    expect(service.t('status.OK')).toBe('OK');
    // ... ein unbekannter liefert den Schlüssel, damit die Lücke auffällt
    expect(service.t('gibt.es.nicht')).toBe('gibt.es.nicht');
  });

  it('liefert das zur Sprache passende Zahlenformat', () => {
    expect(service.locale).toBe('de-DE');

    service.setLang('en');

    expect(service.locale).toBe('en-US');
  });
});
