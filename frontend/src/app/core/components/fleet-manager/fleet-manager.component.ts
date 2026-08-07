import { Component, OnInit, OnDestroy, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { computed } from '@angular/core';

import { FleetService, FleetEvent, FleetAttendance } from '../../services/fleet.service';
import { AuthService } from '../../services/auth.service';
import { ToastService } from '../../services/toast.service';
import { ConfirmService } from '../../services/confirm.service';
import { DoctrinesComponent } from '../doctrines/doctrines.component';
import {
  ReadinessService,
  CharacterReadinessDto,
  DoctrineReadinessDto,
  FitReadinessDto,
  SandboxResultDto,
  AccountReadinessDto
} from '../../services/readiness.service';
import { formatNumber } from '../../shared/eve-format.util';
import { handlePortraitError, handleTypeImageError, portrait } from '../../shared/eve-image.util';
import { copyText } from '../../shared/clipboard.util';
import { toPlanLines, toSkillPlanText } from '../../shared/skill-plan.util';

type TabId = 'FLEETS' | 'DOCTRINES' | 'BOARD' | 'SANDBOX';

@Component({
  selector: 'app-fleet-manager',
  standalone: true,
  imports: [CommonModule, FormsModule, DoctrinesComponent],
  templateUrl: './fleet-manager.component.html',
  styleUrls: ['./fleet-manager.component.scss']
})
export class FleetManagerComponent implements OnInit, OnDestroy {
  public authService = inject(AuthService);
  private fleetService = inject(FleetService);
  private readinessService = inject(ReadinessService);
  private toastService = inject(ToastService);
  private confirmService = inject(ConfirmService);

  // Formatierung und Bildadressen kommen aus den gemeinsamen Utilities -
  // hier werden sie nur noch fuer das Template sichtbar gemacht.
  protected readonly formatNumber = formatNumber;
  protected readonly portrait = portrait;
  protected readonly onImgError = handleTypeImageError;
  protected readonly onPortraitError = handlePortraitError;

  activeTab = signal<TabId>('FLEETS');

  // --- Fleet State ---
  recentFleets = signal<FleetEvent[]>([]);
  attendanceList = signal<FleetAttendance[]>([]);
  selectedFleetId = signal<number | null>(null);

  showCreateModal = signal(false);
  fleetName = '';
  doctrineInput = '';
  expiryMinutes = 60;
  trackingType: 'LIVE' | 'LINK' = 'LIVE';

  isCreating = signal(false);
  isSyncing = signal(false);
  private pollingInterval: any;

  selectedFleetObj = computed(() => {
    return this.recentFleets().find(f => f.id === this.selectedFleetId());
  });

  // --- Readiness State ---
  doctrineNames = signal<string[]>([]);
  selectedDoctrine: string | null = null;
  board = signal<DoctrineReadinessDto | null>(null);
  loadingBoard = signal(false);

  memberFilter = signal('');

  expandedFits = signal<Set<number>>(new Set());
  expandedAccounts = signal<Set<string>>(new Set()); // Key: "fitKey:mainId"

  // --- Sandbox State ---
  sandboxInput = signal('');
  sandboxResult = signal<SandboxResultDto | null>(null);
  sandboxError = signal<string | null>(null);
  loadingSandbox = signal(false);

  get isFleetCommander(): boolean {
    return this.authService.hasAnyRole(['ROLE_CEO', 'ROLE_DIRECTOR', 'ROLE_1337', 'ROLE_A38', 'ROLE_69']);
  }

  get canSeeReadiness(): boolean {
    return this.authService.hasAnyRole([
      'ROLE_IT_ADMIN', 'ROLE_CEO', 'ROLE_DIRECTOR', 'ROLE_MANAGER', 'ROLE_69', 'ROLE_1337', 'ROLE_A38'
    ]);
  }

  ngOnInit() {
    this.loadRecentFleets();
    this.pollingInterval = setInterval(() => this.loadRecentFleets(), 10000);
  }

  ngOnDestroy() {
    if (this.pollingInterval) clearInterval(this.pollingInterval);
  }

  setTab(tab: TabId) {
    this.activeTab.set(tab);

    if (tab === 'BOARD') {
      if (this.doctrineNames().length === 0) {
        this.loadDoctrineNames('BOARD');
        return;
      }
      if (!this.board()) this.loadBoard();
    }
  }

  // ================= Fleet Logic =================
  // (Unverändert)

  loadRecentFleets() {
    this.fleetService.getRecentFleets().subscribe(fleets => {
      this.recentFleets.set(fleets);
      if (this.selectedFleetId()) {
        this.loadAttendance(this.selectedFleetId()!);
      } else if (fleets.length > 0) {
        this.selectFleet(fleets[0].id);
      }
    });
  }

  selectFleet(eventId: number) {
    this.selectedFleetId.set(eventId);
    this.loadAttendance(eventId);
  }

  loadAttendance(eventId: number) {
    this.fleetService.getFleetAttendance(eventId).subscribe(att => {
      this.attendanceList.set(att);
    });
  }

  createFleet() {
    if (!this.fleetName) return;
    this.isCreating.set(true);
    this.fleetService.createFleet({
      fleetName: this.fleetName,
      doctrine: this.doctrineInput,
      linkExpiryMinutes: this.expiryMinutes,
      trackingType: this.trackingType
    }).subscribe({
      next: () => {
        this.isCreating.set(false);
        this.showCreateModal.set(false);
        this.fleetName = '';
        this.doctrineInput = '';
        this.loadRecentFleets();
        this.toastService.success('Flotte erfolgreich gestartet!');
      },
      error: (err) => {
        this.isCreating.set(false);
        this.toastService.error(err.error?.message || 'Fehler beim Erstellen der Flotte.');
      }
    });
  }

  syncEsi(fleetId: number) {
    this.isSyncing.set(true);
    this.fleetService.syncFleetViaEsi(fleetId).subscribe({
      next: (count) => {
        this.toastService.success(`Sync abgeschlossen: ${count} neue Member erfasst!`);
        this.isSyncing.set(false);
        this.loadAttendance(fleetId);
      },
      error: (err) => {
        this.toastService.error(err.error?.message || 'ESI Fehler beim Synchronisieren.');
        this.isSyncing.set(false);
      }
    });
  }

  async closeFleet(fleetId: number) {
    const confirmed = await this.confirmService.ask(
      'Tracking beenden?',
      'Möchtest du das Tracking für diesen FAT wirklich beenden?',
      'FAT beenden',
      'Abbrechen'
    );
    if (confirmed) {
      this.fleetService.closeFleet(fleetId).subscribe({
        next: () => {
          this.toastService.info('Flotten-Tracking wurde beendet.');
          this.loadRecentFleets();
        }
      });
    }
  }

  copyLinkToClipboard(code: string): Promise<void> {
    return copyText(this.getJoinUrlFor(code)).then((ok) =>
      ok
        ? this.toastService.success('PAP-Link erfolgreich kopiert!')
        : this.toastService.error('Fehler beim Kopieren des PAP-Links.'));
  }

  getJoinUrlFor(code: string): string {
    return `${window.location.origin}/fleet/join/${code}`;
  }

  // ================= Readiness Logic =================

  loadDoctrineNames(thenLoad?: TabId) {
    this.readinessService.doctrines().subscribe({
      next: (names) => {
        this.doctrineNames.set(names);
        if (names.length > 0 && !this.selectedDoctrine) {
          this.selectedDoctrine = names[0];
        }
        if (thenLoad === 'BOARD') this.loadBoard();
      },
      error: () => this.toastService.error('Doktrinen konnten nicht geladen werden.')
    });
  }

  onDoctrineChange() {
    this.board.set(null);
    this.expandedFits.set(new Set());
    this.expandedAccounts.set(new Set());

    if (this.activeTab() === 'BOARD') this.loadBoard();
  }

  loadBoard() {
    if (!this.selectedDoctrine) return;
    this.loadingBoard.set(true);
    this.readinessService.checkBoard(this.selectedDoctrine).subscribe({
      next: (data) => {
        this.board.set(data);
        this.loadingBoard.set(false);
        if (data.fits.length > 0) this.expandedFits.set(new Set([this.fitKey(data.fits[0])]));
      },
      error: (err) => {
        this.loadingBoard.set(false);
        this.toastService.error(err.error?.message || 'Readiness-Check fehlgeschlagen.');
      }
    });
  }

  // ================= Sandbox Logic =================

  runSandbox() {
    const eft = this.sandboxInput().trim();
    if (!eft) return;

    this.loadingSandbox.set(true);
    this.sandboxError.set(null);

    this.readinessService.sandbox(eft).subscribe({
      next: (data) => {
        this.sandboxResult.set(data);
        this.loadingSandbox.set(false);
        this.expandedAccounts.set(new Set());
      },
      error: (err) => {
        this.loadingSandbox.set(false);
        this.sandboxResult.set(null);
        this.sandboxError.set(err.error?.message || 'Das Fitting konnte nicht ausgewertet werden.');
      }
    });
  }

  clearSandbox() {
    this.sandboxInput.set('');
    this.sandboxResult.set(null);
    this.sandboxError.set(null);
  }

  // ================= Aufklapp-Logik =================

  /**
   * Ein stabiler Schlüssel je Fit.
   *
   * Nicht die typeId: eine Doktrin kann zwei Fits derselben Hülle enthalten,
   * die sich sonst den Aufklapp-Zustand teilen würden. Der Sandbox-Fit hat
   * keine ID - er steht ohnehin allein und immer offen.
   */
  fitKey(fit: FitReadinessDto): number {
    return fit.fitId ?? -fit.typeId;
  }

  toggleFit(key: number) {
    this.expandedFits.update(current => {
      const next = new Set(current);
      next.has(key) ? next.delete(key) : next.add(key);
      return next;
    });
  }

  isFitExpanded(key: number): boolean {
    return this.expandedFits().has(key);
  }

  toggleAccount(fitKey: number, mainId: number) {
    const key = `${fitKey}:${mainId}`;
    this.expandedAccounts.update(current => {
      const next = new Set(current);
      next.has(key) ? next.delete(key) : next.add(key);
      return next;
    });
  }

  isAccountExpanded(fitKey: number, mainId: number): boolean {
    return this.expandedAccounts().has(`${fitKey}:${mainId}`);
  }

  // ================= Filter =================

  filterAccounts(accounts: AccountReadinessDto[]): AccountReadinessDto[] {
    const q = this.memberFilter().trim().toLowerCase();
    if (!q) return accounts;
    return accounts.filter(a =>
      a.mainName.toLowerCase().includes(q) ||
      a.characters.some(c => c.characterName.toLowerCase().includes(q))
    );
  }

  // ================= Utilities =================

    percent(value: number): string {
    return (value * 100).toFixed(0) + ' %';
  }

  coverageWidth(value: number): string {
    return Math.max(0, Math.min(100, value * 100)).toFixed(0) + '%';
  }

  coverageClass(value: number): string {
    if (value >= 0.75) return 'green';
    if (value >= 0.4) return 'orange';
    return 'red';
  }

  copyFitToClipboard(eft: string): Promise<void> {
    return copyText(eft).then((ok) =>
      ok
        ? this.toastService.info(
            'Fitting kopiert! Ingame das Fitting-Fenster öffnen und "Import from Clipboard" wählen.')
        : this.toastService.error('Fehler beim Kopieren in die Zwischenablage.'));
  }

  /**
   * Legt die fehlenden Skills eines Piloten als Plantext in die Zwischenablage.
   *
   * Beide Quellen zusammen - Voraussetzungen und Skillplan. So kann ein FC
   * einem Piloten genau die Liste geben, die er ingame einfügen muss.
   */
  copyMissingSkills(character: CharacterReadinessDto): Promise<void> {
    const text = toSkillPlanText(
      toPlanLines([...character.missingSkills, ...character.missingPlanSkills]));
    if (!text) {
      this.toastService.info(`${character.characterName} fehlt nichts.`);
      return Promise.resolve();
    }

    return copyText(text).then((ok) =>
      ok
        ? this.toastService.success(`Fehlende Skills von ${character.characterName} kopiert.`)
        : this.toastService.error('Fehler beim Kopieren in die Zwischenablage.'));
  }

  /** Ob es bei diesem Piloten überhaupt etwas zu kopieren gibt. */
  hasMissingSkills(character: CharacterReadinessDto): boolean {
    return character.missingSkills.length + character.missingPlanSkills.length > 0;
  }

    
}
