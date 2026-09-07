import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

/**
 * Die FAT-Statistik, so wie der Server sie liefert.
 *
 * <p>Die Datensätze sind die wörtliche Entsprechung von
 * `FleetStatisticsDtos` - und die eine Eigenschaft, die dabei zählt, ist eine
 * Auslassung: Es gibt hier <b>keinen einzigen Prozentwert</b>. Jede Quote
 * kommt als {@link Anteil} mit Zähler und Nenner an, und dieser Dienst rechnet
 * daraus auch keinen aus. Wer im Frontend eine Prozentzahl anzeigen wollte,
 * müsste sie selbst dividieren - und stünde dann vor der Frage, warum "100 %"
 * bei einer einzigen Flotte danebensteht. Genau diese Frage soll gar nicht
 * erst aufkommen: Angezeigt wird "7 von 9".</p>
 *
 * <p>Die Sätze für dünne Datenlagen (`FatStatistik.hinweis`,
 * `Zeitraum.hinweis`) kommen fertig vom Server und werden hier nicht
 * zusammengesetzt. Sonst stünde dieselbe Aussage an zwei Stellen und driftete
 * auseinander.</p>
 */

/** Eine Quote, die ihren Nenner mitbringt. Bewusst ohne Prozentfeld. */
export interface Anteil {
  zaehler: number;
  nenner: number;
}

export interface Zeitraum {
  tageGewaehlt: number;
  tageAusgewertet: number;
  von: string;
  bis: string;
  ersteFlotteInsgesamt: string | null;
  tageMitDaten: number | null;
  gekuerzt: boolean;
  hinweis: string | null;
}

export interface DoktrinAngabe {
  gereiht: boolean;
  haeufigste: string | null;
  anteil: Anteil;
  ohneAngabe: number;
  hinweis: string | null;
}

export interface Kopfzeile {
  flotten: number;
  /**
   * Verschiedene Accounts - der Nenner der Tafel darunter.
   *
   * <p>Zusammen mit `charaktere` und nicht statt dessen: Eine Zahl, die ihre
   * Einheit wechselt, ohne dass die Beschriftung mitgeht, ist eine stille
   * Falschaussage. Der Abstand zwischen beiden ist außerdem die einzige
   * Stelle, an der sichtbar wird, wie stark hier multiboxt wird.</p>
   */
  accounts: number;
  /** Verschiedene Charaktere derselben Teilnahmen. */
  charaktere: number;
  fcs: number;
  /**
   * LIVE-Flotten von allen. Der Vorbehalt zur Tafel darunter: Bei einer
   * LINK-Flotte steht nur drin, wer den Link geklickt hat.
   */
  liveAnteil: Anteil;
  doktrin: DoktrinAngabe;
}

/**
 * Eine Zeile der Teilnahmetafel - <b>ein Account</b>, nicht ein Charakter.
 *
 * <p>Wer drei Alts in dieselbe Flotte bringt, war einmal dabei. Sonst misst
 * die Tafel Multiboxing statt Beteiligung.</p>
 *
 * <p>Bewusst ohne Quote: Ein Pilot, der vor drei Wochen beigetreten ist, kann
 * keine 40 Flotten haben; jeder Nenner wäre entweder unfair (die Gesamtzahl
 * des Fensters) oder zirkulär (ab seiner ersten Flotte). Absolute Zahl plus
 * erste und letzte Flotte - dann sieht der Leser selbst, ob "4" wenig ist.</p>
 */
export interface AccountZeile {
  accountId: number;
  /** Der Name des Mains - er muss selbst nie mitgeflogen sein. */
  name: string | null;
  /** Wer aus diesem Account tatsächlich geflogen ist, nach Namen sortiert. */
  charaktere: string[];
  flotten: number;
  ersteFlotte: string | null;
  letzteFlotte: string | null;
  /** Ob das Auth zu diesem Account überhaupt eine Verknüpfung kennt. */
  verbunden: boolean;
}

export interface Teilnahme {
  zeilen: AccountZeile[];
  /** Accounts mit genau einer Flotte - getrennt, damit sie die Tabelle nicht füllen. */
  einmalige: AccountZeile[];
  /**
   * Accounts, die das Auth als einzelnen Charakter kennt, von allen.
   *
   * <p>Die Zahl, ohne die diese Seite genauer aussieht, als sie ist: Die
   * Gruppierung ist nur so gut wie die CharLink-Daten. Ein Alt, der nicht mit
   * seinem Main verbunden ist, bekommt weiterhin eine eigene Zeile.</p>
   */
  ohneVerbindung: Anteil;
  /** Der fertige Satz dazu - vom Server, damit er nicht zweimal existiert. */
  hinweis: string | null;
}

export interface FatStatistik {
  zeitraum: Zeitraum;
  kopf: Kopfzeile;
  auswertbar: boolean;
  hinweis: string | null;
  teilnahme: Teilnahme;
}

/**
 * Die wählbaren Zeiträume - dieselben drei wie im Server.
 *
 * <p>Kein "seit Anbeginn": Die Zahl würde mit jedem Monat träger (ein Pilot,
 * der vor zwei Jahren 40 Flotten flog, stünde ewig oben) und die Abfrage
 * wüchse unbegrenzt. Wer die Gesamthistorie will, will einen Export.</p>
 *
 * <p>Der Server weist einen anderen Wert ab, statt ihn still durch 90 zu
 * ersetzen - deshalb steht die Liste hier und wird nicht frei getippt.</p>
 */
export const ZEITRAEUME = [30, 90, 180] as const;

export type ZeitraumTage = (typeof ZEITRAEUME)[number];

export const ZEITRAUM_VORGABE: ZeitraumTage = 90;

@Injectable({ providedIn: 'root' })
export class FleetStatisticsService {
  private http = inject(HttpClient);
  private apiUrl = `${environment.apiUrl}/fleets`;

  /**
   * Holt die ganze Seite in einem Zug.
   *
   * <p>Ein Aufruf und nicht einer je Abschnitt: Kopfzeile und Tafel beziehen
   * sich auf dieselbe Flottenzahl. Getrennt geladen stünde in der Kopfzeile 40
   * und in der Tafel darunter 41, sobald zwischendurch eine Flotte geschlossen
   * wird - und die Seite verlöre genau die Glaubwürdigkeit, um die es hier
   * geht.</p>
   */
  statistik(tage: ZeitraumTage): Observable<FatStatistik> {
    return this.http.get<FatStatistik>(`${this.apiUrl}/statistics`, {
      params: new HttpParams().set('tage', tage),
    });
  }
}
