import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import {
  AltSignalDto,
  AltSuggestionDto,
  CharacterService,
} from '../../services/character.service';
import { ConfirmService } from '../../services/confirm.service';
import { ToastService } from '../../services/toast.service';

/**
 * Ab wann eine Zahl in der Anzeige als "hoch" gilt.
 *
 * <p>Der Server liefert ohnehin nur ab 80. Diese Grenze faerbt lediglich, sie
 * filtert nicht - die Schwelle selbst steht im Server
 * (`AltDetectionTuning.MIN_PROBABILITY`) und darf hier nicht ein zweites Mal
 * entschieden werden.</p>
 */
const HOHE_WAHRSCHEINLICHKEIT = 90;

/**
 * Was in der Spalte "Wahrscheinlichkeit" unter der Zahl steht.
 *
 * <p>Freie Funktion und keine Methode: so ist der Satz ohne Komponente
 * pruefbar, und er ist der eigentliche Ertrag der Spalte. Eine Zahl allein
 * verleitet zum Durchklicken - ein Director soll lesen, worauf sie beruht,
 * bevor er einen Knopf drueckt, dessen Folge er nicht zuruecknehmen kann.</p>
 *
 * <p>Der Ein-Signal-Fall ist der wichtige: 85 aus einem gleichen Nachnamen und
 * 85 aus drei uebereinstimmenden Signalen sehen in der Tabelle gleich aus und
 * sind es nicht. Ohne diesen Satz waere die Spalte irrefuehrend.</p>
 */
export function begruendungSatz(suggestion: AltSuggestionDto): string {
  const tragend = suggestion.signals.filter((signal) => signal.available);
  if (tragend.length <= 1) {
    const name = tragend[0]?.label ?? 'kein einziges Signal';
    return `Traegt nur ${name} - eine hohe Zahl aus einer einzigen Quelle ist ein Verdacht, kein Nachweis.`;
  }
  return `${suggestion.signalsUsed} von ${suggestion.signalsTotal} Signalen tragen: ${tragend
    .map((signal) => signal.label)
    .join(', ')}.`;
}

/**
 * Die Beschriftung eines Chips.
 *
 * <p>"nicht gemessen" statt einer 0: der Unterschied ist der ganze Punkt von
 * `AltSignalDto.available`. Ein Charakter ohne Mining-Zeilen hat keine
 * gepruefte Unaehnlichkeit, er hat gar keine Pruefung.</p>
 */
export function chipText(signal: AltSignalDto): string {
  return signal.available ? `${signal.label} ${signal.score}` : `${signal.label}: nicht gemessen`;
}

/**
 * Die Vorschlaege fuer die Alt-Erkennung.
 *
 * <p>Zu den Klassen: `.surface-panel` ist die einzige der drei gewuenschten
 * Klassen, die wirklich global in `styles.scss` steht. Die verlangte
 * `.role-table` ist in `roles.component.scss` definiert und damit
 * komponenten-lokal - durch Angulars View-Encapsulation griffe sie hier nicht,
 * die Tabelle waere unformatiert. Benutzt wird deshalb `.eve-table`, der im
 * Projekt ueberwiegende Name (Academy, Asset-Audit, Groups-Board, Mining,
 * My-Assets), und die Regeln stehen wie dort lokal in der eigenen `.scss` -
 * woertlich aus `academy.component.scss` uebernommen, nicht neu erfunden.
 * Dasselbe gilt fuer `.hinweis` und `.btn-primary`.</p>
 */
@Component({
  selector: 'app-alt-suggestions',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './alt-suggestions.component.html',
  styleUrls: ['./alt-suggestions.component.scss'],
})
export class AltSuggestionsComponent implements OnInit {
  private characterService = inject(CharacterService);
  private confirmService = inject(ConfirmService);
  private toastService = inject(ToastService);

  readonly hoheWahrscheinlichkeit = HOHE_WAHRSCHEINLICHKEIT;

  suggestions = signal<AltSuggestionDto[]>([]);
  loading = signal<boolean>(true);

  /**
   * Der Charakter, dessen Bestaetigung gerade laeuft - `null` heisst: keine.
   *
   * <p>Eine ID statt eines Ja/Nein: sperrte ein Kennzeichen alle Knoepfe, saehe
   * der Nutzer nicht, welche Zeile gerade arbeitet. Dasselbe Muster wie
   * `pendingRole` in der Rollenverwaltung.</p>
   */
  pending = signal<number | null>(null);

  /** Ob ueberhaupt etwas anzuzeigen ist - trennt Leerzustand von Ladezustand. */
  isEmpty = computed(() => !this.loading() && this.suggestions().length === 0);

  ngOnInit() {
    this.load();
  }

  /**
   * Holt die Vorschlaege.
   *
   * <p>Ein Fehler leert die Liste. Sonst stuende nach einem fehlgeschlagenen
   * Neuladen der alte Stand da, und der naechste Klick bestaetigte einen
   * Vorschlag, den es vielleicht gar nicht mehr gibt.</p>
   */
  private load() {
    this.loading.set(true);
    this.characterService.getAltSuggestions().subscribe({
      next: (suggestions) => {
        this.suggestions.set(suggestions);
        this.loading.set(false);
      },
      error: (error: unknown) => {
        this.suggestions.set([]);
        this.loading.set(false);
        this.toastService.error(
          this.messageOf(error, 'Die Alt-Vorschlaege konnten nicht geladen werden.'),
        );
      },
    });
  }

  begruendung(suggestion: AltSuggestionDto): string {
    return begruendungSatz(suggestion);
  }

  chipBeschriftung(signal: AltSignalDto): string {
    return chipText(signal);
  }

  isPending(suggestion: AltSuggestionDto): boolean {
    return this.pending() === suggestion.unauthedCharId;
  }

  /**
   * Der Text der Rueckfrage.
   *
   * <p>Er nennt beide Namen und sagt, was der Klick wirklich tut. Der Server
   * schreibt <b>nicht</b> nach `characters.main_character_id`, sondern legt eine
   * Vormerkung an, die er nie stillschweigend ersetzt - ueber diese Oberflaeche
   * gibt es also keinen Weg zurueck. Eine Rueckfrage, die das verschweigt, ist
   * eine Formalitaet und keine Sicherung: sie liesse den Director glauben, der
   * Charakter haenge danach am Konto, und ein Irrtum bliebe unbemerkt im
   * Nachweis stehen.</p>
   */
  rueckfrageText(suggestion: AltSuggestionDto): string {
    return (
      `Moechtest du ${suggestion.unauthedCharName} wirklich ${suggestion.mainName} zuordnen? ` +
      `${suggestion.probability} % aus ${suggestion.signalsUsed} von ${suggestion.signalsTotal} Signalen. ` +
      `Der Klick merkt die Zuordnung nur vor: ${suggestion.unauthedCharName} haengt danach noch NICHT ` +
      `an dem Konto - das geschieht erst, wenn der Charakter sich selbst per EVE-Login als Alt anmeldet. ` +
      `Die Vormerkung selbst laesst sich hier nicht zuruecknehmen und steht mit deinem Namen im Nachweis.`
    );
  }

  /**
   * Bestaetigt den Vorschlag - nach Rueckfrage.
   *
   * <p>Gemeldet wird die `message` des Servers und nicht ein eigener Erfolgssatz:
   * nur der Server weiss, ob tatsaechlich zugeordnet oder nur vorgemerkt wurde,
   * und er sagt es im Klartext samt naechstem Schritt.</p>
   */
  async verknuepfen(suggestion: AltSuggestionDto) {
    if (this.pending()) {
      return;
    }

    const confirmed = await this.confirmService.ask(
      'Alt verknuepfen?',
      this.rueckfrageText(suggestion),
      'Verknuepfen',
      'Abbrechen',
    );
    if (!confirmed) {
      return;
    }

    this.pending.set(suggestion.unauthedCharId);
    this.characterService
      .confirmAltSuggestion(suggestion.unauthedCharId, suggestion.mainId)
      .subscribe({
        next: (result) => {
          this.pending.set(null);
          this.toastService.success(result.message);
          // Neu laden statt die Zeile zu entfernen: der Server rechnet die
          // Vorschlaege bei jedem Aufruf neu, und mit dem bestaetigten Paar
          // faellt oft auch ein konkurrierender Vorschlag desselben Charakters weg.
          this.load();
        },
        error: (error: unknown) => {
          this.pending.set(null);
          this.toastService.error(
            this.messageOf(error, 'Der Vorschlag konnte nicht bestaetigt werden.'),
          );
        },
      });
  }

  /**
   * Die Begruendung des Servers, sonst der Ersatztext.
   *
   * <p>Hier steht das Entscheidende - etwa dass der Charakter laengst
   * registriert ist oder schon jemand anderes ihn vorgemerkt hat. Ein pauschales
   * "hat nicht geklappt" liesse den Nutzer vor einer Schaltflaeche zurueck, die
   * scheinbar grundlos nichts tut.</p>
   */
  private messageOf(error: unknown, fallback: string): string {
    const body = (error as HttpErrorResponse | null)?.error as { message?: string } | null;
    const message = typeof body?.message === 'string' ? body.message.trim() : '';
    return message || fallback;
  }
}
