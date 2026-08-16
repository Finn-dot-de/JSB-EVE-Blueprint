import { Component, EventEmitter, OnDestroy, OnInit, Output, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DatePipe, DecimalPipe } from '@angular/common';
import {
  BuybotAdminService,
  AdminConfig,
  AdminLocation,
  AdminCategory,
  AdminType,
  LinkedCharacter,
  ContractCheckResult,
  ContractCheckStatus,
  AuditEntry,
  AuditCategory,
  AuditSeverity
} from '../../services/buybot-admin.service';
import { ToastService } from '../../services/toast.service';
import { ConfirmService } from '../../services/confirm.service';

/** Abschnitte des Panels, die sich zusammenklappen lassen. */
export type AdminSection = 'reports' | 'audit';

@Component({
  selector: 'app-buybot-admin',
  standalone: true,
  imports: [FormsModule, DecimalPipe, DatePipe],
  templateUrl: './buybot-admin.component.html',
  styleUrls: ['../buybot/buybot.component.scss'] // Erbt das CSS vom Terminal
})
export class BuybotAdminComponent implements OnInit, OnDestroy {
  @Output() closePanel = new EventEmitter<void>();
  /** Damit das Frontend Wartungsmodus und Texte sofort neu zieht. */
  @Output() configSaved = new EventEmitter<void>();

  private adminService = inject(BuybotAdminService);
  private toastService = inject(ToastService);
  private confirmService = inject(ConfirmService);

  // State
  config: AdminConfig = this.emptyConfig();

  locations: AdminLocation[] = [];
  categories: AdminCategory[] = [];
  types: AdminType[] = [];
  characters: LinkedCharacter[] = [];
  checkResults: ContractCheckResult[] = [];
  checkStatus: ContractCheckStatus | null = null;
  expandedResult: number | null = null;
  isRunningCheck = false;
  private statusTimer: any;

  /**
   * Zustand der aufklappbaren Abschnitte.
   *
   * Beide starten zugeklappt: es sind lange Listen, die mit der Zeit wachsen, und
   * aufgeklappt schiebt man alles darunter aus dem Bild.
   */
  sections: Record<AdminSection, boolean> = { reports: false, audit: false };

  // Protokoll
  auditEntries: AuditEntry[] = [];
  auditTotal = 0;
  auditCategory: AuditCategory | '' = '';
  auditSeverity: AuditSeverity | '' = '';
  expandedAudit: number | null = null;

  // Formular-Modelle für neue Einträge
  newLoc: AdminLocation = { name: '', transportFee: 0, securityFee: 0 };
  newCat = { name: '', modifier: 90, useReprocessedValue: false };
  newType = { name: '', modifier: 90, isBlacklisted: false, useReprocessedValue: false };

  ngOnInit() {
    this.loadAllData();
    // Solange das Panel offen ist, den Zustand der automatischen Prüfung mitlaufen lassen
    this.statusTimer = setInterval(() => this.loadCheckStatus(), 15000);
  }

  ngOnDestroy() {
    if (this.statusTimer) {
      clearInterval(this.statusTimer);
    }
  }

  private emptyConfig(): AdminConfig {
    return {
      priceBasis: 'buy',
      globalModifier: 90,
      volumeThreshold: 350000,
      valueThreshold: 1000000000,
      itemValueThreshold: 500000000,
      reprocessingRate: 50,
      botEnabled: true,
      maintenanceTitle: '',
      maintenanceMessage: '',
      contractRecipient: '',
      contractExpireDays: 3,
      contractDaysToComplete: 0,
      contractNote: '',
      contractCheckEnabled: false,
      contractCheckCharacterId: 0,
      priceTolerancePercent: 1,
      checkIntervalMinutes: 15,
      notifyTarget: 'NONE',
      discordWebhookUrl: '',
      notifyMailRecipientId: 0,
      notifyOnOk: true,
      botTexts: {
        idle: '', thinking: '', success: '', warnMissing: '', warnRejected: '', error: '', highVolume: '', highValue: '', expensiveItem: ''
      }
    };
  }

  /**
   * Altbestand: die neuen Spalten sind in der DB noch NULL. Defaults auffüllen und
   * "nicht gesetzt" auf 0 normalisieren, damit die Auswahlfelder etwas zum Binden haben
   * und das Backend die Zurücksetzung auch mitbekommt (null bedeutet dort "unverändert").
   */
  private normalize(c: AdminConfig): AdminConfig {
    const merged: AdminConfig = { ...this.emptyConfig(), ...c };
    merged.botEnabled = c.botEnabled ?? true;
    merged.contractCheckEnabled = c.contractCheckEnabled ?? false;
    merged.notifyOnOk = c.notifyOnOk ?? true;
    merged.contractCheckCharacterId = c.contractCheckCharacterId ?? 0;
    merged.notifyMailRecipientId = c.notifyMailRecipientId ?? 0;
    merged.reprocessingRate = c.reprocessingRate ?? 50;
    merged.botTexts = c.botTexts ?? this.emptyConfig().botTexts;
    return merged;
  }

  loadAllData() {
    this.adminService.getConfig().subscribe(c => this.config = this.normalize(c));
    this.adminService.getLocations().subscribe(l => this.locations = l);
    this.adminService.getCategories().subscribe(c => this.categories = c);
    this.adminService.getTypes().subscribe(t => this.types = t);
    this.adminService.getLinkedCharacters().subscribe({
      next: (chars) => this.characters = chars,
      error: () => this.characters = []
    });
    this.loadCheckResults();
    this.loadAuditEntries();
  }

  saveConfig(closeAfter = false) {
    this.adminService.updateConfig(this.config).subscribe({
      next: (saved) => {
        this.config = this.normalize(saved);
        this.toastService.success('Konfiguration erfolgreich gespeichert!');
        this.configSaved.emit();
        if (closeAfter) {
          this.close();
        }
      },
      error: (err) => this.toastService.error('Fehler beim Speichern: ' + err.message)
    });
  }

  addLocation() {
    if (!this.newLoc.name) return;
    this.adminService.addLocation(this.newLoc as any).subscribe(() => {
      this.newLoc = { name: '', transportFee: 0, securityFee: 0 };
      this.adminService.getLocations().subscribe(l => this.locations = l);
      this.toastService.success('Abgabeort hinzugefügt.');
    });
  }

  isSearching = false;

  searchStationId() {
    if (!this.newLoc.name) {
      this.toastService.info('Bitte gib zuerst den Namen des Ortes ein.');
      return;
    }

    this.isSearching = true;
    this.adminService.searchStationId(this.newLoc.name).subscribe({
      next: (id) => {
        this.newLoc.stationId = id;
        this.isSearching = false;
        this.toastService.success(`Station ID ${id} erfolgreich gefunden!`);
      },
      error: () => {
        this.isSearching = false;
        this.toastService.error('Station nicht gefunden. Existiert sie wirklich und hast du die Rechte dazu?');
      }
    });
  }

  async deleteLocation(id: number) {
    const confirmed = await this.confirmService.ask(
      'Ort löschen?',
      'Soll dieser Abgabeort wirklich gelöscht werden?',
      'LÖSCHEN',
      'ABBRECHEN'
    );

    if (confirmed) {
      this.adminService.deleteLocation(id).subscribe(() => {
        this.locations = this.locations.filter(l => l.id !== id);
        this.toastService.info('Abgabeort entfernt.');
      });
    }
  }

  addCategory() {
    if (!this.newCat.name) return;
    this.adminService.addCategory(this.newCat.name, this.newCat.modifier, this.newCat.useReprocessedValue).subscribe({
      next: () => {
        this.newCat.name = '';
        this.newCat.useReprocessedValue = false;
        this.adminService.getCategories().subscribe(c => this.categories = c);
        this.toastService.success('Kategorie zur Whitelist hinzugefügt.');
      },
      error: () => this.toastService.error('Kategorie in der EVE SDE nicht gefunden!')
    });
  }

  async deleteCategory(id: number) {
    const confirmed = await this.confirmService.ask(
      'Kategorie entfernen?',
      'Soll diese Kategorie wirklich aus der Whitelist entfernt werden?',
      'ENTFERNEN',
      'ABBRECHEN'
    );

    if (confirmed) {
      this.adminService.deleteCategory(id).subscribe(() => {
        this.categories = this.categories.filter(c => c.categoryId !== id);
        this.toastService.info('Kategorie entfernt.');
      });
    }
  }

  addType() {
    if (!this.newType.name) return;
    // Fuer die Rueckmeldung nach dem Speichern, das Formular wird gleich geleert
    const gespeichert = { name: this.newType.name.trim(), reprocess: this.newType.useReprocessedValue };

    this.adminService.addType(this.newType.name, this.newType.modifier, this.newType.isBlacklisted,
      this.newType.useReprocessedValue).subscribe({
      next: () => {
        this.newType.name = '';
        this.newType.isBlacklisted = false;
        this.newType.useReprocessedValue = false;
        this.adminService.getTypes().subscribe(t => {
          this.types = t;
          this.warnIfNotReprocessable(gespeichert.name, gespeichert.reprocess);
        });
        this.toastService.success('Item-Regel erfolgreich gespeichert.');
      },
      error: () => this.toastService.error('Exaktes Item in der EVE SDE nicht gefunden!')
    });
  }

  /**
   * Weist darauf hin, wenn das Reprocessing-Häkchen bei diesem Item nichts bewirkt.
   *
   * Mineralien und Mondgüter sind Endprodukte - sie haben keine Ausbeute, es bleibt
   * beim Marktpreis. Ohne diesen Hinweis sucht man den Fehler beim Preis.
   */
  private warnIfNotReprocessable(itemName: string, reprocessGewuenscht: boolean) {
    if (!reprocessGewuenscht) {
      return;
    }
    const eintrag = this.types.find(t => t.typeName?.toLowerCase() === itemName.toLowerCase());
    if (eintrag && eintrag.reprocessable === false) {
      this.toastService.info(
        `${eintrag.typeName} lässt sich nicht verwerten - das Häkchen bleibt ohne Wirkung, es gilt der Marktpreis.`);
    }
  }

  /**
   * Beschriftung der Reprocessed-Spalte.
   *
   * @param type die Regel
   * @return was in der Spalte steht
   */
  reprocessLabel(type: AdminType): string {
    if (!type.useReprocessedValue) {
      return '-';
    }
    return type.reprocessable === false ? 'JA (wirkungslos)' : 'JA';
  }

  /**
   * Farbe der Reprocessed-Spalte.
   *
   * @param type die Regel
   * @return die CSS-Klasse
   */
  reprocessClass(type: AdminType): string {
    if (!type.useReprocessedValue) {
      return '';
    }
    return type.reprocessable === false ? 'status-warn' : 'status-ok';
  }

  async deleteType(id: number) {
    const confirmed = await this.confirmService.ask(
      'Item-Regel löschen?',
      'Soll diese spezifische Item-Regel wirklich gelöscht werden?',
      'LÖSCHEN',
      'ABBRECHEN'
    );

    if (confirmed) {
      this.adminService.deleteType(id).subscribe(() => {
        this.types = this.types.filter(t => t.typeId !== id);
        this.toastService.info('Item-Regel entfernt.');
      });
    }
  }

  // ==========================================
  // VERTRAGSPRÜFUNG
  // ==========================================
  loadCheckResults() {
    this.adminService.getContractCheckResults(25).subscribe({
      next: (r) => this.checkResults = r,
      error: () => this.checkResults = []
    });
    this.loadCheckStatus();
  }

  loadCheckStatus() {
    this.adminService.getContractCheckStatus().subscribe({
      next: (s) => this.checkStatus = s,
      error: () => this.checkStatus = null
    });
  }

  runContractCheck() {
    this.isRunningCheck = true;
    this.adminService.runContractCheck().subscribe({
      next: (res) => {
        this.isRunningCheck = false;
        if (res.success) {
          this.toastService.success(res.message);
        } else {
          // Enthält jetzt den konkreten Grund, z.B. fehlender Mail-Scope
          this.toastService.error(res.message);
        }
        this.loadCheckResults();
      },
      error: (err) => {
        this.isRunningCheck = false;
        this.toastService.error('Prüflauf fehlgeschlagen: ' + (err?.error?.message || err.message));
      }
    });
  }

  isTestingNotification = false;

  testNotification() {
    this.isTestingNotification = true;
    this.adminService.testNotification().subscribe({
      next: (res) => {
        this.isTestingNotification = false;
        if (res.success) {
          this.toastService.success(res.message);
        } else {
          this.toastService.error(res.message);
        }
      },
      error: (err) => {
        this.isTestingNotification = false;
        this.toastService.error('Testnachricht fehlgeschlagen: ' + (err?.error?.message || err.message));
      }
    });
  }

  async forgetResult(contractId: number) {
    const confirmed = await this.confirmService.ask(
      'Vertrag erneut prüfen?',
      'Der Vertrag wird aus dem Prüf-Gedächtnis gelöscht und beim nächsten Lauf erneut geprüft und gemeldet.',
      'ERNEUT PRÜFEN',
      'ABBRECHEN'
    );
    if (confirmed) {
      this.adminService.forgetContractCheck(contractId).subscribe(() => {
        this.checkResults = this.checkResults.filter(r => r.contractId !== contractId);
        this.toastService.info('Vertrag wird beim nächsten Lauf erneut geprüft.');
      });
    }
  }

  toggleResult(contractId: number) {
    this.expandedResult = this.expandedResult === contractId ? null : contractId;
  }

  // ==========================================
  // AUFKLAPPBARE ABSCHNITTE
  // ==========================================

  /**
   * Klappt einen Abschnitt auf oder zu.
   *
   * @param section der betroffene Abschnitt
   */
  toggleSection(section: AdminSection) {
    this.sections[section] = !this.sections[section];
  }

  /**
   * @param section der gefragte Abschnitt
   * @return true, wenn er gerade aufgeklappt ist
   */
  isSectionOpen(section: AdminSection): boolean {
    return this.sections[section];
  }

  // ==========================================
  // PROTOKOLL
  // ==========================================
  loadAuditEntries() {
    this.adminService.getAuditEntries(this.auditCategory, this.auditSeverity, 50).subscribe({
      next: (page) => {
        this.auditEntries = page.entries;
        this.auditTotal = page.total;
      },
      error: () => {
        this.auditEntries = [];
        this.auditTotal = 0;
      }
    });
  }

  toggleAudit(id: number) {
    this.expandedAudit = this.expandedAudit === id ? null : id;
  }

  severityClass(severity: string): string {
    switch (severity) {
      case 'ERROR': return 'status-err';
      case 'WARN': return 'status-warn';
      default: return 'status-ok';
    }
  }

  verdictClass(verdict: string): string {
    switch (verdict) {
      case 'OK': return 'status-ok';
      case 'WARN': return 'status-warn';
      default: return 'status-err';
    }
  }

  close() {
    this.closePanel.emit();
  }
}
