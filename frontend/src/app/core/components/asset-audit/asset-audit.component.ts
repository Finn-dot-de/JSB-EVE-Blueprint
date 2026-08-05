import {Component, OnInit, inject, signal, computed, ViewChild, ElementRef, HostListener} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subject, debounceTime, distinctUntilChanged, switchMap } from 'rxjs';
import {
  AssetService, AssetRowDto, AssetStackDto, PageDto, SummaryDto,
  FilterOptionsDto, TypeSuggestionDto, TypeHoldersDto,
  MemberAssetDetailDto, AssetSearchParams
} from '../../services/asset.service';
import { AuthService } from '../../services/auth.service';
import { ToastService } from '../../services/toast.service';
import { barWidth, formatIsk, formatIskFull, formatNumber, maxValue } from '../../shared/eve-format.util';
import { handlePortraitError, handleTypeImageError, typeIcon } from '../../shared/eve-image.util';

// DOKTRIN WURDE ENTFERNT
type Tab = 'OVERVIEW' | 'SEARCH' | 'HOLDERS' | 'MEMBER';

@Component({
  selector: 'app-asset-audit',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './asset-audit.component.html',
  styleUrls: ['./asset-audit.component.scss']
})
export class AssetAuditComponent implements OnInit {
  private assetService = inject(AssetService);
  public authService = inject(AuthService);
  private toastService = inject(ToastService);

  // Formatierung und Bildadressen kommen aus den gemeinsamen Utilities -
  // hier werden sie nur noch fuer das Template sichtbar gemacht.
  protected readonly formatIsk = formatIsk;
  protected readonly formatIskFull = formatIskFull;
  protected readonly formatNumber = formatNumber;
  protected readonly barWidth = barWidth;
  protected readonly maxValue = maxValue;
  protected readonly typeIcon = typeIcon;
  protected readonly onImgError = handleTypeImageError;
  protected readonly onPortraitError = handlePortraitError;

  activeTab = signal<Tab>('OVERVIEW');

  // --- UI States ---
  showAdvancedFilters = signal(false);
  showHoldersModal = signal(false);
  showMemberModal = signal(false);

  // --- Uebersicht ---
  summary = signal<SummaryDto | null>(null);
  loadingSummary = signal(false);

  // --- Suche ---
  filters = signal<FilterOptionsDto | null>(null);
  flatResult = signal<PageDto<AssetRowDto> | null>(null);
  groupedResult = signal<PageDto<AssetStackDto> | null>(null);
  loadingSearch = signal(false);
  grouped = signal(true);

  f: AssetSearchParams = {
    q: '', typeId: null, groupId: null, categoryId: null,
    characterId: null, mainId: null, corporationId: null, locationId: null,
    regionName: null, locationFlag: null, minQuantity: null, minValue: null,
    shipsOnly: false, ownerType: null, sort: 'value', direction: 'desc', page: 0, size: 50
  };

  // --- Typeahead ---
  suggestions = signal<TypeSuggestionDto[]>([]);
  showSuggestions = signal(false);
  private searchTerms = new Subject<string>();

  @ViewChild('typeaheadWrapper') typeaheadWrapper?: ElementRef;

  @HostListener('document:click', ['$event'])
  onClickOutside(event: Event) {
    if (this.showSuggestions() && this.typeaheadWrapper && !this.typeaheadWrapper.nativeElement.contains(event.target)) {
      this.showSuggestions.set(false);
    }
  }

  onSearchFocus() {
    if (this.f.q && this.f.q.length >= 2 && this.suggestions().length > 0) {
      this.showSuggestions.set(true);
    }
  }

  // --- Wer hat das? ---
  holders = signal<TypeHoldersDto | null>(null);
  loadingHolders = signal(false);
  expandedHolders = signal<Set<number>>(new Set());

  // --- Member-Detail ---
  memberDetail = signal<MemberAssetDetailDto | null>(null);
  loadingMember = signal(false);
  selectedMainId: number | null = null;

  get isLeadership(): boolean {
    return this.authService.hasAnyRole(['ROLE_CEO', 'ROLE_DIRECTOR', 'ROLE_IT_ADMIN']);
  }

  get isAdmin(): boolean {
    return this.authService.hasAnyRole(['ROLE_CEO', 'ROLE_IT_ADMIN']);
  }

  currentTotal = computed(() => this.grouped() ? this.groupedResult()?.totalElements ?? 0 : this.flatResult()?.totalElements ?? 0);
  currentPages = computed(() => this.grouped() ? this.groupedResult()?.totalPages ?? 0 : this.flatResult()?.totalPages ?? 0);
  grandTotal = computed(() => this.grouped() ? this.groupedResult()?.grandTotalValue ?? 0 : this.flatResult()?.grandTotalValue ?? 0);

  ngOnInit() {
    this.loadSummary();
    this.loadFilters();
    this.searchTerms.pipe(
      debounceTime(250),
      distinctUntilChanged(),
      switchMap(term => this.assetService.suggestTypes(term))
    ).subscribe({
      next: (res) => { this.suggestions.set(res); this.showSuggestions.set(res.length > 0); },
      error: () => this.suggestions.set([])
    });
  }

  setTab(tab: Tab) {
    this.activeTab.set(tab);
    if (tab === 'OVERVIEW' && !this.summary()) this.loadSummary();
    if (tab === 'SEARCH' && !this.flatResult() && !this.groupedResult()) this.runSearch();
  }

  closeModals() {
    this.showHoldersModal.set(false);
    this.showMemberModal.set(false);
  }

  // ================= Uebersicht & Suche & Member =================
  loadSummary() {
    this.loadingSummary.set(true);
    this.assetService.summary().subscribe({
      next: (data) => { this.summary.set(data); this.loadingSummary.set(false); },
      error: () => { this.loadingSummary.set(false); this.toastService.error('Auswertung konnte nicht geladen werden.'); }
    });
  }

  loadFilters() {
    this.assetService.filters(this.f.categoryId).subscribe({
      next: (data) => this.filters.set(data),
      error: () => this.toastService.error('Filter-Optionen konnten nicht geladen werden.')
    });
  }

  onCategoryChange() {
    this.f.groupId = null;
    this.loadFilters();
    this.runSearch();
  }

  onTypeAhead(term: string) {
    this.f.q = term;
    if (term && term.length >= 2) this.searchTerms.next(term);
    else this.showSuggestions.set(false);
  }

  pickSuggestion(s: TypeSuggestionDto) {
    this.f.q = s.typeName;
    this.f.typeId = s.typeId;
    this.showSuggestions.set(false);
    this.runSearch();
  }

  clearTypeFilter() {
    this.f.typeId = null;
    this.f.q = '';
    this.showSuggestions.set(false);
    this.runSearch();
  }

  runSearch(resetPage: boolean = true) {
    if (resetPage) this.f.page = 0;
    this.loadingSearch.set(true);
    this.showSuggestions.set(false);

    if (this.grouped()) {
      this.assetService.searchGrouped(this.f).subscribe({
        next: (res) => { this.groupedResult.set(res); this.flatResult.set(null); this.loadingSearch.set(false); },
        error: () => { this.loadingSearch.set(false); this.toastService.error('Suche fehlgeschlagen.'); }
      });
    } else {
      this.assetService.search(this.f).subscribe({
        next: (res) => { this.flatResult.set(res); this.groupedResult.set(null); this.loadingSearch.set(false); },
        error: () => { this.loadingSearch.set(false); this.toastService.error('Suche fehlgeschlagen.'); }
      });
    }
  }

  toggleGrouped() {
    this.grouped.update(v => !v);
    this.runSearch();
  }

  sortBy(column: string) {
    if (this.f.sort === column) this.f.direction = this.f.direction === 'desc' ? 'asc' : 'desc';
    else { this.f.sort = column; this.f.direction = 'desc'; }
    this.runSearch();
  }

  sortIcon(column: string): string {
    if (this.f.sort !== column) return 'fa-solid fa-sort';
    return this.f.direction === 'desc' ? 'fa-solid fa-sort-down' : 'fa-solid fa-sort-up';
  }

  nextPage() {
    if ((this.f.page ?? 0) + 1 < this.currentPages()) {
      this.f.page = (this.f.page ?? 0) + 1;
      this.runSearch(false);
    }
  }

  prevPage() {
    if ((this.f.page ?? 0) > 0) {
      this.f.page = (this.f.page ?? 0) - 1;
      this.runSearch(false);
    }
  }

  resetFilters() {
    this.f = {
      q: '', typeId: null, groupId: null, categoryId: null,
      characterId: null, mainId: null, corporationId: null, locationId: null,
      regionName: null, locationFlag: null, minQuantity: null, minValue: null,
      shipsOnly: false, ownerType: null, sort: 'value', direction: 'desc', page: 0, size: 50
    };
    this.loadFilters();
    this.runSearch();
  }

  exportCsv() {
    this.assetService.exportCsv({ ...this.f, grouped: this.grouped() }).subscribe({
      next: (blob) => {
        const url = window.URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        link.download = `assets-${new Date().toISOString().slice(0, 10)}.csv`;
        link.click();
        window.URL.revokeObjectURL(url);
        this.toastService.success('Export erstellt (max. 500 Zeilen).');
      },
      error: () => this.toastService.error('Export fehlgeschlagen.')
    });
  }

  openHolders(typeId: number, typeName?: string) {
    this.showHoldersModal.set(true);
    this.loadingHolders.set(true);
    this.expandedHolders.set(new Set());
    this.assetService.holders(typeId).subscribe({
      next: (data) => { this.holders.set(data); this.loadingHolders.set(false); },
      error: () => { this.loadingHolders.set(false); this.toastService.error(`Konnte Besitzer von ${typeName ?? typeId} nicht laden.`); }
    });
  }

  toggleHolder(mainId: number) {
    this.expandedHolders.update(set => {
      const next = new Set(set);
      if (next.has(mainId)) next.delete(mainId); else next.add(mainId);
      return next;
    });
  }

  isHolderExpanded(mainId: number): boolean { return this.expandedHolders().has(mainId); }

  openMember(mainId: number) {
    this.showMemberModal.set(true);
    this.selectedMainId = mainId;
    this.loadingMember.set(true);
    this.assetService.memberDetail(mainId).subscribe({
      next: (data) => { this.memberDetail.set(data); this.loadingMember.set(false); },
      error: () => { this.loadingMember.set(false); this.toastService.error('Member-Details konnten nicht geladen werden.'); }
    });
  }

  onMemberSelectChange() {
    if (this.selectedMainId) this.openMember(Number(this.selectedMainId));
  }

  resolveLocations() {
    this.assetService.resolveLocations().subscribe({
      next: () => this.toastService.info('Standort-Aufloesung angestossen. Ergebnis nach dem naechsten Sync sichtbar.'),
      error: () => this.toastService.error('Aufloesung konnte nicht gestartet werden.')
    });
  }

}
