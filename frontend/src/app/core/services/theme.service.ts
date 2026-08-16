import { Injectable, signal } from '@angular/core';

/**
 * Die Wahl des Nutzers.
 *
 * `system` ist kein eigenes Thema, sondern der Verzicht auf eine Festlegung:
 * dann entscheidet die Einstellung des Betriebssystems.
 */
export type ThemeChoice = 'system' | 'dim' | 'dark' | 'ma';

/** Muss mit dem Inline-Skript in index.html übereinstimmen. */
export const THEME_STORAGE_KEY = 'theme';

const CHOICES: readonly ThemeChoice[] = ['system', 'dim', 'dark', 'ma'];

/**
 * Frühere Fassungen boten ein echtes helles Thema an. Wer es gewählt hatte,
 * landet auf der gedämpften Variante statt auf einem Wert, den es nicht mehr
 * gibt - sonst stünde er nach dem Update wieder auf "wie das System".
 */
const RETIRED_CHOICES: Readonly<Record<string, ThemeChoice>> = { light: 'dim' };

@Injectable({ providedIn: 'root' })
export class ThemeService {
  /** Die aktuelle Wahl - für die Anzeige im Menü. */
  readonly choice = signal<ThemeChoice>('system');

  constructor() {
    this.choice.set(this.stored());
    this.apply(this.choice());
  }

  set(choice: ThemeChoice) {
    this.choice.set(choice);
    this.apply(choice);
    this.remember(choice);
  }

  /**
   * Setzt das Attribut, das `styles.scss` auswertet.
   *
   * Bei `system` wird es entfernt, nicht auf einen Wert gesetzt: die
   * Stylesheet-Regel für den hellen Systemmodus greift genau dann, wenn kein
   * `data-theme="dark"` gesetzt ist. Ein eigener Wert würde sie aushebeln.
   */
  private apply(choice: ThemeChoice) {
    const root = document.documentElement;
    if (choice === 'system') {
      root.removeAttribute('data-theme');
    } else {
      root.setAttribute('data-theme', choice);
    }
  }

  /**
   * Die gemerkte Wahl.
   *
   * Der Zugriff ist abgesichert: im privaten Modus mancher Browser wirft schon
   * das Lesen des Speichers, und daran soll die Anwendung nicht scheitern.
   */
  private stored(): ThemeChoice {
    try {
      const value = localStorage.getItem(THEME_STORAGE_KEY) ?? '';
      if (CHOICES.includes(value as ThemeChoice)) return value as ThemeChoice;
      return RETIRED_CHOICES[value] ?? 'system';
    } catch {
      return 'system';
    }
  }

  private remember(choice: ThemeChoice) {
    try {
      localStorage.setItem(THEME_STORAGE_KEY, choice);
    } catch {
      // Ohne Speicher gilt die Wahl nur für diese Sitzung - das ist immer
      // noch besser, als die Umschaltung ganz zu verweigern.
    }
  }
}
