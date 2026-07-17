import { Component, OnInit, OnDestroy, inject, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { FleetService, FleetEvent, FleetAttendance } from '../../services/fleet.service';
import { AuthService } from '../../services/auth.service';

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
  syncResult = signal<string | null>(null);

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
    return this.authService.hasAnyRole(['ROLE_CEO', 'ROLE_DIRECTOR', 'ROLE_FC', 'ROLE_A38']);
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
      },
      error: (err) => {
        this.isCreating.set(false);
        alert(err.error?.message || 'Unbekannter Fehler beim Erstellen der Flotte.');
      }
    });
  }

  syncEsi(fleetId: number) {
    this.isSyncing.set(true);
    this.fleetService.syncFleetViaEsi(fleetId).subscribe({
      next: (count) => {
        this.syncResult.set(`Sync OK: ${count} neue Member!`);
        this.isSyncing.set(false);
        this.loadAttendance(fleetId);
        setTimeout(() => this.syncResult.set(null), 5000); // Nachricht nach 5s ausblenden
      },
      error: (err) => {
        this.syncResult.set(err.error?.message || 'ESI Fehler');
        this.isSyncing.set(false);
        setTimeout(() => this.syncResult.set(null), 5000);
      }
    });
  }

  // NEU: Akzeptiert jetzt direkt die ID aus der Tabelle
  closeFleet(fleetId: number) {
    if (confirm('Tracking für diesen FAT wirklich beenden?')) {
      this.fleetService.closeFleet(fleetId).subscribe({
        next: () => {
          this.syncResult.set(null);
          this.loadRecentFleets();
        }
      });
    }
  }

  copyLinkToClipboard(code: string) {
    const url = this.getJoinUrlFor(code);
    navigator.clipboard.writeText(url).then(() => {
      alert('PAP-Link in die Zwischenablage kopiert!');
    }).catch(err => {
      console.error('Konnte Link nicht kopieren: ', err);
      alert('Fehler beim Kopieren des Links.');
    });
  }

  getJoinUrlFor(code: string): string {
    return `${window.location.origin}/fleet/join/${code}`;
  }
}
