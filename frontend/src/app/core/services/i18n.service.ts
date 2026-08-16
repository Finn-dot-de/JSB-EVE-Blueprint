import { Injectable, computed, signal } from '@angular/core';
import { Lang, TRANSLATIONS } from '../i18n/translations';

const STORAGE_KEY = 'buybot.lang';

/**
 * Laufzeit-Übersetzung statt Build-Zeit-i18n: es gibt nur ein Deployment,
 * die Sprache lässt sich per Knopf umschalten und über ?lang=en direkt
 * verlinken (praktisch für den beworbenen Link im Discord).
 */
@Injectable({ providedIn: 'root' })
export class I18nService {
  readonly lang = signal<Lang>(this.detectInitialLang());
  readonly isGerman = computed(() => this.lang() === 'de');

  t(key: string, ...args: (string | number)[]): string {
    const dict = TRANSLATIONS[this.lang()];
    let value = dict[key] ?? TRANSLATIONS.de[key] ?? key;
    args.forEach((arg, index) => {
      value = value.replace(`{${index}}`, String(arg));
    });
    return value;
  }

  setLang(lang: Lang) {
    this.lang.set(lang);
    try {
      localStorage.setItem(STORAGE_KEY, lang);
    } catch {
    }
    document.documentElement.lang = lang;
  }

  toggle() {
    this.setLang(this.lang() === 'de' ? 'en' : 'de');
  }

  /** Zahlenformat passend zur Sprache (1.234,56 vs 1,234.56). */
  get locale(): string {
    return this.lang() === 'de' ? 'de-DE' : 'en-US';
  }

  private detectInitialLang(): Lang {
    // 1. Direkter Link: ?lang=en
    try {
      const fromUrl = new URLSearchParams(window.location.search).get('lang');
      if (fromUrl === 'en' || fromUrl === 'de') {
        localStorage.setItem(STORAGE_KEY, fromUrl);
        document.documentElement.lang = fromUrl;
        return fromUrl;
      }
    } catch {
      // window/URLSearchParams nicht verfügbar (SSR/Tests)
    }

    // 2. Zuletzt gewählte Sprache
    try {
      const stored = localStorage.getItem(STORAGE_KEY);
      if (stored === 'en' || stored === 'de') {
        document.documentElement.lang = stored;
        return stored;
      }
    } catch {
      // ignorieren
    }

    // 3. Deutsch ist die Hauptsprache
    return 'de';
  }
}
