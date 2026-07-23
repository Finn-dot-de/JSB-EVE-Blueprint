import { Component, OnInit, OnDestroy, inject, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { FleetService, FleetEvent, FleetAttendance } from '../../services/fleet.service';
import { AuthService } from '../../services/auth.service';
import { ToastService } from '../../services/toast.service';
import {ConfirmService} from '../../services/confirm.service';

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
  private toastService = inject(ToastService);
  private confirmService = inject(ConfirmService);

  recentFleets = signal<FleetEvent[]>([]);
  attendanceList = signal<FleetAttendance[]>([]);
  selectedFleetId = signal<number | null>(null);

  // Modal Status
  showCreateModal = signal(false);

  // FC Formular
  fleetName = '';
  doctrine = '';
  expiryMinutes = 60;
  trackingType: 'LIVE' | 'LINK' = 'LIVE';

  isCreating = signal(false);
  isSyncing = signal(false);

  private pollingInterval: any;

  selectedFleetObj = computed(() => {
    return this.recentFleets().find(f => f.id === this.selectedFleetId());
  });

  ngOnInit() {
    this.loadRecentFleets();
    this.pollingInterval = setInterval(() => this.loadRecentFleets(), 10000);
  }

  ngOnDestroy() {
    if (this.pollingInterval) clearInterval(this.pollingInterval);
  }

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

  get isFleetCommander(): boolean {
    return this.authService.hasAnyRole(['ROLE_CEO', 'ROLE_DIRECTOR', 'ROLE_1337', 'ROLE_A38']);
  }

  createFleet() {
    if (!this.fleetName) return;
    this.isCreating.set(true);

    this.fleetService.createFleet({
      fleetName: this.fleetName,
      doctrine: this.doctrine,
      linkExpiryMinutes: this.expiryMinutes,
      trackingType: this.trackingType
    }).subscribe({
      next: () => {
        this.isCreating.set(false);
        this.showCreateModal.set(false); // Modal schließen
        this.fleetName = ''; // Formular zurücksetzen
        this.doctrine = '';
        this.loadRecentFleets();
        this.toastService.success('Flotte erfolgreich gestartet!');
      },
      error: (err) => {
        this.isCreating.set(false);
        this.toastService.error(err.error?.message || 'Unbekannter Fehler beim Erstellen der Flotte.');
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

    // Die Code-Ausführung "wartet" hier (await), bis der User im Modal klickt
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
}
