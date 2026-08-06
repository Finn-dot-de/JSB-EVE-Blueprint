import { DestroyRef, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Observable, Subject, of } from 'rxjs';
import { catchError, debounceTime, distinctUntilChanged, map, switchMap } from 'rxjs/operators';

/**
 * Eine Anfrage, von der immer nur die jüngste zählt.
 *
 * <p>Löst drei Probleme, die bei Suchfeldern und Filtern regelmäßig auftreten:</p>
 *
 * <ol>
 *   <li><b>Überholende Antworten.</b> Eine breite, langsame Abfrage darf das
 *       Ergebnis einer engeren, schnelleren nicht überschreiben. `switchMap`
 *       meldet die vorherige Anfrage ab - Angulars HttpClient bricht sie damit
 *       tatsächlich ab, statt ihr Ergebnis nur zu verwerfen.</li>
 *   <li><b>Ein Fehler beendet den Stream.</b> Läuft die Fehlerbehandlung im
 *       äußeren `subscribe`, ist die Verbindung nach dem ersten Fehlschlag
 *       endgültig tot - jede weitere Eingabe bliebe stumm. Deshalb wird der
 *       Fehler <em>innerhalb</em> der inneren Anfrage abgefangen.</li>
 *   <li><b>Offene Abos.</b> `takeUntilDestroyed` beendet alles, sobald die
 *       Komponente verschwindet.</li>
 * </ol>
 *
 * <p>Muss im Injektionskontext angelegt werden, also als Feld oder im
 * Konstruktor einer Komponente.</p>
 */
export interface LatestRequestOptions<TInput, TResult> {
  /** Baut die eigentliche Anfrage. Wird bei jedem Auslösen neu aufgerufen. */
  run: (input: TInput) => Observable<TResult>;

  /** Verarbeitet das Ergebnis der jüngsten Anfrage. */
  next: (result: TResult) => void;

  /** Reagiert auf einen Fehlschlag. Der Auslöser bleibt danach benutzbar. */
  error?: (error: unknown) => void;

  /** Wartezeit vor dem Absenden - für Eingabefelder, die pro Tastendruck auslösen. */
  debounceMs?: number;

  /** Überspringt Auslöser mit unverändertem Eingabewert. */
  distinct?: boolean;
}

/** Ergebnis eines Durchlaufs; der Fehlerfall bleibt Teil des Werts statt den Stream zu beenden. */
type Outcome<TResult> =
  | { readonly ok: true; readonly value: TResult }
  | { readonly ok: false; readonly error: unknown };

/**
 * Legt den Auslöser an.
 *
 * @return eine Funktion, die eine neue Anfrage startet und eine noch laufende abbricht
 */
export function latestRequest<TInput, TResult>(
  options: LatestRequestOptions<TInput, TResult>,
): (input: TInput) => void {
  const destroyRef = inject(DestroyRef);
  const trigger = new Subject<TInput>();

  let stream: Observable<TInput> = trigger;
  if (options.debounceMs !== undefined) {
    stream = stream.pipe(debounceTime(options.debounceMs));
  }
  if (options.distinct) {
    stream = stream.pipe(distinctUntilChanged());
  }

  stream
    .pipe(
      switchMap((input) =>
        options.run(input).pipe(
          map((value): Outcome<TResult> => ({ ok: true, value })),
          catchError((error: unknown) => of<Outcome<TResult>>({ ok: false, error })),
        ),
      ),
      takeUntilDestroyed(destroyRef),
    )
    .subscribe((outcome) => {
      if (outcome.ok) {
        options.next(outcome.value);
      } else {
        options.error?.(outcome.error);
      }
    });

  return (input: TInput) => trigger.next(input);
}
