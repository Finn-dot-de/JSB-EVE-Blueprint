import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { FleetService } from '../../services/fleet.service';

@Component({
  selector: 'app-fleet-join',
  standalone: true,
  imports: [CommonModule, RouterLink],
  template: `
    <div class="roles-container" style="align-items: center; justify-content: center; height: 60vh;">
      <div class="surface-panel text-center" style="max-width: 400px;">
        @if (status() === 'loading') {
          <i class="fa-solid fa-satellite-dish fa-spin fa-3x" style="color: var(--accent-color); margin-bottom: 1rem;"></i>
          <h3>Verbinde mit Flotten-Netzwerk...</h3>
          <p style="color: var(--text-secondary);">Deine Teilnahme wird registriert.</p>
        }
        @else if (status() === 'success') {
          <i class="fa-regular fa-circle-check fa-3x" style="color: #00dfa2; margin-bottom: 1rem;"></i>
          <h3>FAT Erfolgreich!</h3>
          <p style="color: var(--text-secondary);">Deine Flotten-Teilnahme wurde sicher in der Datenbank protokolliert.</p>
          <button routerLink="/dashboard" class="btn-primary" style="margin-top: 1.5rem; width: 100%;">Zurück zum Dashboard</button>
        }
        @else {
          <i class="fa-regular fa-circle-xmark fa-3x" style="color: #ff3366; margin-bottom: 1rem;"></i>
          <h3>Fehler beim Eintragen</h3>
          <p style="color: #ff3366;">{{ errorMessage() }}</p>
          <button routerLink="/dashboard" class="btn-primary" style="margin-top: 1.5rem; width: 100%;">Zurück zum Dashboard</button>
        }
      </div>
    </div>
  `
})
export class FleetJoinComponent implements OnInit {
  private route = inject(ActivatedRoute);
  private fleetService = inject(FleetService);

  status = signal<'loading' | 'success' | 'error'>('loading');
  errorMessage = signal<string>('');

  ngOnInit() {
    const code = this.route.snapshot.paramMap.get('code');
    if (!code) {
      this.showError('Kein gültiger Tracking Code vorhanden.');
      return;
    }

    this.fleetService.joinFleet(code).subscribe({
      next: () => this.status.set('success'),
      error: (err) => this.showError(err.error?.message || 'Link abgelaufen oder ungültig.')
    });
  }

  private showError(msg: string) {
    this.errorMessage.set(msg);
    this.status.set('error');
  }
}
