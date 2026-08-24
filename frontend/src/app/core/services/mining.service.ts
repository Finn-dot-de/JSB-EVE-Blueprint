import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

/**
 * Zu den ISK-Beträgen in dieser Datei.
 *
 * <p>Sie stehen als `number`, obwohl der Server sie inzwischen durchgängig als
 * `BigDecimal` führt und die Datenbank als `numeric(20,2)`. Das ist kein
 * Rückschritt, sondern die Form, in der JSON sie transportiert: eine Zahl.
 * Angezeigt werden sie mit `formatIskCents`, also mit beiden Nachkommastellen -
 * bis 10^12 ISK trägt ein `double` jeden Cent, sein Fehler liegt dort bei rund
 * 0,0002 ISK.</p>
 *
 * <p>In die andere Richtung gilt das nicht. Ein Betrag, den der Browser als
 * Zahl verpackt, ist ungenau, bevor er losgeschickt wird; deshalb geht
 * {@link MiningService.grantCredit} als Zeichenkette hinaus. Wer diese
 * Schnittstelle einmal "vereinheitlicht", macht daraus wieder einen `double`
 * und das exakte `numeric(20,2)` dahinter nutzlos.</p>
 *
 * <p>Nicht jede Zahl hier ist Geld: `volume` ist m³ und die Rangliste eine
 * Reihenfolge - beide sind auch im Server `double`.</p>
 */
export interface LedgerItemDto {
  typeId: number;
  typeName: string;
  category: string;
  quantity: number;
  volume: number;
  /** Der Preis, mit dem der Server gerechnet hat - exakt, nicht der Tagespreis. */
  jitaPrice: number;
  taxToPay: number;
}

export interface UserLedgerResponse {
  totalDebt: number;
  totalPaid: number;
  totalCredited: number;
  currentBalance: number;
  months: MonthlyLedgerDto[];
}

/**
 * Der Anteil einer Gutschrift an einem einzelnen Monat.
 *
 * <p>Sie ist der Nachweis hinter einem nachgetragenen Monat: `applied` ist der
 * Anteil, den *dieser* Monat aus der Buchung bekommen hat, `amount` die volle
 * Buchung. Beide stehen dabei, weil eine Gutschrift über mehrere Monate reicht
 * - stünde nur `amount` da, sähe derselbe Betrag in zwei Monaten wie zwei
 * Nachträge aus.</p>
 */
export interface AppliedCreditDto {
  creditId: number;
  applied: number;
  amount: number;
  actorCharacterId: number;
  actorName: string;
  reason: string | null;
  occurredAt: string;
}

export interface MonthlyLedgerDto {
  month: string;
  totalTax: number;
  /** Der Anteil, der aus einer *erkannten* Überweisung gedeckt ist. */
  taxPaid: number;
  /**
   * Ob der Monat als beglichen gilt - gerechnet aus Überweisungen *und*
   * nachgetragenen Gutschriften.
   *
   * <p>Eine Gutschrift ist hier eine Korrektur und keine Zuwendung: sie trägt
   * eine Zahlung nach, die stattgefunden hat, die das Werkzeug aber nicht
   * erkannt hat. Einen so gedeckten Monat weiter als offen zu führen hiesse,
   * an einer Schuld festzuhalten, die jemand ausdrücklich für beglichen
   * erklärt hat.</p>
   */
  isPaid: boolean;
  /**
   * Der Anteil, der aus *nachgetragenen* Gutschriften gedeckt ist - getrennt
   * von `taxPaid` und nicht darin eingerechnet.
   *
   * <p>Das ist die einzige Stelle, an der die Herkunft der Deckung noch
   * sichtbar ist. Ohne sie liesse sich später nicht mehr sagen, ob für einen
   * Monat wirklich Geld geflossen ist oder ob ihn jemand per Eintrag
   * geschlossen hat.</p>
   */
  creditApplied: number;
  /**
   * Welche Buchungen diesen Monat nachgetragen haben, älteste zuerst - leer,
   * wenn er allein aus Überweisungen bezahlt ist. Damit steht der Unterschied
   * nicht nur als Betrag da, sondern mit Beleg: wer, wann, warum.
   */
  appliedCredits: AppliedCreditDto[];
  /**
   * Was nach Zahlungen *und* Gutschriften tatsächlich noch zu überweisen ist.
   * Die einzige Zahl, die diese Oberfläche zur Handlung auffordern darf: der
   * Server verteilt die Gutschriften chronologisch über die Monate, und diese
   * Verteilung hier nachzubilden hiesse, dieselbe Rechnung ein zweites Mal
   * aufzuschreiben.
   */
  amountDue: number;
  details: LedgerItemDto[];
}

export interface MiningTaxRate {
  typeId: number;
  typeName: string;
  category: string;
  taxPercentage: number;
  currentJitaBuy: number;
}

/** Eine Zeile der Mining-Rangliste (ein Account: Main + Alts zusammengefasst). */
export interface MiningLeaderRowDto {
  rank: number;
  mainId: number;
  mainName: string;
  portraitUrl: string;
  volume: number;   // m³
  value: number;    // ISK (Jita Buy)
  units: number;    // abgebaute Einheiten
  isMe: boolean;
}

export interface MiningLeaderboardDto {
  month: string;              // "YYYY-MM" oder "ALL"
  availableMonths: string[];
  totalVolume: number;
  totalValue: number;
  rows: MiningLeaderRowDto[];
}

export interface AdminLedgerSummaryDto {
  mainId: number;
  mainName: string;
  portraitUrl: string;
  totalTax: number;
  totalPaid: number;
  totalCredited: number;
  currentBalance: number;
}

/**
 * Eine Buchung aus dem Gutschriftenverlauf.
 *
 * <p>Zu `amount` siehe den Hinweis zu den Beträgen oben in dieser Datei; der
 * Server begrenzt jede einzelne Buchung zusätzlich auf 10^12 ISK und hält sie
 * damit ausdrücklich in dem Bereich, in dem die Anzeige gefahrlos ist.</p>
 */
export interface TaxCreditDto {
  id: number;
  accountId: number;
  accountName: string;
  portraitUrl: string;
  amount: number;
  /** ACTIVE = gültig, REVERSED = zurückgenommen, REVERSAL = die Gegenbuchung dazu. */
  status: string;
  reversalOfCreditId: number | null;
  actorCharacterId: number;
  actorName: string;
  selfGranted: boolean;
  reason: string | null;
  occurredAt: string;
}

/** Die Steuerakte eines Members, wie sie die Führung sieht - inkl. Erzen je Monat. */
export interface AdminMemberLedgerDto {
  accountId: number;
  accountName: string;
  portraitUrl: string;
  totalTax: number;
  totalPaid: number;
  totalCredited: number;
  currentBalance: number;
  months: MonthlyLedgerDto[];
  credits: TaxCreditDto[];
}

@Injectable({
  providedIn: 'root'
})
export class MiningService {
  private http = inject(HttpClient);
  private apiUrl = `${environment.apiUrl}/mining`;

  getMyLedger(): Observable<UserLedgerResponse> {
    return this.http.get<UserLedgerResponse>(`${this.apiUrl}/my-ledger`);
  }

  getTaxRates(): Observable<MiningTaxRate[]> {
    return this.http.get<MiningTaxRate[]>(`${this.apiUrl}/taxes`);
  }

  saveTaxRate(rate: MiningTaxRate): Observable<MiningTaxRate> {
    return this.http.post<MiningTaxRate>(`${this.apiUrl}/taxes`, rate);
  }

  deleteTaxRate(typeId: number): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/taxes/${typeId}`);
  }

  // FIX: Sendet jetzt taxPercentage
  saveBulkTax(category: string, taxPercentage: number): Observable<void> {
    return this.http.post<void>(`${this.apiUrl}/taxes/bulk?category=${category}&taxPercentage=${taxPercentage}`, {});
  }

  getAdminLedgers(): Observable<AdminLedgerSummaryDto[]> {
    return this.http.get<AdminLedgerSummaryDto[]>(`${this.apiUrl}/admin/ledgers`);
  }

  /**
   * Die Steuerakte eines Members - der Klick auf eine Zeile der Bilanz.
   *
   * <p>`accountId` ist die `mainId` aus {@link AdminLedgerSummaryDto}: die Steuer
   * wird über den Account geführt, nicht über den einzelnen Charakter.</p>
   */
  getMemberLedger(accountId: number): Observable<AdminMemberLedgerDto> {
    return this.http.get<AdminMemberLedgerDto>(`${this.apiUrl}/admin/ledgers/${accountId}`);
  }

  /**
   * Schreibt einem Member einen Betrag gut.
   *
   * <p>`amount` ist eine <b>Zeichenkette</b> und wird hier bewusst nicht in eine
   * Zahl verwandelt. Ein `number` wäre ein `double`, und der Betrag wäre schon
   * ungenau, bevor er die Leitung erreicht - das exakte `numeric(20,2)` der
   * Datenbank nützte dann nichts mehr. Was jemand eingetippt hat, geht
   * unverändert hinaus; gelesen wird es genau einmal, nämlich auf dem Server.</p>
   */
  grantCredit(accountId: number, amount: string, reason: string | null): Observable<TaxCreditDto> {
    return this.http.post<TaxCreditDto>(
      `${this.apiUrl}/admin/credits/accounts/${accountId}`, { amount, reason });
  }

  /**
   * Nimmt eine Gutschrift zurück.
   *
   * <p>POST und nicht DELETE, weil nichts gelöscht wird: es entsteht eine
   * Gegenbuchung, die ursprüngliche Zeile bleibt im Verlauf stehen.</p>
   */
  reverseCredit(creditId: number, reason: string | null): Observable<TaxCreditDto> {
    return this.http.post<TaxCreditDto>(
      `${this.apiUrl}/admin/credits/${creditId}/reverse`, { reason });
  }

  /** Rangliste "wer hat am meisten abgebaut". month: "YYYY-MM" oder "ALL". */
  getLeaderboard(month?: string | null): Observable<MiningLeaderboardDto> {
    let params = new HttpParams();
    if (month) params = params.set('month', month);
    return this.http.get<MiningLeaderboardDto>(`${this.apiUrl}/leaderboard`, { params });
  }

}
