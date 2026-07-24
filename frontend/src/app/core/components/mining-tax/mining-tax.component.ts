import { Component, OnInit, inject, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  MiningService,
  MiningTaxRate,
  LedgerItemDto,
  UserLedgerResponse
} from '../../services/mining.service';
import { AuthService } from '../../services/auth.service';
import { ToastService } from '../../services/toast.service';
import { ConfirmService } from '../../services/confirm.service';

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

  activeTab = signal<'USER' | 'ADMIN'>('USER');

  myLedgerData = signal<UserLedgerResponse | null>(null);

  myLedger = computed(() => this.myLedgerData()?.months || []);

  loadingLedger = signal(true);

  selectedMonthIndex = signal<number>(0);

  currentMonthLedger = computed(() => {
    const ledgers = this.myLedger();
    if (ledgers.length === 0) return null;
    return ledgers[this.selectedMonthIndex()];
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

  setTab(tab: 'USER' | 'ADMIN') {
    this.activeTab.set(tab);
    if (tab === 'ADMIN') this.loadTaxRates();
    else this.loadUserLedger();
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

  // --- REST BLEIBT GLEICH ---
  loadTaxRates() {
    this.loadingTaxes.set(true);
    this.miningService.getTaxRates().subscribe({
      next: (data) => { this.taxRates.set(data); this.loadingTaxes.set(false); },
      error: () => this.loadingTaxes.set(false)
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

  formatIsk(value: number | undefined): string {
    if (value === undefined || value === null) return '0 ISK';
    return value.toLocaleString('de-DE', { minimumFractionDigits: 0, maximumFractionDigits: 0 }) + ' ISK';
  }

  formatVolume(value: number | undefined): string {
    if (value === undefined || value === null) return '0 m³';
    return value.toLocaleString('de-DE', { minimumFractionDigits: 0, maximumFractionDigits: 0 }) + ' m³';
  }
}
