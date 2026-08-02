import { Component, OnInit, OnDestroy, inject, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { FleetService, FleetEvent, FleetAttendance } from '../../services/fleet.service';
import { AuthService } from '../../services/auth.service';
import { ToastService } from '../../services/toast.service';
import { ConfirmService } from '../../services/confirm.service';
import { AssetService, DoctrineReadinessDto } from '../../services/asset.service';

@Component({
  selector: 'app-fleet-manager',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './fleet-manager.component.html',
  styleUrls: ['./fleet-manager.component.scss']
})
export class FleetManagerComponent implements OnInit, OnDestroy {
  public authService = inject(AuthService);
  private fleetService = inject(FleetService);
  private assetService = inject(AssetService);
  private toastService = inject(ToastService);
  private confirmService = inject(ConfirmService);

  // Tabs
  activeTab = signal<'FLEETS' | 'DOCTRINE'>('FLEETS');

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

  // --- Doctrine State ---
  doctrineNames = signal<string[]>([]);
  doctrine = signal<DoctrineReadinessDto | null>(null);
  loadingDoctrine = signal(false);
  selectedDoctrine: string | null = null;

  get isFleetCommander(): boolean {
    return this.authService.hasAnyRole(['ROLE_CEO', 'ROLE_DIRECTOR', 'ROLE_1337', 'ROLE_A38']);
  }

  get isMainFC(): boolean {
    // Erlaubt der ROLE_69 (und CEO/Director als Backup) den Doktrin-Check zu sehen
    return this.authService.hasAnyRole(['ROLE_CEO', 'ROLE_DIRECTOR', 'ROLE_69']);
  }

  ngOnInit() {
    this.loadRecentFleets();
    this.pollingInterval = setInterval(() => this.loadRecentFleets(), 10000);
  }

  ngOnDestroy() {
    if (this.pollingInterval) clearInterval(this.pollingInterval);
  }

  setTab(tab: 'FLEETS' | 'DOCTRINE') {
    this.activeTab.set(tab);
    if (tab === 'DOCTRINE' && this.doctrineNames().length === 0) {
      this.loadDoctrineNames();
    }
  }

  // ================= Fleet Logic =================
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
      'Möchtest du das Tracking für diesen FAT wirklich beenden? Die Flotte wird dadurch geschlossen.',
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

  copyLinkToClipboard(code: string) {
    const url = this.getJoinUrlFor(code);
    navigator.clipboard.writeText(url).then(() => {
      this.toastService.success('PAP-Link erfolgreich kopiert!');
    }).catch(err => {
      console.error('Konnte Link nicht kopieren: ', err);
      this.toastService.error('Fehler beim Kopieren des PAP-Links.');
    });
  }

  getJoinUrlFor(code: string): string {
    return `${window.location.origin}/fleet/join/${code}`;
  }

  // ================= Doctrine Logic =================
  loadDoctrineNames() {
    this.assetService.doctrines().subscribe({
      next: (names) => {
        this.doctrineNames.set(names);
        if (names.length > 0 && !this.selectedDoctrine) {
          this.selectedDoctrine = names[0];
          this.loadDoctrine();
        }
      },
      error: () => this.toastService.error('Doktrinen konnten nicht geladen werden.')
    });
  }

  loadDoctrine() {
    if (!this.selectedDoctrine) return;
    this.loadingDoctrine.set(true);
    this.assetService.doctrineReadiness(this.selectedDoctrine).subscribe({
      next: (data) => { this.doctrine.set(data); this.loadingDoctrine.set(false); },
      error: () => { this.loadingDoctrine.set(false); this.toastService.error('Doktrin-Auswertung fehlgeschlagen.'); }
    });
  }

  // ================= Utilities =================
  formatNumber(value: number | null | undefined): string {
    if (value === null || value === undefined || isNaN(value)) return '0';
    return value.toLocaleString('de-DE');
  }

  percent(value: number): string {
    return (value * 100).toFixed(0) + ' %';
  }

  coverageWidth(value: number): string {
    return Math.max(0, Math.min(100, value * 100)).toFixed(0) + '%';
  }

  onImgError(event: Event) {
    const target = event.target as HTMLImageElement;

    // Verhindert eine Endlosschleife, wenn das Fragezeichen-Bild selbst fehlen sollte
    if (target.src.includes('7_64_15.png')) return;

    // Wenn das normale Icon (oder Render) fehlschlägt, prüfen wir auf Blueprint (/bp?)
    if (target.src.includes('/icon?') || target.src.includes('/render?')) {
      const match = target.src.match(/\/types\/(\d+)\//);
      if (match) {
        // Blueprint Endpunkt setzen. Falls das auch fehlschlägt, triggert (error) erneut!
        target.src = `https://images.evetech.net/types/${match[1]}/bp?size=64`;
        return;
      }
    }

    // Wenn es kein Blueprint war (oder /bp? auch fehlgeschlagen ist), zeige das Fragezeichen
    target.src = 'https://evetycoon.com/images/icons/7_64_15.png';
  }

  onPortraitError(event: Event) {
    (event.target as HTMLImageElement).src = 'https://images.evetech.net/characters/1/portrait?size=64';
  }
}
