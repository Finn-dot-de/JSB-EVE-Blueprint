import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { CharacterService, CorpStatsDto, AdminAccountDto } from '../../services/character.service';

@Component({
  selector: 'app-corp-stats',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './corp-stats.component.html',
  styleUrls: ['./corp-stats.component.scss']
})
export class CorpStatsComponent implements OnInit {
  private charService = inject(CharacterService);

  // Tab State
  activeTab = signal<'CORP' | 'ACCOUNTS'>('CORP');

  // Daten
  stats = signal<CorpStatsDto[]>([]);
  accounts = signal<AdminAccountDto[]>([]);

  errorMsg = signal<string | null>(null);
  loading = signal(true);
  loadingAccounts = signal(false);

  // Filter
  expandedCorpId = signal<number | null>(null);
  searchQuery = signal<string>('');
  searchQueryAccounts = signal<string>('');

  ngOnInit() {
    this.loadCorpStats();
  }

  setTab(tab: 'CORP' | 'ACCOUNTS') {
    this.activeTab.set(tab);
    if (tab === 'ACCOUNTS' && this.accounts().length === 0) {
      this.loadAccounts();
    }
  }

  loadCorpStats() {
    this.loading.set(true);
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

  loadAccounts() {
    this.loadingAccounts.set(true);
    this.charService.getAllAccounts().subscribe({
      next: (data) => {
        this.accounts.set(data);
        this.loadingAccounts.set(false);
      },
      error: (err) => {
        this.errorMsg.set(err.error?.message || 'Fehler beim Laden der Account-Liste.');
        this.loadingAccounts.set(false);
      }
    });
  }

  getPercentage(s: CorpStatsDto): number {
    if (!s || s.totalEsiMembers === 0) return 0;
    return Math.round((s.totalRegisteredChars / s.totalEsiMembers) * 100);
  }

  toggleCorp(corpId: number) {
    if (this.expandedCorpId() === corpId) {
      this.expandedCorpId.set(null);
    } else {
      this.expandedCorpId.set(corpId);
    }
    this.searchQuery.set('');
  }

  getFilteredAuthed(corp: CorpStatsDto) {
    const q = this.searchQuery().toLowerCase();
    if (!q) return corp.authedMembers;
    return corp.authedMembers.filter(m =>
      m.mainName.toLowerCase().includes(q) ||
      m.alts.some(a => a.name.toLowerCase().includes(q))
    );
  }

  getFilteredUnauthed(corp: CorpStatsDto) {
    const q = this.searchQuery().toLowerCase();
    if (!q) return corp.unauthedMembers;
    return corp.unauthedMembers.filter(u => u.name.toLowerCase().includes(q));
  }

  // Filter für den neuen Tab
  getFilteredAccounts() {
    const q = this.searchQueryAccounts().toLowerCase();
    if (!q) return this.accounts();

    return this.accounts().filter(acc =>
      acc.mainName.toLowerCase().includes(q) ||
      acc.alts.some(alt => alt.name.toLowerCase().includes(q))
    );
  }
}
