import { Component, OnInit, WritableSignal, inject, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  MiningService,
  MiningTaxRate,
  AppliedCreditDto,
  LedgerItemDto,
  MonthlyLedgerDto,
  UserLedgerResponse, AdminLedgerSummaryDto, AdminMemberLedgerDto, TaxCreditDto,
  MiningLeaderboardDto
} from '../../services/mining.service';
import { AuthService } from '../../services/auth.service';
import { ToastService } from '../../services/toast.service';
import { ConfirmService } from '../../services/confirm.service';
import { formatIsk, formatIskCents, formatMonthLabel, formatVolume, formatVolumeCompact } from '../../shared/eve-format.util';
import { handlePortraitError, typeIcon } from '../../shared/eve-image.util';

@Component({
  selector: 'app-mining-tax',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './mining-tax.component.html',
  styleUrls: ['./mining-tax.component.scss']
})
export class MiningTaxComponent implements OnInit {
  private miningService = inject(MiningService);
  public authService = inject(AuthService);
  private toastService = inject(ToastService);
  private confirmService = inject(ConfirmService);

  // Formatierung und Bildadressen kommen aus den gemeinsamen Utilities.
  protected readonly formatIsk = formatIsk;
  /**
   * Jeder Betrag dieser Seite geht durch {@link formatIskCents}, keiner durch
   * `formatIskFull`. Steuer, Zahlung, Gutschrift, Saldo und der Preis, mit dem
   * gerechnet wurde, liegen im Server als `BigDecimal` und in der Datenbank als
   * `numeric(20,2)` - hier auf ganze ISK zu runden hiesse, die Genauigkeit genau
   * an der Stelle wegzuwerfen, an der sie erarbeitet wurde. `formatIskFull`
   * bleibt den geschätzten Besitzwerten vorbehalten, die aus `double`-Preisen
   * stammen.
   */
  protected readonly formatIskCents = formatIskCents;
  protected readonly formatVolume = formatVolume;
  protected readonly formatVolumeCompact = formatVolumeCompact;
  protected readonly formatMonthLabel = formatMonthLabel;
  protected readonly typeIcon = typeIcon;
  protected readonly onPortraitError = handlePortraitError;

  adminLedgers = signal<AdminLedgerSummaryDto[]>([]);
  loadingAdminLedgers = signal(false);

  // --- Steuerakte eines einzelnen Members (der Klick auf eine Bilanzzeile) ---
  selectedMember = signal<AdminMemberLedgerDto | null>(null);
  loadingMember = signal(false);
  /** Welcher Monat der Akte gerade offen ist - wie in der Eigensicht 0 = neuester. */
  memberMonthIndex = signal(0);

  memberMonth = computed(() => {
    const months = this.selectedMember()?.months ?? [];
    return months[this.memberMonthIndex()] ?? null;
  });

  /**
   * Der eingetippte Betrag - als Zeichenkette, nicht als Zahl.
   *
   * <p>Ein `type="number"` gäbe hier ein `double` zurück, und damit wäre der
   * Betrag ungenau, bevor er überhaupt losgeschickt wird. Die Eingabe wandert
   * unverändert zum Server, der sie als einziger liest.</p>
   */
  creditAmount = '';
  creditReason = '';
  /** Solange die Buchung läuft, bleibt der Knopf zu - eine Gutschrift zweimal zu senden wäre teuer. */
  grantingCredit = signal(false);

  activeTab = signal<'USER' | 'ADMIN' | 'LEDGERS'>('USER');

  myLedgerData = signal<UserLedgerResponse | null>(null);
  myLedger = computed(() => this.myLedgerData()?.months || []);
  loadingLedger = signal(true);
  selectedMonthIndex = signal<number>(0);

  currentMonthLedger = computed(() => {
    const ledgers = this.myLedger();
    if (ledgers.length === 0) return null;
    return ledgers[this.selectedMonthIndex()];
  });

  // --- Rangliste (aufklappbar) ---
  showLeaderboard = signal(false);
  leaderboard = signal<MiningLeaderboardDto | null>(null);
  loadingLeaderboard = signal(false);
  /** Nach welcher Größe die Balken skalieren. */
  leaderMetric = signal<'VOLUME' | 'VALUE'>('VOLUME');
  selectedLeaderMonth: string | null = null;

  /** Bezugsgröße für die Balkenbreite: der Spitzenreiter ist immer 100 %. */
  leaderMax = computed(() => {
    const rows = this.leaderboard()?.rows ?? [];
    if (rows.length === 0) return 0;
    return Math.max(...rows.map(r => this.leaderValue(r.volume, r.value)));
  });

  leaderTotal = computed(() => {
    const lb = this.leaderboard();
    if (!lb) return 0;
    return this.leaderMetric() === 'VOLUME' ? lb.totalVolume : lb.totalValue;
  });

  // Admin State
  taxRates = signal<MiningTaxRate[]>([]);
  loadingTaxes = signal(false);

  // Single Insert (Dropdown)
  selectedTypeId: number | null = null;
  newTaxPercentage: number = 0;

  // Bulk Form
  bulkCategory = 'ORE';
  bulkTaxPercentage = 0;

  get isLeadership(): boolean {
    return this.authService.hasAnyRole(['ROLE_CEO', 'ROLE_DIRECTOR', 'ROLE_IT_ADMIN']);
  }

  ngOnInit() { this.loadUserLedger(); }

  setTab(tab: 'USER' | 'ADMIN' | 'LEDGERS') {
    this.activeTab.set(tab);
    if (tab === 'ADMIN') {
      this.loadTaxRates();
    } else if (tab === 'LEDGERS') {
      this.loadAdminLedgers();
    } else {
      this.loadUserLedger();
    }
  }

  loadUserLedger() {
    this.loadingLedger.set(true);
    this.miningService.getMyLedger().subscribe({
      next: (data) => {
        this.myLedgerData.set(data);
        this.selectedMonthIndex.set(0);
        this.loadingLedger.set(false);
      },
      error: () => this.loadingLedger.set(false)
    });
  }

  // --- Navigation durch die Monate ---

  /**
   * Blättert einen Monat weiter, ohne über die Enden hinauszulaufen.
   *
   * <p>Eine Stelle für beide Ansichten: die Eigensicht und die Steuerakte eines
   * Members zeigen dieselbe Monatsliste, und ein Blätterfehler, der nur in einer
   * von beiden behoben wäre, fiele in der anderen erst jemandem auf, der schon
   * eine falsche Abrechnung vor sich hat.</p>
   */
  private stepMonth(index: WritableSignal<number>, count: number, delta: number) {
    const next = index() + delta;
    if (next >= 0 && next < count) {
      index.set(next);
    }
  }

  olderMonth() { this.stepMonth(this.selectedMonthIndex, this.myLedger().length, 1); }

  newerMonth() { this.stepMonth(this.selectedMonthIndex, this.myLedger().length, -1); }

  olderMemberMonth() {
    this.stepMonth(this.memberMonthIndex, this.selectedMember()?.months.length ?? 0, 1);
  }

  newerMemberMonth() {
    this.stepMonth(this.memberMonthIndex, this.selectedMember()?.months.length ?? 0, -1);
  }

  getTotalVolume(details: LedgerItemDto[]): number {
    return details.reduce((sum, item) => sum + item.volume, 0);
  }

  /**
   * Ob dieser Monat wirklich eine Überweisung verlangt.
   *
   * <p>Früher stand hier `totalTax - taxPaid`. Diese Differenz kennt die
   * Gutschriften nicht, und deshalb forderte der Bildschirm 28,9 Mio. ein,
   * während derselbe Bildschirm eine Zeile höher 461 Mio. Guthaben auswies.
   * Wer im Plus steht, soll nicht zur Kasse gebeten werden.</p>
   *
   * <p>Der Server verteilt die Gutschriften chronologisch über die offenen
   * Monate und legt das Ergebnis als `amountDue` bei. Hier wird davon
   * <b>nichts</b> nachgerechnet - nur gefragt, ob etwas übrig bleibt. Eine
   * zweite Rechnung liefe früher oder später neben der ersten her, und dann
   * forderte die Oberfläche Geld ein, das der Server längst als gedeckt
   * ansieht.</p>
   */
  needsTransfer(monthLedger: MonthlyLedgerDto): boolean {
    return monthLedger.amountDue > 0;
  }

  /**
   * Ob dieser Monat einen Nachtrag enthält - also einen Teil, der nicht aus
   * einer erkannten Überweisung stammt, sondern aus einer von Hand gebuchten
   * Gutschrift.
   *
   * <p>Am Status ändert das nichts mehr: ein so gedeckter Monat ist bezahlt.
   * Sichtbar bleiben muss trotzdem, <i>woher</i> die Deckung kommt - sonst
   * liesse sich später nicht mehr sagen, ob wirklich Geld geflossen ist oder
   * ob jemand den Monat per Eintrag geschlossen hat. Ohne diese Abfrage
   * stünde bei jedem gewöhnlichen Monat ein "Nachgetragen: 0,00 ISK", das
   * nichts erklärt und nur Platz nimmt.</p>
   */
  hasBackfill(monthLedger: MonthlyLedgerDto): boolean {
    return monthLedger.creditApplied > 0;
  }

  /**
   * Ob von dieser Buchung nur ein Teil in den gezeigten Monat geflossen ist.
   *
   * <p>Keine Rechnung, sondern ein Vergleich zweier fertiger Zahlen des
   * Servers: `applied` ist der Anteil dieses Monats, `amount` die ganze
   * Buchung. Sind sie gleich, wäre der Zusatz "Teil einer Gutschrift über ..."
   * nur eine Wiederholung desselben Betrags; sind sie verschieden, ist er die
   * Antwort auf die Frage, warum dieselbe Gutschrift in zwei Monaten
   * auftaucht.</p>
   */
  isPartialCredit(credit: AppliedCreditDto): boolean {
    return credit.applied < credit.amount;
  }

  // --- Rangliste ---

  toggleLeaderboard() {
    this.showLeaderboard.update(v => !v);
    // Erst beim Aufklappen laden - die Rangliste soll die Seite nicht ausbremsen.
    if (this.showLeaderboard() && !this.leaderboard()) {
      this.loadLeaderboard();
    }
  }

  loadLeaderboard() {
    this.loadingLeaderboard.set(true);
    this.miningService.getLeaderboard(this.selectedLeaderMonth).subscribe({
      next: (data) => {
        this.leaderboard.set(data);
        // Beim ersten Laden übernimmt das Backend die Monatswahl (neuester Monat).
        this.selectedLeaderMonth = data.month;
        this.loadingLeaderboard.set(false);
      },
      error: () => {
        this.loadingLeaderboard.set(false);
        this.toastService.error('Die Mining-Rangliste konnte nicht geladen werden.');
      }
    });
  }

  onLeaderMonthChange() {
    this.loadLeaderboard();
  }

  setLeaderMetric(metric: 'VOLUME' | 'VALUE') {
    this.leaderMetric.set(metric);
  }

  /** Liefert je nach aktiver Metrik den Volumen- oder den ISK-Wert. */
  leaderValue(volume: number, value: number): number {
    return this.leaderMetric() === 'VOLUME' ? volume : value;
  }

  leaderBarWidth(volume: number, value: number): string {
    const max = this.leaderMax();
    if (!max || max <= 0) return '0%';
    return Math.max(1.5, (this.leaderValue(volume, value) / max) * 100).toFixed(1) + '%';
  }

  leaderShare(volume: number, value: number): string {
    const total = this.leaderTotal();
    if (!total || total <= 0) return '0 %';
    return ((this.leaderValue(volume, value) / total) * 100).toFixed(1) + ' %';
  }

  /** Formatiert den Wert der gerade aktiven Metrik. */
  formatLeaderValue(volume: number, value: number): string {
    return this.leaderMetric() === 'VOLUME'
      ? this.formatVolume(volume)
      : this.formatIsk(value);
  }

  // --- ADMIN LADEN ---
  loadTaxRates() {
    this.loadingTaxes.set(true);
    this.miningService.getTaxRates().subscribe({
      next: (data) => { this.taxRates.set(data); this.loadingTaxes.set(false); },
      error: () => this.loadingTaxes.set(false)
    });
  }

  loadAdminLedgers() {
    this.loadingAdminLedgers.set(true);
    this.miningService.getAdminLedgers().subscribe({
      next: (data) => { this.adminLedgers.set(data); this.loadingAdminLedgers.set(false); },
      error: () => this.loadingAdminLedgers.set(false)
    });
  }

  // ==================================================================
  // Steuerakte eines Members
  // ==================================================================

  /** Der Klick auf eine Zeile der Bilanz: Erze, Zusammensetzung und Gutschriften. */
  openMember(accountId: number) {
    this.loadingMember.set(true);
    // Das Eingabefeld gehört zum vorherigen Member. Bliebe ein Betrag darin
    // stehen, stünde er beim nächsten Klick vor einem anderen Namen - und die
    // Rückfrage nennt zwar den richtigen Namen, aber der Finger ist schneller.
    this.creditAmount = '';
    this.creditReason = '';
    this.miningService.getMemberLedger(accountId).subscribe({
      next: (data) => {
        this.selectedMember.set(data);
        this.memberMonthIndex.set(0);
        this.loadingMember.set(false);
      },
      error: (err) => {
        this.loadingMember.set(false);
        this.toastService.error(err.error?.message || 'Die Steuerakte konnte nicht geladen werden.');
      }
    });
  }

  closeMember() {
    this.selectedMember.set(null);
  }

  /** Ob diese Zeile gerade geöffnet ist - für die Hervorhebung in der Bilanz. */
  isSelectedMember(mainId: number): boolean {
    return this.selectedMember()?.accountId === mainId;
  }

  getTotalTaxOfMonth(details: LedgerItemDto[]): number {
    return details.reduce((sum, item) => sum + item.taxToPay, 0);
  }

  /**
   * Anteil eines Erzes an der Steuer des Monats - die "Zusammensetzung".
   *
   * <p>Bezugsgröße ist die Summe der aufgeführten Erze und nicht
   * `monthLedger.totalTax`: bei einem eingefrorenen Monat kann die gespeicherte
   * Gesamtsumme aus einer älteren Rechnung stammen, und dann ergäben die Anteile
   * in der Spalte nicht mehr 100 Prozent.</p>
   */
  taxShare(item: LedgerItemDto, details: LedgerItemDto[]): string {
    const total = this.getTotalTaxOfMonth(details);
    if (total <= 0) return '0 %';
    return ((item.taxToPay / total) * 100).toFixed(1) + ' %';
  }

  // ==================================================================
  // Gutschriften
  // ==================================================================

  /**
   * Vergibt eine Gutschrift - nach einer Rückfrage, die Betrag und Namen nennt.
   *
   * <p>Der Betrag in der Rückfrage ist <b>wortgleich der eingetippte</b> und
   * nicht etwa ein hier formatierter. Ihn für die Anzeige zu deuten hiesse, die
   * Regeln des Servers ein zweites Mal aufzuschreiben; wären sich die beiden
   * Lesarten je uneinig, bestätigte der Nutzer einen Betrag und der Server
   * buchte einen anderen. Die einzige Prüfung hier ist "überhaupt etwas
   * eingegeben" - alles Weitere entscheidet der Server und meldet es zurück.</p>
   */
  async grantCredit() {
    const member = this.selectedMember();
    const amount = this.creditAmount.trim();
    if (!member || !amount) {
      this.toastService.error('Ohne Betrag gibt es keine Gutschrift.');
      return;
    }

    const confirmed = await this.confirmService.ask(
      'Gutschrift vergeben?',
      `${member.accountName} bekommt ${amount} ISK gutgeschrieben. `
      + 'Die Buchung bleibt mit deinem Namen dauerhaft im Verlauf stehen; '
      + 'zurücknehmen lässt sie sich nur durch eine sichtbare Gegenbuchung.',
      'Gutschreiben'
    );
    if (!confirmed) {
      return;
    }

    this.grantingCredit.set(true);
    this.miningService.grantCredit(member.accountId, amount, this.creditReason.trim() || null).subscribe({
      next: () => {
        this.toastService.success(`${amount} ISK für ${member.accountName} gutgeschrieben.`);
        this.creditAmount = '';
        this.creditReason = '';
        this.grantingCredit.set(false);
        this.refreshAfterBooking(member.accountId);
      },
      error: (err) => {
        this.grantingCredit.set(false);
        // Die Meldung des Servers und nicht ein pauschales "hat nicht geklappt":
        // dort steht, WARUM der Betrag abgelehnt wurde - etwa dass "12.500"
        // mehrdeutig ist. Ohne diesen Text tippt der Nutzer dasselbe noch einmal.
        this.toastService.error(err.error?.message || 'Die Gutschrift konnte nicht gebucht werden.');
      }
    });
  }

  /**
   * Nimmt eine Gutschrift zurück - erst nach Rückfrage mit Betrag und Namen.
   *
   * <p>Der Grund kommt aus demselben Feld wie bei der Vergabe. Damit ein dort
   * stehengebliebener Text nicht unbemerkt an einer Rücknahme klebt, nennt ihn
   * die Rückfrage wörtlich - und sagt ausdrücklich, wenn keiner festgehalten
   * wird.</p>
   */
  async reverseCredit(credit: TaxCreditDto) {
    const reason = this.creditReason.trim();
    const confirmed = await this.confirmService.ask(
      'Gutschrift zurücknehmen?',
      `Die Gutschrift über ${this.formatIskCents(credit.amount)} an ${credit.accountName} wird `
      + 'durch eine Gegenbuchung aufgehoben. Beide Zeilen bleiben im Verlauf sichtbar. '
      + (reason ? `Als Grund wird festgehalten: "${reason}".` : 'Es wird kein Grund festgehalten.'),
      'Zurücknehmen'
    );
    if (!confirmed) {
      return;
    }

    this.miningService.reverseCredit(credit.id, reason || null).subscribe({
      next: () => {
        this.toastService.info('Gutschrift zurückgenommen.');
        this.creditReason = '';
        this.refreshAfterBooking(credit.accountId);
      },
      error: (err) => this.toastService.error(
        err.error?.message || 'Die Gutschrift konnte nicht zurückgenommen werden.')
    });
  }

  /**
   * Lädt Akte und Bilanz nach einer Buchung neu.
   *
   * <p>Beide, nicht nur die Akte: die Bilanzzeile dahinter trägt dieselbe Summe.
   * Bliebe sie stehen, zeigten zwei Zahlen auf demselben Bildschirm ein
   * unterschiedliches Guthaben für denselben Account.</p>
   */
  private refreshAfterBooking(accountId: number) {
    this.openMember(accountId);
    this.loadAdminLedgers();
  }

  /** Nur eine gültige Gutschrift lässt sich zurücknehmen - eine Gegenbuchung nicht. */
  isReversible(credit: TaxCreditDto): boolean {
    return credit.status === 'ACTIVE';
  }

  creditStatusLabel(status: string): string {
    if (status === 'REVERSED') return 'zurückgenommen';
    if (status === 'REVERSAL') return 'Gegenbuchung';
    return 'gültig';
  }

  saveTaxRateFromDropdown() {
    if (!this.selectedTypeId) return;
    const existing = this.taxRates().find(r => r.typeId === Number(this.selectedTypeId));
    if (!existing) return;

    existing.taxPercentage = this.newTaxPercentage;

    this.miningService.saveTaxRate(existing).subscribe({
      next: () => {
        this.toastService.success(`Steuersatz für ${existing.typeName} gespeichert!`);
        this.selectedTypeId = null;
        this.newTaxPercentage = 0;
        this.loadTaxRates();
      },
      error: () => this.toastService.error('Fehler beim Speichern.')
    });
  }

  saveBulkTax() {
    if (this.bulkTaxPercentage < 0) return;
    this.miningService.saveBulkTax(this.bulkCategory, this.bulkTaxPercentage).subscribe({
      next: () => {
        this.toastService.success(`Alle Steuersätze für ${this.bulkCategory} erfolgreich auf ${this.bulkTaxPercentage}% gesetzt!`);
        this.bulkTaxPercentage = 0;
        this.loadTaxRates();
      },
      error: () => this.toastService.error('Fehler beim Massen-Update.')
    });
  }

  async deleteTaxRate(typeId: number) {
    const confirmed = await this.confirmService.ask(
      'Steuersatz löschen?',
      'Soll dieser Steuersatz wirklich entfernt werden? Bisheriges Mining wird dann bei den Usern mit 0 ISK berechnet.',
      'Löschen',
      'Abbrechen'
    );
    if (confirmed) {
      this.miningService.deleteTaxRate(typeId).subscribe({
        next: () => { this.toastService.info('Steuersatz gelöscht.'); this.loadTaxRates(); },
        error: () => this.toastService.error('Fehler beim Löschen.')
      });
    }
  }

}
