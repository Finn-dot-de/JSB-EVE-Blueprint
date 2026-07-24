import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms'; // <-- WICHTIG für Suchfeld
import { CharacterService, CorpStatsDto } from '../../services/character.service';

@Component({
  selector: 'app-corp-stats',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './corp-stats.component.html',
  styleUrls: ['./corp-stats.component.scss']
})
export class CorpStatsComponent implements OnInit {
  private charService = inject(CharacterService);

  stats = signal<CorpStatsDto[]>([]);
  errorMsg = signal<string | null>(null);
  loading = signal(true);

  // States für Aufklappen und Suche
  expandedCorpId = signal<number | null>(null);
  searchQuery = signal<string>('');

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

  getPercentage(s: CorpStatsDto): number {
    if (!s || s.totalEsiMembers === 0) return 0;
    return Math.round((s.totalRegisteredChars / s.totalEsiMembers) * 100);
  }

  // Karte auf- und zuklappen
  toggleCorp(corpId: number) {
    if (this.expandedCorpId() === corpId) {
      this.expandedCorpId.set(null);
    } else {
      this.expandedCorpId.set(corpId);
    }
    this.searchQuery.set(''); // Suche beim Umschalten zurücksetzen
  }

  // Filtern der Geauthten (durchsucht Main + Alts)
  getFilteredAuthed(corp: CorpStatsDto) {
    const q = this.searchQuery().toLowerCase();
    if (!q) return corp.authedMembers;
    return corp.authedMembers.filter(m =>
      m.mainName.toLowerCase().includes(q) ||
      m.alts.some(a => a.name.toLowerCase().includes(q))
    );
  }

  // Filtern der Nicht Geauthten
  getFilteredUnauthed(corp: CorpStatsDto) {
    const q = this.searchQuery().toLowerCase();
    if (!q) return corp.unauthedMembers;
    return corp.unauthedMembers.filter(u => u.name.toLowerCase().includes(q));
  }
}
