import { Component, OnInit, inject, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  MiningService,
  MiningTaxRate,
  LedgerItemDto,
  UserLedgerResponse, AdminLedgerSummaryDto,
  MiningLeaderboardDto
} from '../../services/mining.service';
import { AuthService } from '../../services/auth.service';
import { ToastService } from '../../services/toast.service';
import { ConfirmService } from '../../services/confirm.service';
import { formatIsk, formatIskFull, formatMonthLabel, formatVolume, formatVolumeCompact } from '../../shared/eve-format.util';
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
  protected readonly formatIskFull = formatIskFull;
  protected readonly formatVolume = formatVolume;
  protected readonly formatVolumeCompact = formatVolumeCompact;
  protected readonly formatMonthLabel = formatMonthLabel;
  protected readonly typeIcon = typeIcon;
  protected readonly onPortraitError = handlePortraitError;

  adminLedgers = signal<AdminLedgerSummaryDto[]>([]);
  loadingAdminLedgers = signal(false);

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

  // --- NEU: Navigation durch die Monate ---
  olderMonth() {
    if (this.selectedMonthIndex() < this.myLedger().length - 1) {
      this.selectedMonthIndex.update(i => i + 1);
    }
  }

  newerMonth() {
    if (this.selectedMonthIndex() > 0) {
      this.selectedMonthIndex.update(i => i - 1);
    }
  }

  getTotalVolume(details: LedgerItemDto[]): number {
    return details.reduce((sum, item) => sum + item.volume, 0);
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
