import { Component, OnInit, inject, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { CharacterService, CorpStatsDto } from '../../services/character.service';

@Component({
  selector: 'app-corp-stats',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './corp-stats.component.html',
  styleUrls: ['./corp-stats.component.scss']
})
export class CorpStatsComponent implements OnInit {
  private charService = inject(CharacterService);

  stats = signal<CorpStatsDto | null>(null);
  errorMsg = signal<string | null>(null);
  loading = signal(true);

  // Berechnet den prozentualen Anteil für den CSS-Kreis (z.B. 52%)
  authPercentage = computed(() => {
    const s = this.stats();
    if (!s || s.totalEsiMembers === 0) return 0;
    return Math.round((s.registeredMains / s.totalEsiMembers) * 100);
  });

  ngOnInit() {
    this.charService.getCorpStats().subscribe({
      next: (data) => {
        this.stats.set(data);
        this.loading.set(false);
      },
      error: (err) => {
        this.errorMsg.set(err.error?.message || 'Fehler beim Laden der Statistiken.');
        this.loading.set(false);
      }
    });
  }
}
