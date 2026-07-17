import { Component, OnInit, OnDestroy, inject, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { FleetService, FleetEvent, FleetAttendance } from '../../services/fleet.service';
import { AuthService } from '../../auth/auth.service';

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

  // FC Formular
  fleetName = '';
  doctrine = '';
  expiryMinutes = 60;
  isCreating = signal(false);
  isSyncing = signal(false);
  syncResult = signal<string | null>(null);

  private pollingInterval: any;

  // --- HIGHLIGHT: Intelligente Computed Signals für sauberes HTML ---
  myActiveFleet = computed(() => {
    const myId = this.authService.currentUser()?.characterId;
    return this.recentFleets().find(f => f.fcCharacterId === myId && !f.endTime);
  });

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
      fleetName: this.fleetName, doctrine: this.doctrine, linkExpiryMinutes: this.expiryMinutes
    }).subscribe({
      next: () => {
        this.isCreating.set(false);
        this.loadRecentFleets();
      },
      error: () => this.isCreating.set(false)
    });
  }

  syncEsi() {
    const fleet = this.myActiveFleet();
    if (!fleet) return;
    this.isSyncing.set(true);
    this.fleetService.syncFleetViaEsi(fleet.id).subscribe({
      next: (count) => {
        this.syncResult.set(`Sync OK: ${count} neue Member!`);
        this.isSyncing.set(false);
        this.loadAttendance(fleet.id);
      },
      error: (err) => {
        this.syncResult.set(err.error?.message || 'ESI Fehler');
        this.isSyncing.set(false);
      }
    });
  }

  closeFleet() {
    const fleet = this.myActiveFleet();
    if (!fleet) return;
    if (confirm('Tracking für diesen FAT wirklich beenden?')) {
      this.fleetService.closeFleet(fleet.id).subscribe({
        next: () => {
          this.syncResult.set(null);
          this.loadRecentFleets();
        }
      });
    }
  }

  copyLinkToClipboard(code: string) {
    const url = this.getJoinUrlFor(code);
    navigator.clipboard.writeText(url);
    alert('PAP-Link in die Zwischenablage kopiert!');
  }

  getJoinUrlFor(code: string): string {
    return `${window.location.origin}/fleet/join/${code}`;
  }
}
