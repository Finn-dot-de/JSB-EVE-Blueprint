import { Component, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../../../environments/environment';

/** Ein Charakter, dessen Anmeldung bei EVE abgelaufen ist. */
export interface TokenHealth {
  characterId: number;
  name: string;
  /** ISO-Zeitstempel des ERSTEN Fehlschlags, nicht des letzten. */
  invalidSince: string | null;
  reason: string | null;
}

/**
 * Sagt dem Spieler, welcher seiner Charaktere sich neu anmelden muss.
 *
 * <p>Der Anlass ist ein Zustand, den bisher niemand sehen konnte. Läuft der
 * Refresh-Token eines Charakters ab, holt EVE für ihn keine Daten mehr — seine
 * Assets, Skills und Industriejobs veralten stillschweigend. Im Serverlog stand
 * das, im Auth nirgends. Wer es nicht selbst bemerkte, arbeitete wochenlang mit
 * Zahlen von gestern.</p>
 *
 * <p>Bewusst im Rahmen der Anwendung und nicht auf einer Unterseite: der
 * Betroffene sucht nicht danach, er muss darüber stolpern.</p>
 */
@Component({
  selector: 'app-token-health-banner',
  standalone: true,
  imports: [CommonModule],
  template: `
    @if (betroffene().length) {
      <div class="token-banner" role="status">
        <i class="fa-solid fa-triangle-exclamation" aria-hidden="true"></i>
        <div class="token-text">
          <strong>
            @if (betroffene().length === 1) {
              {{ betroffene()[0].name }} ist bei EVE abgemeldet.
            } @else {
              {{ betroffene().length }} deiner Charaktere sind bei EVE abgemeldet.
            }
          </strong>
          <span>
            <!-- Ohne diesen Satz wirkt es wie eine Formalie. Der Punkt ist:
                 die Daten veralten ab jetzt, ohne dass es weiter auffällt. -->
            Für {{ betroffene().length === 1 ? 'ihn' : 'sie' }} werden keine Daten
            mehr geholt – Bestände, Skills und Industriejobs bleiben auf dem
            letzten Stand stehen.
            @for (c of betroffene(); track c.characterId) {
              <span class="token-char">
                {{ c.name }}@if (c.invalidSince) { <span class="seit">seit {{ seit(c.invalidSince) }}</span> }
              </span>
            }
          </span>
        </div>
        <button type="button" class="token-knopf" (click)="anmelden()">
          Neu anmelden
        </button>
        <button type="button" class="token-schliessen" (click)="verbergen.set(true)"
                aria-label="Hinweis ausblenden">×</button>
      </div>
    }
  `,
  styleUrls: ['./token-health-banner.component.scss'],
})
export class TokenHealthBannerComponent {
  private http = inject(HttpClient);

  private readonly rows = signal<TokenHealth[]>([]);
  readonly verbergen = signal(false);

  readonly betroffene = computed(() => (this.verbergen() ? [] : this.rows()));

  constructor() {
    // Ein Fehlschlag hier bleibt still: wer nicht angemeldet ist, bekommt eine
    // 401, und ein Banner über abgelaufene Anmeldungen wäre auf dem
    // Anmeldebildschirm bestenfalls verwirrend.
    this.http
      .get<TokenHealth[]>(`${environment.apiUrl}/characters/token-health`)
      .subscribe({
        next: (rows) => this.rows.set(rows ?? []),
        error: () => this.rows.set([]),
      });
  }

  /**
   * Wie lange das schon so geht.
   *
   * Grob und in Worten: „seit 3 Tagen" sagt mehr als ein Zeitstempel, und die
   * Zahl entscheidet, ob man es heute noch erledigt oder nächste Woche.
   */
  seit(iso: string): string {
    const start = Date.parse(iso);
    if (Number.isNaN(start)) return 'unbekannt';

    const stunden = Math.floor((Date.now() - start) / 3_600_000);
    if (stunden < 1) return 'gerade eben';
    if (stunden < 24) return `${stunden} h`;

    const tage = Math.floor(stunden / 24);
    return tage === 1 ? 'einem Tag' : `${tage} Tagen`;
  }

  anmelden() {
    window.location.href = `${environment.apiUrl}/auth/login`;
  }
}
